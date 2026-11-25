package com.jarvis.assistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.agent.JarvisState
import com.jarvis.assistant.assist.AmbientPillSnapshot
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.ScreenTreeSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only ADB test harness (never packaged into release APKs).
 * Usage:
 *   adb shell am broadcast -a com.jarvis.assistant.DEBUG_PILOT --es action read_screen
 *   adb shell am broadcast -a com.jarvis.assistant.DEBUG_PILOT --es action tap --es query "Display"
 *   adb shell am broadcast -a com.jarvis.assistant.DEBUG_PILOT --es action tap_xy --ef x 0.5 --ef y 0.5
 */
class DebugPilotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val action = intent.getStringExtra("action").orEmpty()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val svc = JarvisAccessibilityService.instance
                if (svc == null) {
                    Log.w(TAG, "RESULT: AccessibilityService offline")
                    return@launch
                }
                when (action) {
                    "read_screen" -> {
                        val snap = svc.captureScreenSnapshot()
                        if (snap == null) {
                            Log.w(TAG, "RESULT: read_screen returned null")
                        } else {
                            Log.i(TAG, "RESULT_READ_SCREEN:\n${ScreenTreeSerializer.formatForLlm(snap, maxNodes = 30)}")
                        }
                    }

                    "tap" -> {
                        val query = intent.getStringExtra("query")
                        val nodeId = if (intent.hasExtra("node_id")) intent.getIntExtra("node_id", -1).takeIf { it > 0 } else null
                        val (ok, msg) = svc.tapElement(nodeId, query)
                        Log.i(TAG, "RESULT_TAP: ok=$ok msg=$msg")
                    }

                    "tap_xy" -> {
                        val x = intent.getFloatExtra("x", 0.5f)
                        val y = intent.getFloatExtra("y", 0.5f)
                        val (ok, msg) = svc.tapNormalizedCoordinates(x, y, false)
                        Log.i(TAG, "RESULT_TAP_XY: ok=$ok msg=$msg")
                    }

                    "scroll" -> {
                        val dir = intent.getStringExtra("dir") ?: "down"
                        val (ok, msg) = svc.scrollScreen(dir)
                        Log.i(TAG, "RESULT_SCROLL: ok=$ok msg=$msg")
                    }

                    "dismiss_and_read" -> {
                        val app = context.applicationContext as? JarvisApplication
                        app?.container?.taskCoordinator?.dismissOverlayForScreenPilot()
                        val snap = svc.captureScreenSnapshot()
                        if (snap == null) {
                            Log.w(TAG, "RESULT_DISMISS_READ: returned null")
                        } else {
                            Log.i(TAG, "RESULT_DISMISS_READ: pkg=${snap.packageName} nodes=${snap.nodes.size}\n${ScreenTreeSerializer.formatForLlm(snap, maxNodes = 15)}")
                        }
                    }

                    "preview_pill" -> {
                        val app = context.applicationContext as? JarvisApplication
                        val indicator = app?.container?.ambientIndicator
                        val mode = intent.getStringExtra("state").orEmpty().lowercase()
                        val customLabel = intent.getStringExtra("label")
                        val snap = when (mode) {
                            "listening" -> AmbientPillSnapshot(true, JarvisState.LISTENING, customLabel ?: "Listening")
                            "thinking" -> AmbientPillSnapshot(true, JarvisState.THINKING, customLabel ?: "Thinking")
                            "executing" -> AmbientPillSnapshot(true, JarvisState.EXECUTING, customLabel ?: "Opening WhatsApp")
                            "speaking" -> AmbientPillSnapshot(true, JarvisState.SPEAKING, customLabel ?: "Jarvis")
                            "auth" -> AmbientPillSnapshot(true, JarvisState.AWAITING_AUTH, customLabel ?: "Confirm?")
                            else -> null
                        }
                        indicator?.setPreviewOverride(snap)
                        Log.i(TAG, "RESULT_PREVIEW_PILL: mode=$mode snap=$snap")
                    }

                    else -> Log.w(TAG, "RESULT: unknown action=$action")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "JarvisDebugPilot"
    }
}
