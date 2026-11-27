package com.jarvis.assistant.assist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisAppContainer
import com.jarvis.assistant.agent.JarvisState
import com.jarvis.assistant.agent.PendingConfirmation
import com.jarvis.assistant.agent.SpokenLanguage
import com.jarvis.assistant.voice.StarkCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object VoiceCommandClassifier {
    private val dismissPhrases = setOf(
        "stand down", "dismiss", "never mind", "nevermind", "cancel", "close",
        "sleep", "go to sleep", "power down", "nothing", "forget it", "stop",
        "shut down", "abbrechen", "schließen", "schliessen", "nichts",
        "vergiss es", "lass es", "stopp", "schlafen", "ruhe"
    )

    private val approveWords = setOf(
        "confirm", "confirmed", "yes", "yeah", "yep", "authorize", "authorized",
        "approve", "approved", "proceed", "go ahead", "do it", "ok", "okay",
        "sure", "ja", "bestätigen", "bestaetigen", "bestätige", "machen",
        "mach es", "mach weiter", "los", "genau", "alles klar", "einverstanden"
    )

    private val denyWords = setOf(
        "cancel", "no", "nope", "deny", "denied", "abort", "stop", "don't",
        "do not", "never mind", "nein", "abbrechen", "stopp", "nicht", "lass es"
    )

    fun isDismissCommand(spoken: String): Boolean {
        val norm = normalize(spoken)
        return norm in dismissPhrases
    }

    /** Returns true for approve, false for deny, null if ambiguous. */
    fun parseConfirmation(spoken: String): Boolean? {
        val norm = normalize(spoken)
        if (norm.isBlank()) return null
        if (denyWords.any { norm == it || norm.startsWith("$it ") || norm.contains(" $it") }) return false
        if (approveWords.any { norm == it || norm.startsWith("$it ") || norm.contains(" $it") }) return true
        return null
    }

    private fun normalize(raw: String): String =
        raw.trim().lowercase()
            .removePrefix("hey jarvis")
            .removePrefix("ok jarvis")
            .removePrefix("okay jarvis")
            .removePrefix("hallo jarvis")
            .removePrefix("jarvis")
            .trim(',', '.', '!', '?', ' ')
}

