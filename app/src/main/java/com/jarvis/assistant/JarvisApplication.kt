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
import com.jarvis.assistant.assist.JarvisAmbientIndicator
import com.jarvis.assistant.assist.JarvisTaskCoordinator
import com.jarvis.assistant.data.JarvisDatabaseHelper
import com.jarvis.assistant.data.JarvisRepository
import com.jarvis.assistant.data.SecureSettingsStore
import com.jarvis.assistant.device.DeviceTelemetryMonitor
import com.jarvis.assistant.device.InstalledAppsRepository
import com.jarvis.assistant.services.ReminderScheduler
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
    val soundEffects = StarkSoundEffects { settingsStore.current().soundEffectsEnabled }
    val voiceEngine = JarvisVoiceEngine(appContext)

    lateinit var taskCoordinator: JarvisTaskCoordinator
        private set

    lateinit var ambientIndicator: JarvisAmbientIndicator
        private set

    val toolRegistry = ToolRegistry(
        context = appContext,
        appsRepo = installedAppsRepository,
        telemetryMonitor = telemetryMonitor,
        store = repository,
        settingsProvider = { settingsStore.current() },
        prepareScreenForPilot = {
            if (::taskCoordinator.isInitialized) {
                taskCoordinator.dismissOverlayForScreenPilot()
            }
        }
    )

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

    init {
        taskCoordinator = JarvisTaskCoordinator(
            orchestrator = orchestrator,
            repository = repository,
            settingsStore = settingsStore,
            voiceEngine = voiceEngine,
            soundEffects = soundEffects
        )
        ambientIndicator = JarvisAmbientIndicator(
            orchestrator = orchestrator,
            voiceEngine = voiceEngine,
            settingsStore = settingsStore,
            taskCoordinator = taskCoordinator
        )
    }

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
        runCatching {
            ReminderScheduler.rescheduleAllActive(this, container.repository)
        }
    }

    override fun onTerminate() {
        container.voiceEngine.shutdown()
        super.onTerminate()
    }
}
