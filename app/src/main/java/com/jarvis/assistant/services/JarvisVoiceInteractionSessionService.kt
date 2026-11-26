package com.jarvis.assistant.services

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.assist.JarvisAssistController
import com.jarvis.assistant.assist.StarkAssistOverlayView

class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisVoiceInteractionSession(this)
    }
}

/**
 * Native Android System Default Digital Assistant session.
 * Triggered when holding the Xiaomi 15T Pro Power Button.
 * When screen/app piloting begins, hides cleanly while JarvisTaskCoordinator completes
 * multi-step interactions on the unobstructed underlying app.
 */
class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private var overlayView: StarkAssistOverlayView? = null
    private var controller: JarvisAssistController? = null

    override fun onCreateContentView(): View {
        val view = StarkAssistOverlayView(context)
        overlayView = view
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        configureFullBleedWindow()

        val view = overlayView ?: return
        val app = context.applicationContext as? JarvisApplication ?: return

        controller?.detachOverlayOnly()
        controller = JarvisAssistController(
            context = context,
            container = app.container,
            overlayView = view,
            onDismissImmediate = {
                applyFrostedBlur(enabled = false)
                hide()
            },
            onDismissComplete = {
                applyFrostedBlur(enabled = false)
                hide()
            }
        )
        val initialCommand = args?.getString(JarvisVoiceInteractionService.EXTRA_INITIAL_COMMAND)
        val langHint = args?.getString(JarvisVoiceInteractionService.EXTRA_LANGUAGE_HINT)
        controller?.startSession(initialCommand = initialCommand, languageHint = langHint)
    }

    override fun onHide() {
        // Detach UI only — never kill an active multi-step screen pilot running in JarvisTaskCoordinator
        controller?.detachOverlayOnly()
        controller = null
        super.onHide()
    }

    override fun onDestroy() {
        controller?.detachOverlayOnly()
        controller = null
        overlayView = null
        super.onDestroy()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
        outInsets.contentInsets.set(0, 0, 0, 0)
    }

    private fun configureFullBleedWindow() {
        val w = window?.window ?: return
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.setBackgroundDrawableResource(android.R.color.transparent)
            @Suppress("DEPRECATION")
            w.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            applyFrostedBlur(enabled = true)
        }
    }

    private fun applyFrostedBlur(enabled: Boolean) {
        val w = window?.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                if (enabled) {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    w.attributes = w.attributes.apply { blurBehindRadius = 42 }
                } else {
                    w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    w.attributes = w.attributes.apply { blurBehindRadius = 0 }
                }
            }
        }
    }
}
