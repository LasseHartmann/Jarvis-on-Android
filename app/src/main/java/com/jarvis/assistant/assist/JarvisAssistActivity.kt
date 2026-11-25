package com.jarvis.assistant.assist

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.jarvis.assistant.JarvisApplication

/**
 * Pure voice-only translucent Stark HUD overlay activity.
 * Launched via Quick Settings Tile, ACTION_ASSIST / ACTION_VOICE_COMMAND, or Settings "Test Wake" button.
 */
class JarvisAssistActivity : ComponentActivity() {

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

        val overlayView = StarkAssistOverlayView(this)
        setContentView(overlayView)

        val container = (application as JarvisApplication).container
        controller = JarvisAssistController(
            context = this,
            container = container,
            overlayView = overlayView,
            onPilotPassThroughChanged = { passThrough ->
                runOnUiThread {
                    applyFrostedBlur(enabled = !passThrough)
                    if (passThrough) {
                        window.addFlags(
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        )
                    } else {
                        window.clearFlags(
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        )
                    }
                }
            },
            onDismissComplete = {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        )
        controller?.startSession()
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
        controller?.stopSession()
        controller = null
        super.onDestroy()
    }
}
