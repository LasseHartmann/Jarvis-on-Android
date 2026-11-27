package com.jarvis.assistant.ui.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.agent.AuditLogEntry
import com.jarvis.assistant.agent.JarvisLanguageMode
import com.jarvis.assistant.agent.JarvisMemory
import com.jarvis.assistant.agent.JarvisProtocol
import com.jarvis.assistant.agent.JarvisReminder
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.SpokenLanguage
import com.jarvis.assistant.device.DeviceTelemetry
import com.jarvis.assistant.services.JarvisWakeWordService
import com.jarvis.assistant.services.ReminderScheduler
import com.jarvis.assistant.voice.JarvisSpeechOptions
import com.jarvis.assistant.voice.SpeakerVoiceprintEngine
import com.jarvis.assistant.widget.JarvisHudWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class VoiceprintUiState(
    val isRecording: Boolean = false,
    val rmsLevel: Float = 0f,
    val statusMessage: String = "",
    val lastTestMatchPercent: Int? = null,
    val lastTestPassed: Boolean? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as JarvisApplication).container

    val settings: StateFlow<JarvisSettings> = container.settingsStore.settings
    val telemetry: StateFlow<DeviceTelemetry> = container.telemetryMonitor.telemetry
    val memories: StateFlow<List<JarvisMemory>> = container.repository.memories
    val auditLogs: StateFlow<List<AuditLogEntry>> = container.repository.auditLogs
    val protocols: StateFlow<List<JarvisProtocol>> = container.repository.protocols
    val reminders: StateFlow<List<JarvisReminder>> = container.repository.reminders

    private val _voiceprintUi = MutableStateFlow(VoiceprintUiState())
    val voiceprintUi: StateFlow<VoiceprintUiState> = _voiceprintUi.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                container.telemetryMonitor.refresh()
                delay(4_000L)
            }
        }
    }

    fun refreshTelemetry() {
        container.telemetryMonitor.refresh()
        syncWakeWordService()
        JarvisHudWidgetProvider.requestUpdate(getApplication())
    }

    fun syncWakeWordService() {
        JarvisWakeWordService.syncWithSettings(getApplication(), container.settingsStore.current())
    }

    fun updateSettings(transform: (JarvisSettings) -> JarvisSettings) {
        container.settingsStore.update(transform)
        syncWakeWordService()
        JarvisHudWidgetProvider.requestUpdate(getApplication())
    }

    fun recordVoiceprintSample() {
        if (_voiceprintUi.value.isRecording) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val phrase = container.settingsStore.current().wakeWordModel.displayName
            _voiceprintUi.value = VoiceprintUiState(
                isRecording = true,
                statusMessage = "Listening... say \"$phrase, good evening\""
            )
            // Temporarily release standby wake-word mic so AudioRecord has exclusive access on HyperOS
            runCatching { app.stopService(Intent(app, JarvisWakeWordService::class.java)) }
            delay(280L)

            try {
                val pcm = SpeakerVoiceprintEngine.recordEnrollmentClip(
                    context = app,
                    durationMs = 2_400L,
                    onRms = { rms ->
                        _voiceprintUi.value = _voiceprintUi.value.copy(rmsLevel = rms)
                    }
                )
                if (pcm == null) {
                    _voiceprintUi.value = VoiceprintUiState(
                        statusMessage = "Microphone unavailable — grant Mic permission first."
                    )
                    return@launch
                }

                _voiceprintUi.value = _voiceprintUi.value.copy(
                    isRecording = false,
                    rmsLevel = 0f,
                    statusMessage = "Analyzing neural voiceprint..."
                )

                val emb = withContext(Dispatchers.Default) {
                    SpeakerVoiceprintEngine.fromAssets(app).use { engine ->
                        engine.extractEmbedding(pcm)
                    }
                }

                if (emb == null) {
                    _voiceprintUi.value = VoiceprintUiState(
                        statusMessage = "Too quiet — please speak clearly closer to the mic and try again."
                    )
                    return@launch
                }

                val current = container.settingsStore.current()
                val existing = SpeakerVoiceprintEngine.parseVoiceprints(current.enrolledVoiceprintsJson).toMutableList()
                existing.add(emb)
                while (existing.size > 3) existing.removeAt(0)
                val serialized = SpeakerVoiceprintEngine.serializeVoiceprints(existing)
                val autoEnable = existing.size >= 2 || current.voiceprintLockEnabled

                container.settingsStore.update {
                    it.copy(
                        enrolledVoiceprintsJson = serialized,
                        voiceprintLockEnabled = autoEnable
                    )
                }
                JarvisHudWidgetProvider.requestUpdate(app)

                _voiceprintUi.value = VoiceprintUiState(
                    statusMessage = if (existing.size >= 3) {
                        "Voiceprint complete (3/3 samples). Voiceprint Lock is active."
                    } else {
                        "Saved sample ${existing.size}/3. Record ${3 - existing.size} more for best accuracy."
                    }
                )
            } catch (err: Throwable) {
                _voiceprintUi.value = VoiceprintUiState(
                    statusMessage = "Enrollment error: ${err.message ?: "unknown"}"
                )
            } finally {
                syncWakeWordService()
            }
        }
    }

    fun testVoiceprintMatch() {
        if (_voiceprintUi.value.isRecording) return
        val current = container.settingsStore.current()
        val enrolled = SpeakerVoiceprintEngine.parseVoiceprints(current.enrolledVoiceprintsJson)
        if (enrolled.isEmpty()) {
            _voiceprintUi.value = VoiceprintUiState(statusMessage = "Record at least one voice sample first.")
            return
        }

        viewModelScope.launch {
            val app = getApplication<Application>()
            _voiceprintUi.value = VoiceprintUiState(
                isRecording = true,
                statusMessage = "Listening... say \"${current.wakeWordModel.displayName}\" now"
            )
            runCatching { app.stopService(Intent(app, JarvisWakeWordService::class.java)) }
            delay(280L)

            try {
                val pcm = SpeakerVoiceprintEngine.recordEnrollmentClip(
                    context = app,
                    durationMs = 2_200L,
                    onRms = { rms ->
                        _voiceprintUi.value = _voiceprintUi.value.copy(rmsLevel = rms)
                    }
                )
                if (pcm == null) {
                    _voiceprintUi.value = VoiceprintUiState(statusMessage = "Microphone unavailable.")
                    return@launch
                }

                _voiceprintUi.value = _voiceprintUi.value.copy(
                    isRecording = false,
                    rmsLevel = 0f,
                    statusMessage = "Comparing voiceprint..."
                )

                val score = withContext(Dispatchers.Default) {
                    SpeakerVoiceprintEngine.fromAssets(app).use { engine ->
                        engine.verify(pcm, enrolled)
                    }
                }
                val pct = (score * 100f).roundToInt().coerceIn(0, 100)
                val threshold = current.voiceprintSensitivity.cosineThreshold
                val passed = score >= threshold
                val threshPct = (threshold * 100f).roundToInt()

                _voiceprintUi.value = VoiceprintUiState(
                    statusMessage = if (passed) {
                        "Verified ($pct% match · threshold $threshPct%)"
                    } else {
                        "Rejected ($pct% match · needs $threshPct%)"
                    },
                    lastTestMatchPercent = pct,
                    lastTestPassed = passed
                )
            } catch (err: Throwable) {
                _voiceprintUi.value = VoiceprintUiState(statusMessage = "Verification failed: ${err.message}")
            } finally {
                syncWakeWordService()
            }
        }
    }

    fun clearVoiceprints() {
        container.settingsStore.update {
            it.copy(enrolledVoiceprintsJson = "", voiceprintLockEnabled = false)
        }
        _voiceprintUi.value = VoiceprintUiState(statusMessage = "Voiceprint erased.")
        syncWakeWordService()
        JarvisHudWidgetProvider.requestUpdate(getApplication())
    }

    fun testVoiceSample(languageOverride: SpokenLanguage? = null) {
        val s = container.settingsStore.current()
        val lang = languageOverride ?: when (s.languageMode) {
            JarvisLanguageMode.GERMAN_ONLY -> SpokenLanguage.GERMAN
            else -> SpokenLanguage.ENGLISH
        }
        val sampleText = if (lang == SpokenLanguage.GERMAN) {
            "J.A.R.V.I.S. ist online und zu Ihren Diensten, ${s.userTitle}. Alle Systeme laufen einwandfrei."
        } else {
            "J.A.R.V.I.S. online and at your service, ${s.userTitle}. All Xiaomi 15T Pro systems are operational."
        }
        container.voiceEngine.speak(
            text = sampleText,
            options = JarvisSpeechOptions(
                geminiApiKey = s.geminiApiKey,
                neuralVoiceEnabled = s.neuralJarvisVoice,
                neuralVoiceName = s.voiceProfile.geminiVoiceName,
                intercomDspEnabled = s.intercomDspEnabled,
                speechRate = s.ttsSpeechRate,
                pitch = s.ttsPitch,
                language = lang
            )
        )
    }

    fun deleteMemory(id: Long) {
        container.repository.deleteMemoryById(id)
    }

    fun deleteProtocol(id: Long) {
        container.repository.deleteProtocol(id)
    }

    fun cancelReminder(id: Long) {
        ReminderScheduler.cancelAlarm(getApplication(), id)
        container.repository.deleteReminder(id)
        JarvisHudWidgetProvider.requestUpdate(getApplication())
    }

    fun clearAuditLogs() {
        container.repository.clearAuditLogs()
    }
}
