package com.jarvis.assistant

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.jarvis.assistant.ui.screens.JarvisSettingsScreen
import com.jarvis.assistant.ui.theme.JarvisPalette
import com.jarvis.assistant.ui.theme.StarkTheme
import com.jarvis.assistant.ui.viewmodel.SettingsViewModel

/**
 * Minimalist Jarvis Settings App.
 * The assistant itself is 100% voice-controlled via Default Digital Assistant wake overlay.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        setContent {
            val settings by viewModel.settings.collectAsState()
            val telemetry by viewModel.telemetry.collectAsState()
            val memories by viewModel.memories.collectAsState()
            val auditLogs by viewModel.auditLogs.collectAsState()
            val protocols by viewModel.protocols.collectAsState()
            val reminders by viewModel.reminders.collectAsState()
            val voiceprintUi by viewModel.voiceprintUi.collectAsState()

            StarkTheme(finish = settings.hudColorTheme) {
                Scaffold(
                    containerColor = JarvisPalette.Obsidian,
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    JarvisSettingsScreen(
                        settings = settings,
                        telemetry = telemetry,
                        memories = memories,
                        auditLogs = auditLogs,
                        protocols = protocols,
                        reminders = reminders,
                        voiceprintUi = voiceprintUi,
                        onUpdateSettings = viewModel::updateSettings,
                        onTestVoice = viewModel::testVoiceSample,
                        onPermissionsUpdated = viewModel::refreshTelemetry,
                        onRecordVoiceprintSample = viewModel::recordVoiceprintSample,
                        onTestVoiceprint = viewModel::testVoiceprintMatch,
                        onClearVoiceprints = viewModel::clearVoiceprints,
                        onDeleteMemory = viewModel::deleteMemory,
                        onDeleteProtocol = viewModel::deleteProtocol,
                        onCancelReminder = viewModel::cancelReminder,
                        onClearAuditLogs = viewModel::clearAuditLogs,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshTelemetry()
    }
}
