package com.jarvis.assistant.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.agent.AuditLogEntry
import com.jarvis.assistant.agent.JarvisMemory
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.device.DeviceTelemetry
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
    }

    fun updateSettings(transform: (JarvisSettings) -> JarvisSettings) {
        container.settingsStore.update(transform)
    }

    fun testVoiceSample() {
        val s = container.settingsStore.current()
        container.voiceEngine.speak(
            text = "J.A.R.V.I.S. online and at your service, ${s.userTitle}. All Xiaomi 15T Pro systems are operational.",
            options = JarvisSpeechOptions(
                geminiApiKey = s.geminiApiKey,
                neuralVoiceEnabled = s.neuralJarvisVoice,
                neuralVoiceName = s.voiceProfile.geminiVoiceName,
                intercomDspEnabled = s.intercomDspEnabled,
                speechRate = s.ttsSpeechRate,
                pitch = s.ttsPitch
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
