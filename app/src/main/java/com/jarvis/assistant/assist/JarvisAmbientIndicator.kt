package com.jarvis.assistant.assist

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import com.jarvis.assistant.agent.HudColorTheme
import com.jarvis.assistant.agent.JarvisOrchestrator
import com.jarvis.assistant.agent.JarvisState
import com.jarvis.assistant.data.SecureSettingsStore
import com.jarvis.assistant.voice.JarvisVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AmbientPillSnapshot(
    val visible: Boolean,
    val state: JarvisState = JarvisState.IDLE,
    val label: String = ""
)

object AmbientPillStateResolver {

    /**
     * Pure state-machine mapping live orchestrator/voice/overlay signals into the small
     * passive floating pill. Never appears while the full-screen wake overlay is visible.
     */
    fun resolve(
        overlayVisible: Boolean,
        orchestratorState: JarvisState,
        statusDetail: String,
        isListening: Boolean,
        isSpeakingHeadless: Boolean
    ): AmbientPillSnapshot {
        if (overlayVisible) {
            return AmbientPillSnapshot(visible = false)
        }

        return when {
            orchestratorState == JarvisState.AWAITING_AUTH ->
                AmbientPillSnapshot(true, JarvisState.AWAITING_AUTH, if (isListening) "Listening" else "Confirm?")

            isListening ->
                AmbientPillSnapshot(true, JarvisState.LISTENING, "Listening")

            orchestratorState == JarvisState.EXECUTING ->
                AmbientPillSnapshot(true, JarvisState.EXECUTING, humanizeActionLabel(statusDetail))

            orchestratorState == JarvisState.THINKING ->
                AmbientPillSnapshot(true, JarvisState.THINKING, "Thinking")

            isSpeakingHeadless ->
                AmbientPillSnapshot(true, JarvisState.SPEAKING, "Jarvis")

            else ->
                AmbientPillSnapshot(visible = false)
        }
    }

    fun humanizeActionLabel(statusDetail: String): String {
        val raw = statusDetail.substringAfter("•", statusDetail).trim()
        val lower = raw.lowercase()
        return when {
            lower.startsWith("opening ") || lower.startsWith("tapping ") || lower.startsWith("scrolling ") ->
                raw.take(20)
            lower.contains("analyze_screen_vision") -> "Inspecting screen"
            lower.contains("look_with_camera") -> "Using camera"
            lower.contains("live_web_answer") -> "Searching web"
            lower.contains("fetch_webpage") -> "Reading page"
            lower.contains("get_current_location") -> "Locating"
            lower.contains("get_weather") -> "Checking weather"
            lower.contains("send_whatsapp") -> "WhatsApp"
            lower.contains("play_music") -> "Starting music"
            lower.contains("control_ir_device") -> "IR blaster"
            lower.contains("smart_home") -> "Smart home"
            lower.contains("alexa") -> "Amazon Alexa"
            lower.contains("pc_bridge") -> "PC bridge"
            lower.contains("protocol") -> "Updating routine"
            lower.contains("reminder") -> "Scheduling"
            lower.contains("open_app") -> "Opening app"
            lower.contains("tap_element") || lower.contains("tap_coordinates") -> "Tapping"
            lower.contains("type_text") -> "Typing"
            lower.contains("scroll_screen") -> "Scrolling"
            lower.contains("read_screen") -> "Reading screen"
            lower.contains("global_action") -> "Navigating"
            lower.contains("navigate_to") -> "Starting Maps"
            lower.contains("open_url") || lower.contains("web_search") -> "Opening web"
            lower.contains("set_volume") || lower.contains("set_ringer") -> "Audio"
            lower.contains("set_brightness") -> "Brightness"
            lower.contains("set_flashlight") -> "Flashlight"
            lower.contains("set_timer") || lower.contains("set_alarm") -> "Scheduling"
            lower.contains("send_sms") || lower.contains("reply_notification") -> "Messaging"
            lower.contains("make_call") -> "Calling"
            lower.contains("media_control") -> "Media"
            lower.contains("reflex") -> "Working"
            lower.contains("authorized") -> "Executing"
            lower.isBlank() || lower.contains("neural") -> "Working"
            else -> lower.replace('_', ' ').replaceFirstChar { it.uppercase() }.take(18)
        }
    }
}

/**
 * Manages the floating non-touchable passive animation pill hosted by JarvisAccessibilityService
 * via TYPE_ACCESSIBILITY_OVERLAY (zero extra permissions, zero Accessibility window occlusion).
 */
