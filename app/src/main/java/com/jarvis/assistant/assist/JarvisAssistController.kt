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
import com.jarvis.assistant.agent.ChatMessage
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.JarvisState
import com.jarvis.assistant.agent.MessageRole
import com.jarvis.assistant.agent.PendingConfirmation
import com.jarvis.assistant.voice.JarvisSpeechOptions
import com.jarvis.assistant.voice.StarkCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object VoiceCommandClassifier {
    private val dismissPhrases = setOf(
        "stand down", "dismiss", "never mind", "nevermind", "cancel", "close",
        "sleep", "go to sleep", "power down", "nothing", "forget it", "stop",
        "shut down", "abbrechen", "schließen", "nichts"
    )

    private val approveWords = setOf(
        "confirm", "confirmed", "yes", "yeah", "yep", "authorize", "authorized",
        "approve", "approved", "proceed", "go ahead", "do it", "ok", "okay",
        "sure", "ja", "bestätigen", "machen"
    )

    private val denyWords = setOf(
        "cancel", "no", "nope", "deny", "denied", "abort", "stop", "don't",
        "do not", "never mind", "nein", "abbrechen"
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
            .removePrefix("jarvis")
            .trim(',', '.', '!', '?', ' ')
}

class JarvisAssistController(
    private val context: Context,
    private val container: JarvisAppContainer,
    private val overlayView: StarkAssistOverlayView,
    private val onPilotPassThroughChanged: (Boolean) -> Unit,
    private val onDismissComplete: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var commandJob: Job? = null
    private var dismissed = false
    private var awaitingVoiceConfirm = false
    private var isFollowUpTurn = false

    private val passThroughTools = setOf(
        "open_app", "open_url", "web_search", "open_system_panel",
        "read_screen", "tap_element", "tap_coordinates", "type_text",
        "scroll_screen", "global_action", "make_call"
    )

    private val exitAfterTools = setOf(
        "open_app", "open_url", "web_search", "open_system_panel",
        "make_call", "global_action"
    )

    fun startSession() {
        dismissed = false
        awaitingVoiceConfirm = false
        isFollowUpTurn = false

        val settings = container.settingsStore.current()
        overlayView.configureTheme(settings.hudColorTheme)

        val tel = container.telemetryMonitor.refresh()
        val wattTag = if (tel.isCharging && tel.chargingWattsEstimate >= 1f) " ${"%.0f".format(tel.chargingWattsEstimate)}W" else ""
        overlayView.updateTelemetry("BATT ${tel.batteryPercent}%$wattTag • ${"%.0f".format(tel.batteryTempC)}°C")

        overlayView.onCoreTap = ::handleCoreTap
        overlayView.onBackdropTap = ::dismissGracefully

        triggerWakeHaptic()
        container.soundEffects.play(StarkCue.WAKE)
        overlayView.startWakeAnimation()

        bindReactiveFlows()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val msg = "Microphone access is required, ${settings.userTitle}. Open Jarvis settings to enable it."
            overlayView.updateState(JarvisState.ERROR, "Microphone needed", msg, "Tap anywhere to close")
            speakAndMaybeDismiss(msg, forceDismiss = true)
            return
        }

        beginVoiceListening(isFollowUp = false)
    }

    fun stopSession() {
        dismissed = true
        setPassThrough(false)
        commandJob?.cancel()
        container.orchestrator.abortCurrentTask()
        container.voiceEngine.stopListening()
        container.voiceEngine.stopSpeaking()
        overlayView.stopAnimation()
        scope.cancel()
    }

    private fun bindReactiveFlows() {
        scope.launch {
            container.voiceEngine.rmsLevel.collectLatest { rms ->
                overlayView.setVoiceRms(rms)
            }
        }
        scope.launch {
            container.voiceEngine.partialTranscript.collectLatest { partial ->
                if (partial.isNotBlank() && container.voiceEngine.isListening.value) {
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
                if (dismissed) return@collectLatest
                val detail = container.orchestrator.statusDetail.value
                when (st) {
                    JarvisState.THINKING -> {
                        overlayView.updateState(JarvisState.THINKING, "Thinking", "", "")
                    }

                    JarvisState.EXECUTING -> {
                        val needsPassThrough = passThroughTools.any { detail.contains(it, ignoreCase = true) }
                        if (needsPassThrough) setPassThrough(true)
                        val cleanAction = formatFriendlyStatus(detail)
                        overlayView.updateState(JarvisState.EXECUTING, "Piloting", "", cleanAction)
                    }

                    else -> Unit
                }
            }
        }
        scope.launch {
            container.orchestrator.pendingConfirmation.collectLatest { pending ->
                if (pending != null && !dismissed) {
                    handleVoiceConfirmationPrompt(pending)
                }
            }
        }
    }

    private fun beginVoiceListening(isFollowUp: Boolean) {
        if (dismissed) return
        isFollowUpTurn = isFollowUp
        awaitingVoiceConfirm = false
        setPassThrough(false)

        val title = container.settingsStore.current().userTitle
        overlayView.updateState(
            JarvisState.LISTENING,
            if (isFollowUp) "Listening" else "Listening",
            if (isFollowUp) "Anything else, $title?" else "",
            ""
        )

        container.voiceEngine.startListening(
            onFinalResult = { spoken ->
                if (dismissed) return@startListening
                if (VoiceCommandClassifier.isDismissCommand(spoken)) {
                    dismissGracefully()
                } else {
                    executeSpokenDirective(spoken)
                }
            },
            onError = { errMsg ->
                if (dismissed) return@startListening
                if (isFollowUp) {
                    dismissGracefully()
                } else {
                    overlayView.updateState(JarvisState.IDLE, "Tap orb to retry", errMsg, "")
                    scope.launch {
                        delay(1_800L)
                        if (!dismissed && !container.voiceEngine.isListening.value && commandJob?.isActive != true) {
                            dismissGracefully()
                        }
                    }
                }
            }
        )
    }

    private fun executeSpokenDirective(spoken: String) {
        commandJob?.cancel()
        overlayView.updateState(JarvisState.THINKING, "Thinking", spoken, "")
        container.repository.addMessage(ChatMessage(role = MessageRole.USER, content = spoken))

        commandJob = scope.launch {
            val history = container.repository.messages.value.dropLast(1)
            val outcome = container.orchestrator.processUserInput(spoken, history)
            if (dismissed) return@launch

            setPassThrough(false)
            container.repository.addMessage(
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = outcome.reply,
                    traces = outcome.traces
                )
            )

            val shouldExitImmediately = outcome.traces.any { it.toolName in exitAfterTools && it.success }
            if (outcome.aborted) {
                container.soundEffects.play(StarkCue.ABORT)
                dismissGracefully()
                return@launch
            }

            container.soundEffects.play(StarkCue.DONE)
            overlayView.updateState(JarvisState.SPEAKING, "Jarvis", outcome.reply, "")
            speakAndMaybeDismiss(outcome.reply, forceDismiss = shouldExitImmediately)
        }
    }

    private fun handleVoiceConfirmationPrompt(pending: PendingConfirmation) {
        awaitingVoiceConfirm = true
        setPassThrough(false)
        triggerWakeHaptic()

        val settings = container.settingsStore.current()
        overlayView.updateState(
            JarvisState.AWAITING_AUTH,
            "Confirmation",
            pending.detail,
            "Say confirm or cancel"
        )

        val promptSpeech = "Authorization required, ${settings.userTitle}. ${pending.detail}. Say confirm or cancel."
        container.voiceEngine.speak(
            text = promptSpeech,
            options = buildSpeechOptions(settings),
            onFinished = {
                if (dismissed || container.orchestrator.pendingConfirmation.value == null) return@speak
                container.soundEffects.play(StarkCue.WAKE)
                container.voiceEngine.startListening(
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

    private fun speakAndMaybeDismiss(text: String, forceDismiss: Boolean) {
        val settings = container.settingsStore.current()
        val afterSpeak = {
            if (!dismissed) {
                scope.launch {
                    if (!forceDismiss && settings.continuousConversation) {
                        delay(280L)
                        if (!dismissed) beginVoiceListening(isFollowUp = true)
                    } else if (forceDismiss || settings.autoDismissAfterReply) {
                        delay(520L)
                        if (!dismissed) dismissGracefully()
                    } else {
                        overlayView.updateState(JarvisState.IDLE, "STANDBY", text, "TAP CORE TO SPEAK")
                    }
                }
            }
        }

        if (settings.ttsEnabled) {
            container.voiceEngine.speak(
                text = text,
                options = buildSpeechOptions(settings),
                onFinished = afterSpeak
            )
        } else {
            scope.launch {
                delay(1_800L)
                afterSpeak()
            }
        }
    }

    private fun handleCoreTap() {
        if (dismissed) return
        triggerWakeHaptic()
        if (awaitingVoiceConfirm) {
            // Tapping core while awaiting voice auth cancels safely
            awaitingVoiceConfirm = false
            container.voiceEngine.stopListening()
            container.voiceEngine.stopSpeaking()
            container.orchestrator.resolveConfirmation(false)
            return
        }
        if (commandJob?.isActive == true) {
            container.orchestrator.abortCurrentTask()
            commandJob?.cancel()
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
            dismissGracefully()
        } else {
            beginVoiceListening(isFollowUp = false)
        }
    }

    fun dismissGracefully() {
        if (dismissed) return
        dismissed = true
        setPassThrough(false)
        commandJob?.cancel()
        container.orchestrator.abortCurrentTask()
        container.voiceEngine.stopListening()
        container.voiceEngine.stopSpeaking()
        container.soundEffects.play(StarkCue.ABORT)
        overlayView.playDismissAnimation {
            scope.cancel()
            onDismissComplete()
        }
    }

    private fun setPassThrough(active: Boolean) {
        overlayView.setPilotPassThrough(active)
        onPilotPassThroughChanged(active)
    }

    private fun buildSpeechOptions(s: JarvisSettings): JarvisSpeechOptions = JarvisSpeechOptions(
        geminiApiKey = s.geminiApiKey,
        neuralVoiceEnabled = s.neuralJarvisVoice,
        neuralVoiceName = s.voiceProfile.geminiVoiceName,
        intercomDspEnabled = s.intercomDspEnabled,
        speechRate = s.ttsSpeechRate,
        pitch = s.ttsPitch
    )

    private fun formatFriendlyStatus(rawDetail: String): String {
        val clean = rawDetail.substringAfter("•").trim().replace('_', ' ')
        return clean.replaceFirstChar { it.uppercase() }.ifBlank { "Working" }
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
            vibrator?.vibrate(VibrationEffect.createOneShot(26L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
