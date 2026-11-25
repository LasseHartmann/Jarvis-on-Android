package com.jarvis.assistant.assist

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.jarvis.assistant.JarvisApplication

/**
 * Pure voice-only translucent minimalist wake overlay activity.
 * Launched via Quick Settings Tile, ACTION_ASSIST / ACTION_VOICE_COMMAND, or Settings "Preview wake".
 */
class JarvisAssistActivity : ComponentActivity() {

    private var overlayView: StarkAssistOverlayView? = null
    private var controller: JarvisAssistController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyFrostedBlur(enabled = true)

        val view = StarkAssistOverlayView(this)
        overlayView = view
        setContentView(view)

        startNewSession(view)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        overlayView?.let { view ->
            controller?.detachOverlayOnly()
            applyFrostedBlur(enabled = true)
            startNewSession(view)
        }
    }

    private fun startNewSession(view: StarkAssistOverlayView) {
        val container = (application as JarvisApplication).container
        val ctrl = JarvisAssistController(
            context = this,
            container = container,
            overlayView = view,
            onDismissImmediate = {
                applyFrostedBlur(enabled = false)
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            },
            onDismissComplete = {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        )
        controller = ctrl
        ctrl.startSession()
    }

    private fun applyFrostedBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply { blurBehindRadius = 42 }
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply { blurBehindRadius = 0 }
                }
            }
        }
    }

    override fun onDestroy() {
        controller?.detachOverlayOnly()
        controller = null
        super.onDestroy()
    }
}