class JarvisAmbientIndicator(
    private val orchestrator: JarvisOrchestrator,
    private val voiceEngine: JarvisVoiceEngine,
    private val settingsStore: SecureSettingsStore,
    private val taskCoordinator: JarvisTaskCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var hostContext: Context? = null
    private var windowManager: WindowManager? = null
    private var pillView: JarvisAmbientPillView? = null
    private var isAttachedToWindow = false
    private var collectJob: Job? = null
    private var rmsJob: Job? = null

    // Debug/preview override (null in normal operation)
    private val previewOverride = MutableStateFlow<AmbientPillSnapshot?>(null)

    private data class CoreInputs(
        val overlayVisible: Boolean,
        val state: JarvisState,
        val detail: String,
        val isListening: Boolean,
        val isSpeakingHeadless: Boolean
    )

    fun attachHost(serviceContext: Context) {
        scope.launch {
            detachHostInternal()
            hostContext = serviceContext
            windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            startObserving()
        }
    }

    fun detachHost(serviceContext: Context) {
        scope.launch {
            if (hostContext === serviceContext) {
                detachHostInternal()
            }
        }
    }

    fun setPreviewOverride(snapshot: AmbientPillSnapshot?) {
        previewOverride.value = snapshot
    }

    private fun startObserving() {
        collectJob?.cancel()
        rmsJob?.cancel()

        val coreFlow = combine(
            taskCoordinator.overlayVisible,
            orchestrator.state,
            orchestrator.statusDetail,
            voiceEngine.isListening,
            taskCoordinator.headlessSpeechActive
        ) { overlayVis, orchState, detail, listening, headlessSpeak ->
            CoreInputs(overlayVis, orchState, detail, listening, headlessSpeak)
        }

        collectJob = scope.launch {
            combine(
                coreFlow,
                voiceEngine.isSpeaking,
                settingsStore.settings,
                previewOverride
            ) { core, speakingNow, settings, override ->
                val resolved = override ?: AmbientPillStateResolver.resolve(
                    overlayVisible = core.overlayVisible,
                    orchestratorState = core.state,
                    statusDetail = core.detail,
                    isListening = core.isListening,
                    isSpeakingHeadless = core.isSpeakingHeadless && speakingNow
                )
                resolved to settings.hudColorTheme
            }.collectLatest { (snapshot, theme) ->
                applySnapshot(snapshot, theme)
            }
        }

        rmsJob = scope.launch {
            voiceEngine.rmsLevel.collectLatest { rms ->
                pillView?.setVoiceRms(rms)
            }
        }
    }

    private fun applySnapshot(snapshot: AmbientPillSnapshot, theme: HudColorTheme) {
        if (snapshot.visible) {
            val view = ensureAttachedView() ?: return
            view.showState(snapshot.state, snapshot.label, theme)
        } else {
            val view = pillView ?: return
            if (isAttachedToWindow) {
                view.hideGracefully {
                    removeViewFromWindow(view)
                }
            }
        }
    }

    private fun ensureAttachedView(): JarvisAmbientPillView? {
        val ctx = hostContext ?: return null
        val wm = windowManager ?: return null

        val existing = pillView
        if (existing != null && isAttachedToWindow) return existing

        val view = existing ?: JarvisAmbientPillView(ctx).also { pillView = it }
        val params = buildLayoutParams(ctx, wm)
        return runCatching {
            wm.addView(view, params)
            isAttachedToWindow = true
            view
        }.onFailure { err ->
            Log.w(TAG, "Unable to attach passive ambient pill: ${err.message}")
        }.getOrNull()
    }

    private fun removeViewFromWindow(view: JarvisAmbientPillView) {
        if (!isAttachedToWindow) return
        val wm = windowManager
        runCatching {
            wm?.removeViewImmediate(view)
        }
        isAttachedToWindow = false
    }

    private fun detachHostInternal() {
        collectJob?.cancel()
        collectJob = null
        rmsJob?.cancel()
        rmsJob = null
        pillView?.let { v ->
            v.stopImmediately()
            removeViewFromWindow(v)
        }
        pillView = null
        windowManager = null
        hostContext = null
    }

    private fun buildLayoutParams(ctx: Context, wm: WindowManager): WindowManager.LayoutParams {
        val density = ctx.resources.displayMetrics.density
        val topInsetPx = resolveTopSafeInsetPx(ctx, wm, density)

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topInsetPx + (6f * density).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "JarvisAmbientPill"
        }
    }

    private fun resolveTopSafeInsetPx(ctx: Context, wm: WindowManager, density: Float): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = runCatching { wm.currentWindowMetrics }.getOrNull()
            if (metrics != null) {
                val insets = metrics.windowInsets
                val cutoutTop = insets.displayCutout?.safeInsetTop ?: 0
                val statusTop = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
                val top = maxOf(cutoutTop, statusTop)
                if (top > 0) return top
            }
        }
        val resId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val fallback = if (resId > 0) ctx.resources.getDimensionPixelSize(resId) else (36f * density).toInt()
        return fallback
    }

    companion object {
        private const val TAG = "JarvisAmbientPill"
    }
}
