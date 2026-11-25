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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val _headlessSpeechActive = MutableStateFlow(false)
    val headlessSpeechActive: StateFlow<Boolean> = _headlessSpeechActive.asStateFlow()

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
        _overlayVisible.value = true
        _headlessSpeechActive.value = false
    }

    fun detachOverlay(handle: OverlaySessionHandle) {
        if (activeOverlay === handle) {
            activeOverlay = null
            _overlayVisible.value = false
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
        if (!handle.isOverlayVisible()) {
            _overlayVisible.value = false
            return
        }
        Log.i(TAG, "Dismissing Jarvis overlay cleanly so underlying app is unobstructed for Accessibility pilot")
        withContext(Dispatchers.Main.immediate) {
            handle.dismissForScreenPilot()
        }
        // Detach immediately and allow WindowManager to recompute interactive A11yWindow regions
        if (activeOverlay === handle) {
            activeOverlay = null
        }
        _overlayVisible.value = false
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
                    _headlessSpeechActive.value = true
                    voiceEngine.speak(
                        text = outcome.reply,
                        options = buildSpeechOptions(settings),
                        onFinished = { _headlessSpeechActive.value = false }
                    )
                }
            }
        }
    }

    fun abortActiveTask() {
        _headlessSpeechActive.value = false
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
        // Headless voice-only confirmation while piloting an app underneath — instant on-device voice for zero cloud pause
        val settings = settingsStore.current()
        val prompt = buildConfirmationPrompt(pending, settings.userTitle)
        voiceEngine.speak(
            text = prompt,
            options = buildSpeechOptions(settings, instantLocal = true),
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

    fun buildConfirmationPrompt(pending: PendingConfirmation, userTitle: String): String =
        formatConfirmationPrompt(pending.detail, userTitle)

    fun buildSpeechOptions(s: JarvisSettings, instantLocal: Boolean = false): JarvisSpeechOptions = JarvisSpeechOptions(
        geminiApiKey = s.geminiApiKey,
        neuralVoiceEnabled = s.neuralJarvisVoice,
        neuralVoiceName = s.voiceProfile.geminiVoiceName,
        intercomDspEnabled = s.intercomDspEnabled,
        speechRate = s.ttsSpeechRate,
        pitch = s.ttsPitch,
        preferInstantLocal = instantLocal
    )

    companion object {
        private const val TAG = "JarvisCoordinator"

        fun formatConfirmationPrompt(rawDetail: String, userTitle: String): String {
            val detail = rawDetail.trim()
            val phrase = when {
                detail.startsWith("Tap '") && detail.endsWith("'") ->
                    "tapping ${detail.removePrefix("Tap '").removeSuffix("'")}"
                detail.startsWith("Tap ") ->
                    "tapping ${detail.removePrefix("Tap ")}"
                detail.startsWith("Dial ") ->
                    "calling ${detail.removePrefix("Dial ")}"
                detail.startsWith("To ") ->
                    "sending that message"
                detail.startsWith("Reply:") ->
                    "sending that reply"
                detail.startsWith("Enable ") ->
                    "enabling ${detail.removePrefix("Enable ")}"
                detail.startsWith("Disable ") ->
                    "disabling ${detail.removePrefix("Disable ")}"
                detail.isNotBlank() ->
                    detail.replaceFirstChar { it.lowercase() }
                else ->
                    "this action"
            }
            return "Confirm $phrase, $userTitle?"
        }
    }
}
