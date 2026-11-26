package com.jarvis.assistant.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.agent.AuditLogEntry
import com.jarvis.assistant.agent.JarvisLanguageMode
import com.jarvis.assistant.agent.JarvisMemory
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.SpokenLanguage
import com.jarvis.assistant.device.DeviceTelemetry
import com.jarvis.assistant.services.JarvisWakeWordService
import com.jarvis.assistant.voice.JarvisSpeechOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as JarvisApplication).container

    val settings: StateFlow<JarvisSettings> = container.settingsStore.settings
    val telemetry: StateFlow<DeviceTelemetry> = container.telemetryMonitor.telemetry
    val memories: StateFlow<List<JarvisMemory>> = container.repository.memories
    val auditLogs: StateFlow<List<AuditLogEntry>> = container.repository.auditLogs

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
    }

    fun syncWakeWordService() {
        JarvisWakeWordService.syncWithSettings(getApplication(), container.settingsStore.current())
    }

    fun updateSettings(transform: (JarvisSettings) -> JarvisSettings) {
        container.settingsStore.update(transform)
        syncWakeWordService()
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

    fun clearAuditLogs() {
        container.repository.clearAuditLogs()
    }
}
