package com.jarvis.assistant.assist

import android.util.Log
import com.jarvis.assistant.agent.ChatMessage
import com.jarvis.assistant.agent.JarvisOrchestrator
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.JarvisState
import com.jarvis.assistant.agent.MessageRole
import com.jarvis.assistant.agent.PendingConfirmation
import com.jarvis.assistant.data.JarvisRepository
import com.jarvis.assistant.data.SecureSettingsStore
import com.jarvis.assistant.voice.JarvisSpeechOptions
import com.jarvis.assistant.voice.JarvisVoiceEngine
import com.jarvis.assistant.voice.StarkCue
import com.jarvis.assistant.voice.StarkSoundEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface OverlaySessionHandle {
    fun isOverlayVisible(): Boolean
    fun dismissForScreenPilot()
    fun onDirectiveReplyReady(reply: String, shouldExitImmediately: Boolean)
    fun onDirectiveAborted()
    fun onVoiceConfirmationPrompt(pending: PendingConfirmation)
}

/**
 * Application-scoped voice directive coordinator.
 * Survives hiding the VoiceInteractionSession / AssistActivity overlay so multi-step
 * Accessibility phone piloting (e.g. "Open WhatsApp and open the chat with Thomas")
 * runs cleanly with zero overlay occlusion, then speaks the final reply aloud.
 */
class JarvisTaskCoordinator(
    private val orchestrator: JarvisOrchestrator,
    private val repository: JarvisRepository,
    private val settingsStore: SecureSettingsStore,
    private val voiceEngine: JarvisVoiceEngine,
    private val soundEffects: StarkSoundEffects
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var activeOverlay: OverlaySessionHandle? = null

    @Volatile
    private var activeTaskJob: Job? = null

    private val exitAfterTools = setOf(
        "open_app", "open_url", "web_search", "open_system_panel",
        "make_call", "global_action", "tap_element", "tap_coordinates", "type_text", "scroll_screen"
    )

    init {
        appScope.launch {
            orchestrator.pendingConfirmation.collectLatest { pending ->
                if (pending != null) {
                    handleConfirmationRequest(pending)
                }
            }
        }
    }

    fun attachOverlay(handle: OverlaySessionHandle) {
        activeOverlay = handle
    }

    fun detachOverlay(handle: OverlaySessionHandle) {
        if (activeOverlay === handle) {
            activeOverlay = null
        }
    }

    fun isTaskActive(): Boolean = activeTaskJob?.isActive == true

    /**
     * Called by screen/app tools before inspecting or tapping the phone.
     * Hides the full-screen Jarvis overlay without cancelling the running task,
     * giving Android WindowManager time to restore the target app to interactive accessibility windows.
     */
    suspend fun dismissOverlayForScreenPilot() {
        val handle = activeOverlay ?: return
        if (!handle.isOverlayVisible()) return
        Log.i(TAG, "Dismissing Jarvis overlay cleanly so underlying app is unobstructed for Accessibility pilot")
        withContext(Dispatchers.Main.immediate) {
            handle.dismissForScreenPilot()
        }
        // Detach immediately and allow WindowManager to recompute interactive A11yWindow regions
        if (activeOverlay === handle) {
            activeOverlay = null
        }
        delay(340L)
    }

    fun submitSpokenDirective(spoken: String) {
        activeTaskJob?.cancel()
        repository.addMessage(ChatMessage(role = MessageRole.USER, content = spoken))

        activeTaskJob = appScope.launch {
            val history = repository.messages.value.dropLast(1)
            val outcome = orchestrator.processUserInput(spoken, history)

            repository.addMessage(
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = outcome.reply,
                    traces = outcome.traces
                )
            )

            val usedScreenOrApp = outcome.traces.any { it.toolName in exitAfterTools && it.success }
            val overlay = activeOverlay

            if (outcome.aborted) {
                soundEffects.play(StarkCue.ABORT)
                overlay?.onDirectiveAborted()
                return@launch
            }

            soundEffects.play(StarkCue.DONE)
            if (overlay != null && overlay.isOverlayVisible()) {
                overlay.onDirectiveReplyReady(outcome.reply, shouldExitImmediately = usedScreenOrApp)
            } else {
                // Overlay already hid for screen piloting — speak the final J.A.R.V.I.S. confirmation cleanly over the target app!
                val settings = settingsStore.current()
                if (settings.ttsEnabled) {
                    voiceEngine.speak(
                        text = outcome.reply,
                        options = buildSpeechOptions(settings)
                    )
                }
            }
        }
    }

    fun abortActiveTask() {
        orchestrator.abortCurrentTask()
        activeTaskJob?.cancel()
        activeTaskJob = null
    }

    private fun handleConfirmationRequest(pending: PendingConfirmation) {
        val overlay = activeOverlay
        if (overlay != null && overlay.isOverlayVisible()) {
            overlay.onVoiceConfirmationPrompt(pending)
            return
        }
        // Headless voice-only confirmation while piloting an app underneath!
        val settings = settingsStore.current()
        val prompt = "Authorization required, ${settings.userTitle}. ${pending.detail}. Say confirm or cancel."
        voiceEngine.speak(
            text = prompt,
            options = buildSpeechOptions(settings),
            onFinished = {
                if (orchestrator.pendingConfirmation.value == null) return@speak
                soundEffects.play(StarkCue.WAKE)
                voiceEngine.startListening(
                    onFinalResult = { spoken ->
                        val approved = VoiceCommandClassifier.parseConfirmation(spoken) ?: false
                        orchestrator.resolveConfirmation(approved)
                    },
                    onError = {
                        orchestrator.resolveConfirmation(false)
                    }
                )
            }
        )
    }

    fun buildSpeechOptions(s: JarvisSettings): JarvisSpeechOptions = JarvisSpeechOptions(
        geminiApiKey = s.geminiApiKey,
        neuralVoiceEnabled = s.neuralJarvisVoice,
        neuralVoiceName = s.voiceProfile.geminiVoiceName,
        intercomDspEnabled = s.intercomDspEnabled,
        speechRate = s.ttsSpeechRate,
        pitch = s.ttsPitch
    )

    companion object {
        private const val TAG = "JarvisCoordinator"
    }
}