class JarvisAssistController(
    private val context: Context,
    private val container: JarvisAppContainer,
    private val overlayView: StarkAssistOverlayView,
    private val onDismissImmediate: () -> Unit,
    private val onDismissComplete: () -> Unit
) : OverlaySessionHandle {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var visible = false
    private var awaitingVoiceConfirm = false
    private var isFollowUpTurn = false

    fun startSession(initialCommand: String? = null, languageHint: String? = null) {
        visible = true
        awaitingVoiceConfirm = false
        isFollowUpTurn = false

        container.taskCoordinator.attachOverlay(this)

        val settings = container.settingsStore.current()
        overlayView.configureTheme(settings.hudColorTheme)
        overlayView.onCoreTap = ::handleCoreTap
        overlayView.onBackdropTap = ::dismissAndAbort

        triggerWakeHaptic()
        container.soundEffects.play(StarkCue.WAKE)
        overlayView.startWakeAnimation()

        bindReactiveFlows()

        if (!initialCommand.isNullOrBlank()) {
            overlayView.updateState(JarvisState.THINKING, "Thinking", initialCommand, "")
            container.taskCoordinator.submitSpokenDirective(initialCommand, languageHint)
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val msg = "Microphone access is required, ${settings.userTitle}. Open Jarvis settings to enable it."
            overlayView.updateState(JarvisState.ERROR, "Microphone needed", msg, "Tap anywhere to close")
            speakAndMaybeDismiss(msg, forceDismiss = true, replyLanguage = SpokenLanguage.ENGLISH)
            return
        }

        beginVoiceListening(isFollowUp = false)
    }

    /**
     * Detaches overlay UI cleanly when the window hides (e.g. during screen piloting)
     * WITHOUT aborting an active multi-step phone control task in JarvisTaskCoordinator.
     */
    fun detachOverlayOnly() {
        visible = false
        container.taskCoordinator.detachOverlay(this)
        if (!container.taskCoordinator.isTaskActive()) {
            container.voiceEngine.stopListening()
        }
        overlayView.stopAnimation()
        scope.cancel()
    }

    override fun isOverlayVisible(): Boolean = visible

    override fun dismissForScreenPilot() {
        if (!visible) return
        visible = false
        container.taskCoordinator.detachOverlay(this)
        overlayView.stopAnimation()
        scope.cancel()
        onDismissImmediate()
    }

    override fun onDirectiveStreamingCaption(text: String, audioStarted: Boolean) {
        if (!visible) return
        val st = if (audioStarted) JarvisState.SPEAKING else JarvisState.THINKING
        val tag = if (audioStarted) "Jarvis" else "Thinking"
        overlayView.updateState(st, tag, text, "")
    }

    override fun onStreamingSpeechComplete(finalReply: String, shouldExitImmediately: Boolean) {
        if (!visible) return
        val settings = container.settingsStore.current()
        scope.launch {
            if (!shouldExitImmediately && settings.continuousConversation) {
                delay(280L)
                if (visible) beginVoiceListening(isFollowUp = true)
            } else if (shouldExitImmediately || settings.autoDismissAfterReply) {
                delay(450L)
                if (visible) dismissOverlayGracefully()
            } else {
                overlayView.updateState(JarvisState.IDLE, "Standby", finalReply, "Tap orb to speak")
            }
        }
    }

    override fun onDirectiveReplyReady(
        reply: String,
        shouldExitImmediately: Boolean,
        replyLanguage: SpokenLanguage
    ) {
        if (!visible) return
        overlayView.updateState(JarvisState.SPEAKING, "Jarvis", reply, "")
        speakAndMaybeDismiss(reply, forceDismiss = shouldExitImmediately, replyLanguage = replyLanguage)
    }

    override fun onDirectiveAborted() {
        if (!visible) return
        dismissOverlayGracefully()
    }

    override fun onVoiceConfirmationPrompt(pending: PendingConfirmation, language: SpokenLanguage) {
        if (!visible) return
        awaitingVoiceConfirm = true
        triggerWakeHaptic()

        val settings = container.settingsStore.current()
        val hintText = if (language == SpokenLanguage.GERMAN) "Sag Ja oder Abbrechen" else "Say confirm or cancel"
        overlayView.updateState(
            JarvisState.AWAITING_AUTH,
            if (language == SpokenLanguage.GERMAN) "Bestätigung" else "Confirmation",
            pending.detail,
            hintText
        )

        val promptSpeech = container.taskCoordinator.buildConfirmationPrompt(pending, settings.userTitle, language)
        container.voiceEngine.speak(
            text = promptSpeech,
            options = container.taskCoordinator.buildSpeechOptions(settings, instantLocal = false, language = language),
            onFinished = {
                if (!visible || container.orchestrator.pendingConfirmation.value == null) return@speak
                container.soundEffects.play(StarkCue.WAKE)
                container.voiceEngine.startListening(
                    languageMode = settings.languageMode,
                    onFinalResult = { spoken ->
                        awaitingVoiceConfirm = false
                        val decision = VoiceCommandClassifier.parseConfirmation(spoken) ?: false
                        container.orchestrator.resolveConfirmation(decision)
                    },
                    onError = {
                        awaitingVoiceConfirm = false
                        container.orchestrator.resolveConfirmation(false)
                    }
                )
            }
        )
    }

    private fun bindReactiveFlows() {
        scope.launch {
            container.voiceEngine.rmsLevel.collectLatest { rms ->
                if (visible) overlayView.setVoiceRms(rms)
            }
        }
        scope.launch {
            container.voiceEngine.partialTranscript.collectLatest { partial ->
                if (visible && partial.isNotBlank() && container.voiceEngine.isListening.value) {
                    val sub = if (awaitingVoiceConfirm) "Say confirm or cancel" else ""
                    overlayView.updateState(
                        if (awaitingVoiceConfirm) JarvisState.AWAITING_AUTH else JarvisState.LISTENING,
                        if (awaitingVoiceConfirm) "Confirmation" else "Listening",
                        partial,
                        sub
                    )
                }
            }
        }
        scope.launch {
            container.orchestrator.state.collectLatest { st ->
                if (!visible) return@collectLatest
                val detail = container.orchestrator.statusDetail.value
                when (st) {
                    JarvisState.THINKING -> {
                        overlayView.updateState(JarvisState.THINKING, "Thinking", "", "")
                    }

                    JarvisState.EXECUTING -> {
                        val cleanAction = detail.substringAfter("•").trim().replace('_', ' ')
                            .replaceFirstChar { it.uppercase() }.ifBlank { "Working" }
                        overlayView.updateState(JarvisState.EXECUTING, "Working", "", cleanAction)
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun beginVoiceListening(isFollowUp: Boolean) {
        if (!visible) return
        isFollowUpTurn = isFollowUp
        awaitingVoiceConfirm = false

        val settings = container.settingsStore.current()
        val title = settings.userTitle
        val deFollowUp = container.taskCoordinator.lastSpokenLanguage == SpokenLanguage.GERMAN
        overlayView.updateState(
            JarvisState.LISTENING,
            "Listening",
            if (isFollowUp) {
                if (deFollowUp) "Noch etwas, $title?" else "Anything else, $title?"
            } else "",
            ""
        )

        container.voiceEngine.startListening(
            languageMode = settings.languageMode,
            onFinalResult = { spoken ->
                if (!visible) return@startListening
                if (VoiceCommandClassifier.isDismissCommand(spoken)) {
                    dismissAndAbort()
                } else {
                    overlayView.updateState(JarvisState.THINKING, "Thinking", spoken, "")
                    container.taskCoordinator.submitSpokenDirective(
                        spoken = spoken,
                        detectedLanguageHint = container.voiceEngine.lastDetectedLanguageTag
                    )
                }
            },
            onError = { errMsg ->
                if (!visible) return@startListening
                if (isFollowUp) {
                    dismissOverlayGracefully()
                } else {
                    overlayView.updateState(JarvisState.IDLE, "Tap orb to retry", errMsg, "")
                    scope.launch {
                        delay(1_800L)
                        if (visible && !container.voiceEngine.isListening.value && !container.taskCoordinator.isTaskActive()) {
                            dismissOverlayGracefully()
                        }
                    }
                }
            }
        )
    }

    private fun speakAndMaybeDismiss(
        text: String,
        forceDismiss: Boolean,
        replyLanguage: SpokenLanguage
    ) {
        val settings = container.settingsStore.current()
        val afterSpeak = {
            if (visible) {
                scope.launch {
                    if (!forceDismiss && settings.continuousConversation) {
                        delay(280L)
                        if (visible) beginVoiceListening(isFollowUp = true)
                    } else if (forceDismiss || settings.autoDismissAfterReply) {
                        delay(450L)
                        if (visible) dismissOverlayGracefully()
                    } else {
                        overlayView.updateState(JarvisState.IDLE, "Standby", text, "Tap orb to speak")
                    }
                }
            }
        }

        if (settings.ttsEnabled) {
            container.voiceEngine.speak(
                text = text,
                options = container.taskCoordinator.buildSpeechOptions(settings, language = replyLanguage),
                onFinished = afterSpeak
            )
        } else {
            scope.launch {
                delay(1_600L)
                afterSpeak()
            }
        }
    }

    private fun handleCoreTap() {
        if (!visible) return
        triggerWakeHaptic()
        if (awaitingVoiceConfirm) {
            awaitingVoiceConfirm = false
            container.voiceEngine.stopListening()
            container.voiceEngine.stopSpeaking()
            container.orchestrator.resolveConfirmation(false)
            return
        }
        if (container.taskCoordinator.isTaskActive()) {
            container.taskCoordinator.abortActiveTask()
            container.soundEffects.play(StarkCue.ABORT)
            beginVoiceListening(isFollowUp = false)
            return
        }
        if (container.voiceEngine.isSpeaking.value) {
            container.voiceEngine.stopSpeaking()
            beginVoiceListening(isFollowUp = false)
            return
        }
        if (container.voiceEngine.isListening.value) {
            container.voiceEngine.stopListening()
            dismissOverlayGracefully()
        } else {
            beginVoiceListening(isFollowUp = false)
        }
    }

    fun dismissAndAbort() {
        container.taskCoordinator.abortActiveTask()
        container.voiceEngine.stopListening()
        container.voiceEngine.stopSpeaking()
        dismissOverlayGracefully()
    }

    private fun dismissOverlayGracefully() {
        if (!visible) return
        visible = false
        container.taskCoordinator.detachOverlay(this)
        container.soundEffects.play(StarkCue.ABORT)
        overlayView.playDismissAnimation {
            scope.cancel()
            onDismissComplete()
        }
    }

    private fun triggerWakeHaptic() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(24L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
