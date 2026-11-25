package com.jarvis.assistant

import android.app.Application
import android.content.Context
import com.jarvis.assistant.agent.AiProvider
import com.jarvis.assistant.agent.GeminiLlmClient
import com.jarvis.assistant.agent.JarvisOrchestrator
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.JarvisState
import com.jarvis.assistant.agent.LlmClient
import com.jarvis.assistant.agent.OpenAiCompatibleLlmClient
import com.jarvis.assistant.data.JarvisDatabaseHelper
import com.jarvis.assistant.data.JarvisRepository
import com.jarvis.assistant.data.SecureSettingsStore
import com.jarvis.assistant.device.DeviceTelemetryMonitor
import com.jarvis.assistant.device.InstalledAppsRepository
import com.jarvis.assistant.tools.ToolRegistry
import com.jarvis.assistant.voice.JarvisVoiceEngine
import com.jarvis.assistant.voice.StarkCue
import com.jarvis.assistant.voice.StarkSoundEffects

class JarvisAppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settingsStore = SecureSettingsStore(appContext)
    val dbHelper = JarvisDatabaseHelper(appContext)
    val repository = JarvisRepository(dbHelper)
    val telemetryMonitor = DeviceTelemetryMonitor(appContext)
    val installedAppsRepository = InstalledAppsRepository(appContext)
    val toolRegistry = ToolRegistry(appContext, installedAppsRepository, telemetryMonitor, repository)
    val soundEffects = StarkSoundEffects { settingsStore.current().soundEffectsEnabled }
    val voiceEngine = JarvisVoiceEngine(appContext)

    val orchestrator = JarvisOrchestrator(
        toolExecutor = toolRegistry,
        store = repository,
        settingsProvider = { settingsStore.current() },
        telemetrySummaryProvider = { telemetryMonitor.refresh().toCompactContextString() },
        llmClientFactory = ::createLlmClient,
        onStateFeedback = { state ->
            when (state) {
                JarvisState.EXECUTING -> soundEffects.play(StarkCue.EXECUTE)
                JarvisState.AWAITING_AUTH -> soundEffects.play(StarkCue.AUTH_ALERT)
                else -> Unit
            }
        }
    )

    private fun createLlmClient(settings: JarvisSettings): LlmClient? = when (settings.provider) {
        AiProvider.GEMINI -> {
            if (settings.geminiApiKey.isBlank()) null
            else GeminiLlmClient(
                apiKey = settings.geminiApiKey,
                model = settings.geminiModel
            )
        }

        AiProvider.OPENAI_COMPATIBLE -> {
            if (!settings.hasActiveCloudKey()) null
            else OpenAiCompatibleLlmClient(
                apiKey = settings.openAiApiKey,
                baseUrl = settings.openAiBaseUrl,
                model = settings.openAiModel
            )
        }

        AiProvider.LOCAL_ONLY -> null
    }
}

class JarvisApplication : Application() {
    lateinit var container: JarvisAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = JarvisAppContainer(this)
    }

    override fun onTerminate() {
        container.voiceEngine.shutdown()
        super.onTerminate()
    }
}
