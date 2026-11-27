package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.ScreenTreeSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val alexaHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
}

private val alexaJsonMediaType = "application/json; charset=utf-8".toMediaType()

object AlexaHelper {
    const val ALEXA_PACKAGE = "com.amazon.dee.app"
    const val VM_V3_BASE = "https://api-v3.voicemonkey.io"
    const val VM_V2_BASE = "https://api-v2.voicemonkey.io"

    fun resolveWebhookUrl(templateUrl: String, routineName: String): String {
        val clean = templateUrl.trim()
        val encoded = URLEncoder.encode(routineName.trim(), "UTF-8")
        return clean
            .replace("{routine}", encoded, ignoreCase = true)
            .replace("{device}", encoded, ignoreCase = true)
            .replace("{event}", encoded, ignoreCase = true)
    }

    fun buildVoiceMonkeyTriggerBody(token: String, deviceId: String): JSONObject =
        JSONObject()
            .put("token", token.trim())
            .put("device", deviceId.trim())

    fun buildVoiceMonkeyAnnounceBody(token: String, deviceId: String, speech: String, v2: Boolean = false): JSONObject =
        JSONObject()
            .put("token", token.trim())
            .put("device", deviceId.trim())
            .put(if (v2) "text" else "speech", speech.trim())
}

class AlexaTriggerRoutineTool(
    private val settingsProvider: () -> JarvisSettings
) : JarvisTool {

    override val spec = ToolSpec(
        name = "alexa_trigger_routine",
        description = "Silently triggers an Amazon Alexa Routine in the background via Voice Monkey (API v3/v2) or a configured Alexa Routine Webhook URL (Virtual Smart Home / IFTTT / Make). Use this to trigger Alexa-connected smart home lights, plugs, or scenes without opening any app.",
        params = listOf(
            ToolParam(
                name = "device_id",
                type = "string",
                description = "Voice Monkey Routine Trigger device ID or routine name (e.g. 'living-room-lights', 'movie-night'). Omit to use the default configured Alexa device."
            ),
            ToolParam(
                name = "webhook_url",
                type = "string",
                description = "Optional explicit Alexa webhook URL (overrides the saved Routine Webhook URL)."
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val settings = settingsProvider()
        val token = settings.alexaVoiceMonkeyToken.trim()
        val deviceId = args.optString("device_id", "").trim().ifBlank { settings.alexaDefaultDevice.trim() }
        val webhookUrl = args.optString("webhook_url", "").trim().ifBlank { settings.alexaWebhookUrl.trim() }

        // Mode 1: Voice Monkey API (v3 with automatic v2 fallback)
        if (token.isNotBlank() && deviceId.isNotBlank()) {
            return withContext(Dispatchers.IO) {
                runCatching {
                    val body = AlexaHelper.buildVoiceMonkeyTriggerBody(token, deviceId).toString()
                    val v3Req = Request.Builder()
                        .url("${AlexaHelper.VM_V3_BASE}/trigger")
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .post(body.toRequestBody(alexaJsonMediaType))
                        .build()

                    alexaHttpClient.newCall(v3Req).execute().use { resp ->
                        val respBody = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            return@withContext ToolExecutionOutput(
                                true,
                                "Triggered Amazon Alexa routine '$deviceId' via Voice Monkey.",
                                JSONObject().put("device_id", deviceId).put("api", "v3")
                            )
                        }
                        // Fallback for legacy Voice Monkey API v2 tokens
                        if (resp.code == 401 || resp.code == 404) {
                            val v2Req = Request.Builder()
                                .url("${AlexaHelper.VM_V2_BASE}/trigger")
                                .header("Authorization", token)
                                .header("Content-Type", "application/json")
                                .post(body.toRequestBody(alexaJsonMediaType))
                                .build()
                            alexaHttpClient.newCall(v2Req).execute().use { v2Resp ->
                                val v2Body = v2Resp.body?.string().orEmpty()
                                if (v2Resp.isSuccessful) {
                                    return@withContext ToolExecutionOutput(
                                        true,
                                        "Triggered Amazon Alexa routine '$deviceId' via Voice Monkey v2.",
                                        JSONObject().put("device_id", deviceId).put("api", "v2")
                                    )
                                }
                                return@withContext ToolExecutionOutput(
                                    false,
                                    "Voice Monkey trigger failed (HTTP ${v2Resp.code}): ${v2Body.take(180)}"
                                )
                            }
                        }
                        ToolExecutionOutput(
                            false,
                            "Voice Monkey trigger failed (HTTP ${resp.code}): ${respBody.take(180)}"
                        )
                    }
                }.getOrElse { err ->
                    ToolExecutionOutput(false, "Could not reach Voice Monkey for Alexa routine: ${err.message}")
                }
            }
        }

        // Mode 2: Custom Routine Webhook URL (Virtual Smart Home / IFTTT / Make)
        if (webhookUrl.startsWith("http://") || webhookUrl.startsWith("https://")) {
            val resolvedUrl = AlexaHelper.resolveWebhookUrl(webhookUrl, deviceId)
            return withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(resolvedUrl).get().build()
                    alexaHttpClient.newCall(req).execute().use { resp ->
                        val respBody = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext ToolExecutionOutput(
                                false,
                                "Alexa webhook returned HTTP ${resp.code}: ${respBody.take(160)}"
                            )
                        }
                        ToolExecutionOutput(
                            true,
                            "Triggered Amazon Alexa webhook${if (deviceId.isNotBlank()) " for '$deviceId'" else ""}."
                        )
                    }
                }.getOrElse { err ->
                    ToolExecutionOutput(false, "Alexa webhook request failed: ${err.message}")
                }
            }
        }

        return ToolExecutionOutput(
            false,
            "Silent Alexa background triggers are not configured yet. Configure Voice Monkey token + device ID (or a Routine Webhook URL) in Jarvis Settings -> Ecosystem & integrations -> Amazon Alexa, or use alexa_app_command to send commands directly through the Amazon Alexa app."
        )
    }
}

