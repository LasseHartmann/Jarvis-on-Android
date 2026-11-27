package com.jarvis.assistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.agent.JarvisState
import com.jarvis.assistant.agent.SafetyMode
import com.jarvis.assistant.agent.SpokenLanguage
import com.jarvis.assistant.assist.AmbientPillSnapshot
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.ScreenTreeSerializer
import com.jarvis.assistant.voice.GeminiTtsClient
import com.jarvis.assistant.voice.OpenWakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

                    "set_safety" -> {
                        val app = context.applicationContext as? JarvisApplication
                        val modeStr = intent.getStringExtra("mode").orEmpty().uppercase()
                        val mode = runCatching { SafetyMode.valueOf(modeStr) }.getOrNull()
                        if (app != null && mode != null) {
                            app.container.settingsStore.update { it.copy(safetyMode = mode) }
                            Log.i(TAG, "RESULT_SET_SAFETY: safetyMode=${app.container.settingsStore.current().safetyMode}")
                        } else {
                            Log.w(TAG, "RESULT_SET_SAFETY: invalid mode=$modeStr")
                        }
                    }

                    "bench_tts" -> {
                        val app = context.applicationContext as? JarvisApplication
                        val container = app?.container
                        if (container != null) {
                            val s = container.settingsStore.current()
                            val start = System.currentTimeMillis()
                            container.voiceEngine.speak(
                                text = "J.A.R.V.I.S. streaming audio online, Sir.",
                                options = container.taskCoordinator.buildSpeechOptions(s),
                                onAudioStarted = {
                                    Log.i(TAG, "BENCH_TTS_AUDIO_START: ${System.currentTimeMillis() - start}ms")
                                },
                                onFinished = {
                                    Log.i(TAG, "BENCH_TTS_DONE: ${System.currentTimeMillis() - start}ms")
                                }
                            )
                        }
                    }

                    "bench_wakeword" -> {
                        val app = context.applicationContext as? JarvisApplication
                        val apiKey = app?.container?.settingsStore?.current()?.geminiApiKey.orEmpty()
                        withContext(Dispatchers.IO) {
                            OpenWakeWordEngine.fromAssets(context.applicationContext).use { engine ->
                                // 1. Test silence false-positive score
                                var silenceMax = 0f
                                val silence = ShortArray(OpenWakeWordEngine.CHUNK_SAMPLES)
                                repeat(30) {
                                    val sc = engine.processPcm16Chunk(silence)
                                    if (sc > silenceMax) silenceMax = sc
                                }

                                // 2. Synthesize real "Hey Jarvis" audio and verify live ONNX detection!
                                val tts = GeminiTtsClient()
                                val pcm24 = tts.synthesizeJarvisSpeech(
                                    apiKey = apiKey,
                                    text = "Hey Jarvis",
                                    voiceName = "Charon",
                                    language = SpokenLanguage.ENGLISH
                                )
                                var wakePeak = -1f
                                var inferenceMsAvg = 0.0
                                if (pcm24 != null) {
                                    val pcm16 = OpenWakeWordEngine.resampleLinear(pcm24.samples, pcm24.sampleRateHz, 16_000)
                                    // Pad 1.5s silence before & 0.8s after so rolling window fills cleanly
                                    val padded = ShortArray(24_000 + pcm16.size + 12_800)
                                    System.arraycopy(pcm16, 0, padded, 24_000, pcm16.size)
                                    engine.reset()
                                    val t0 = System.nanoTime()
                                    var frames = 0
                                    var pos = 0
                                    while (pos + OpenWakeWordEngine.CHUNK_SAMPLES <= padded.size) {
                                        val chunk = padded.copyOfRange(pos, pos + OpenWakeWordEngine.CHUNK_SAMPLES)
                                        val sc = engine.processPcm16Chunk(chunk)
                                        if (sc > wakePeak) wakePeak = sc
                                        pos += OpenWakeWordEngine.CHUNK_SAMPLES
                                        frames++
                                    }
                                    inferenceMsAvg = (System.nanoTime() - t0) / 1_000_000.0 / frames.coerceAtLeast(1)
                                }
                                Log.i(
                                    TAG,
                                    "BENCH_WAKEWORD: silenceMax=${"%.4f".format(silenceMax)} heyJarvisPeak=${"%.4f".format(wakePeak)} avgFrameMs=${"%.2f".format(inferenceMsAvg)}"
                                )
                            }
                        }
                    }

                    "test_nav" -> {
                        val app = context.applicationContext as? JarvisApplication
                        val dest = intent.getStringExtra("dest") ?: "Olympiaturm, Munich"
                        val mode = intent.getStringExtra("mode") ?: "driving"
                        if (app != null) {
                            val res = app.container.toolRegistry.execute(
                                com.jarvis.assistant.agent.ToolCall(
                                    id = "dbg_nav",
                                    name = "navigate_to",
                                    arguments = org.json.JSONObject().put("destination", dest).put("mode", mode)
                                )
                            )
                            Log.i(TAG, "RESULT_TEST_NAV: ok=${res.success} out=${res.output}")
                        }
                    }

                    "test_mark2" -> {
                        val app = context.applicationContext as? JarvisApplication
                        if (app != null) {
                            val registry = app.container.toolRegistry
                            val irMgr = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? android.hardware.ConsumerIrManager
                            val hasIr = irMgr?.hasIrEmitter() == true
                            val ranges = runCatching {
                                irMgr?.carrierFrequencies?.joinToString { "${it.minFrequency}-${it.maxFrequency}Hz" }
                            }.getOrNull().orEmpty()
                            Log.i(TAG, "MARK2_TOOLS: count=${registry.allSpecs().size} hasIr=$hasIr ranges=[$ranges]")

                            val weather = registry.execute(
                                com.jarvis.assistant.agent.ToolCall(
                                    id = "dbg_weather",
                                    name = "get_weather",
                                    arguments = org.json.JSONObject().put("location", "Munich")
                                )
                            )
                            Log.i(TAG, "MARK2_WEATHER: ok=${weather.success} out=${weather.output.replace('\n', ' ')}")

                            val liveWeb = registry.execute(
                                com.jarvis.assistant.agent.ToolCall(
                                    id = "dbg_web",
                                    name = "live_web_answer",
                                    arguments = org.json.JSONObject().put("query", "Who is the current mayor of Munich?")
                                )
                            )
                            Log.i(TAG, "MARK2_WEB: ok=${liveWeb.success} out=${liveWeb.output.take(260).replace('\n', ' ')}")

                            val vision = registry.execute(
                                com.jarvis.assistant.agent.ToolCall(
                                    id = "dbg_vision",
                                    name = "analyze_screen_vision",
                                    arguments = org.json.JSONObject().put("question", "Describe what is currently on this screen in one short sentence.")
                                )
                            )
                            Log.i(TAG, "MARK2_VISION: ok=${vision.success} out=${vision.output.take(260).replace('\n', ' ')}")
                        }
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
