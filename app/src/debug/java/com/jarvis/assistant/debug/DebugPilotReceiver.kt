package com.jarvis.assistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