class AlexaAnnounceTool(
    private val settingsProvider: () -> JarvisSettings
) : JarvisTool {

    override val spec = ToolSpec(
        name = "alexa_announce",
        description = "Speaks a text announcement aloud on the user's Amazon Echo / Alexa speakers via Voice Monkey API.",
        params = listOf(
            ToolParam(
                name = "message",
                type = "string",
                description = "Exact text message for Alexa to announce aloud on Echo speakers.",
                required = true
            ),
            ToolParam(
                name = "device_id",
                type = "string",
                description = "Target Voice Monkey speaker device ID (or comma-separated IDs). Omit to use the configured default Alexa device."
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val message = args.optString("message", "").trim()
        if (message.isBlank()) return ToolExecutionOutput(false, "Announcement message cannot be empty.")

        val settings = settingsProvider()
        val token = settings.alexaVoiceMonkeyToken.trim()
        val deviceId = args.optString("device_id", "").trim().ifBlank { settings.alexaDefaultDevice.trim() }
        if (token.isBlank() || deviceId.isBlank()) {
            return ToolExecutionOutput(
                false,
                "Alexa Echo announcements require a Voice Monkey API token and default speaker device ID in Jarvis Settings -> Ecosystem & integrations -> Amazon Alexa."
            )
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val v3Body = AlexaHelper.buildVoiceMonkeyAnnounceBody(token, deviceId, message, v2 = false).toString()
                val v3Req = Request.Builder()
                    .url("${AlexaHelper.VM_V3_BASE}/announce")
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .post(v3Body.toRequestBody(alexaJsonMediaType))
                    .build()

                alexaHttpClient.newCall(v3Req).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        return@withContext ToolExecutionOutput(
                            true,
                            "Announced on Amazon Alexa ('$deviceId'): \"$message\"."
                        )
                    }
                    if (resp.code == 401 || resp.code == 404) {
                        val v2Body = AlexaHelper.buildVoiceMonkeyAnnounceBody(token, deviceId, message, v2 = true).toString()
                        val v2Req = Request.Builder()
                            .url("${AlexaHelper.VM_V2_BASE}/announcement")
                            .header("Authorization", token)
                            .header("Content-Type", "application/json")
                            .post(v2Body.toRequestBody(alexaJsonMediaType))
                            .build()
                        alexaHttpClient.newCall(v2Req).execute().use { v2Resp ->
                            val v2RespBody = v2Resp.body?.string().orEmpty()
                            if (v2Resp.isSuccessful) {
                                return@withContext ToolExecutionOutput(
                                    true,
                                    "Announced on Amazon Alexa ('$deviceId'): \"$message\"."
                                )
                            }
                            return@withContext ToolExecutionOutput(
                                false,
                                "Voice Monkey announcement failed (HTTP ${v2Resp.code}): ${v2RespBody.take(180)}"
                            )
                        }
                    }
                    ToolExecutionOutput(
                        false,
                        "Voice Monkey announcement failed (HTTP ${resp.code}): ${respBody.take(180)}"
                    )
                }
            }.getOrElse { err ->
                ToolExecutionOutput(false, "Could not reach Voice Monkey for Alexa announcement: ${err.message}")
            }
        }
    }
}

class AlexaAppCommandTool(
    private val context: Context,
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {

    override val spec = ToolSpec(
        name = "alexa_app_command",
        description = "Sends any free-form command to Amazon Alexa directly through the official Amazon Alexa Android app (zero extra API setup required). Opens the Alexa app and returns its CURRENT SCREEN so you can tap 'Type with Alexa' / keyboard and submit the command.",
        params = listOf(
            ToolParam(
                name = "command",
                type = "string",
                description = "Exact command to send to Alexa (e.g. 'turn off the living room lights', 'play radio on kitchen echo').",
                required = true
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val command = args.optString("command", "").trim()
        if (command.isBlank()) return ToolExecutionOutput(false, "Alexa command is empty.")

        val launchIntent = context.packageManager.getLaunchIntentForPackage(AlexaHelper.ALEXA_PACKAGE)
            ?: return ToolExecutionOutput(
                false,
                "Amazon Alexa app (com.amazon.dee.app) is not installed on this phone. Install it from Google Play, or configure Voice Monkey / a Routine Webhook in Jarvis Settings -> Ecosystem & integrations -> Amazon Alexa."
            )

        prepareScreenForPilot()

        val opened = runCatching {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)

        if (!opened) {
            return ToolExecutionOutput(false, "Unable to launch the Amazon Alexa app.")
        }

        delay(1_150L)
        val a11y = JarvisAccessibilityService.instance
        val screenText = a11y?.captureScreenSnapshot()?.let { snap ->
            "\n\nCURRENT SCREEN:\n${ScreenTreeSerializer.formatForLlm(snap)}"
        }.orEmpty()

        return ToolExecutionOutput(
            true,
            "Opened Amazon Alexa app for command '$command'. Look in CURRENT SCREEN for the keyboard / 'Type with Alexa' / 'Ask Alexa' button or input box, tap it, and call type_text(text=\"$command\", submit=true).$screenText"
        )
    }
}
