package com.jarvis.assistant.tools

import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val ecosystemHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
}

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

class ListSmartHomeEntitiesTool(
    private val settingsProvider: () -> JarvisSettings
) : JarvisTool {

    override val spec = ToolSpec(
        name = "list_smart_home_entities",
        description = "Lists smart home devices, lights, switches, thermostats, scenes, and media players from the user's configured Home Assistant server.",
        params = listOf(
            ToolParam(
                name = "domain_filter",
                type = "string",
                description = "Optional Home Assistant domain filter such as light, switch, climate, scene, cover, fan, media_player, lock."
            ),
            ToolParam(
                name = "query",
                type = "string",
                description = "Optional case-insensitive name or room filter (e.g. 'living room', 'desk', 'kitchen')."
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val settings = settingsProvider()
        val baseUrl = settings.homeAssistantUrl.trim().trimEnd('/')
        val token = settings.homeAssistantToken.trim()
        if (baseUrl.isBlank() || token.isBlank()) {
            return ToolExecutionOutput(
                false,
                "Home Assistant is not configured yet. Ask the user to set the Home Assistant URL and Long-Lived Access Token in Jarvis Settings -> Ecosystem & integrations."
            )
        }

        val domainFilter = args.optString("domain_filter").trim().lowercase()
        val query = args.optString("query").trim().lowercase()

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/api/states")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .get()
                    .build()

                ecosystemHttpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext ToolExecutionOutput(
                            false,
                            "Home Assistant returned HTTP ${resp.code}: ${body.take(180)}"
                        )
                    }
                    val arr = JSONArray(body)
                    val defaultDomains = setOf(
                        "light", "switch", "climate", "scene", "script",
                        "cover", "fan", "media_player", "lock", "vacuum", "input_boolean"
                    )
                    val matched = ArrayList<String>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val entityId = obj.optString("entity_id")
                        val domain = entityId.substringBefore('.', "")
                        if (domainFilter.isNotEmpty()) {
                            if (domain != domainFilter) continue
                        } else if (query.isEmpty() && domain !in defaultDomains) {
                            continue
                        }
                        val state = obj.optString("state")
                        val attrs = obj.optJSONObject("attributes")
                        val friendly = attrs?.optString("friendly_name").orEmpty()
                        if (query.isNotEmpty() &&
                            !entityId.lowercase().contains(query) &&
                            !friendly.lowercase().contains(query)
                        ) {
                            continue
                        }
                        val tempNote = attrs?.takeIf { it.has("current_temperature") }?.let {
                            " (current=${it.opt("current_temperature")}°, target=${it.opt("temperature")}°)"
                        }.orEmpty()
                        matched.add("$entityId | ${friendly.ifBlank { entityId }} | state=$state$tempNote")
                        if (matched.size >= 35) break
                    }

                    if (matched.isEmpty()) {
                        ToolExecutionOutput(
                            true,
                            "No matching Home Assistant entities found (domain='$domainFilter', query='$query')."
                        )
                    } else {
                        ToolExecutionOutput(
                            true,
                            "Home Assistant entities (${matched.size}):\n" + matched.joinToString("\n")
                        )
                    }
                }
            }.getOrElse { err ->
                ToolExecutionOutput(false, "Could not reach Home Assistant at $baseUrl: ${err.message}")
            }
        }
    }
}

class SmartHomeControlTool(
    private val settingsProvider: () -> JarvisSettings
) : JarvisTool {

    override val spec = ToolSpec(
        name = "smart_home_control",
        description = "Controls a smart home device or triggers a scene via Home Assistant (e.g. turn lights on/off, dim brightness, adjust thermostat, activate scenes, open blinds).",
        params = listOf(
            ToolParam(
                name = "domain",
                type = "string",
                description = "Home Assistant domain (e.g. light, switch, climate, scene, script, cover, fan, media_player, lock).",
                required = true
            ),
            ToolParam(
                name = "service",
                type = "string",
                description = "Service action (e.g. turn_on, turn_off, toggle, set_temperature, open_cover, close_cover).",
                required = true
            ),
            ToolParam(
                name = "entity_id",
                type = "string",
                description = "Target entity ID (e.g. light.living_room, climate.bedroom, scene.movie_night).",
                required = true
            ),
            ToolParam(
                name = "brightness_pct",
                type = "integer",
                description = "Optional light brightness percentage 0-100."
            ),
            ToolParam(
                name = "temperature",
                type = "number",
                description = "Optional target temperature in Celsius for climate entities."
            ),
            ToolParam(
                name = "color_temp_kelvin",
                type = "integer",
                description = "Optional white color temperature in Kelvin (e.g. 2700 warm, 4500 neutral, 6000 cool)."
            ),
            ToolParam(
                name = "extra_json",
                type = "string",
                description = "Optional JSON object string with extra Home Assistant service fields."
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val settings = settingsProvider()
        val baseUrl = settings.homeAssistantUrl.trim().trimEnd('/')
        val token = settings.homeAssistantToken.trim()
        if (baseUrl.isBlank() || token.isBlank()) {
            return ToolExecutionOutput(
                false,
                "Home Assistant is not configured yet. Ask the user to enter their Home Assistant URL and token in Jarvis Settings -> Ecosystem & integrations."
            )
        }

        val domain = args.optString("domain").trim().lowercase()
        val service = args.optString("service").trim().lowercase()
        val entityId = args.optString("entity_id").trim()
        if (domain.isBlank() || service.isBlank() || entityId.isBlank()) {
            return ToolExecutionOutput(false, "domain, service, and entity_id are all required.")
        }
        if (!domain.matches(Regex("^[a-z0-9_]+$")) || !service.matches(Regex("^[a-z0-9_]+$"))) {
            return ToolExecutionOutput(false, "Invalid Home Assistant domain or service name.")
        }

        val payload = JSONObject().put("entity_id", entityId)
        if (args.has("brightness_pct") && !args.isNull("brightness_pct")) {
            payload.put("brightness_pct", args.optInt("brightness_pct").coerceIn(0, 100))
        }
        if (args.has("temperature") && !args.isNull("temperature")) {
            payload.put("temperature", args.optDouble("temperature"))
        }
        if (args.has("color_temp_kelvin") && !args.isNull("color_temp_kelvin")) {
            payload.put("color_temp_kelvin", args.optInt("color_temp_kelvin").coerceIn(1500, 9000))
        }
        val extra = args.optString("extra_json").trim()
        if (extra.startsWith("{")) {
            runCatching {
                val extraObj = JSONObject(extra)
                val keys = extraObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    payload.put(k, extraObj.get(k))
                }
            }
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/api/services/$domain/$service")
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()

                ecosystemHttpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext ToolExecutionOutput(
                            false,
                            "Home Assistant service $domain.$service failed (HTTP ${resp.code}): ${body.take(180)}"
                        )
                    }
                    ToolExecutionOutput(
                        true,
                        "Executed Home Assistant $domain.$service on $entityId.",
                        JSONObject()
                            .put("domain", domain)
                            .put("service", service)
                            .put("entity_id", entityId)
                    )
                }
            }.getOrElse { err ->
                ToolExecutionOutput(false, "Could not reach Home Assistant at $baseUrl: ${err.message}")
            }
        }
    }
}

class PcBridgeActionTool(
    private val settingsProvider: () -> JarvisSettings
) : JarvisTool {

    override val spec = ToolSpec(
        name = "pc_bridge_action",
        description = "Controls the user's Windows PC over local Wi-Fi via the Jarvis PC Bridge companion: open a URL in the PC browser, copy text to PC clipboard, lock the workstation, control PC media playback/volume, send a desktop notification, or check PC bridge status.",
        params = listOf(
            ToolParam(
                name = "action",
                type = "string",
                description = "Action to run on the user's Windows PC.",
                required = true,
                enumValues = listOf(
                    "status", "open_url", "set_clipboard", "lock_pc",
                    "media_play_pause", "media_next", "media_prev",
                    "volume_mute", "volume_up", "volume_down", "notify"
                )
            ),
            ToolParam(
                name = "value",
                type = "string",
                description = "URL for open_url, text for set_clipboard, or message body for notify."
            ),
            ToolParam(
                name = "title",
                type = "string",
                description = "Optional desktop notification title for notify."
            ),
            ToolParam(
                name = "steps",
                type = "integer",
                description = "Optional volume key repeat count (1-15, default 4) for volume_up / volume_down."
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val settings = settingsProvider()
        val baseUrl = settings.pcBridgeUrl.trim().trimEnd('/')
        val token = settings.pcBridgeToken.trim()
        if (baseUrl.isBlank() || token.isBlank()) {
            return ToolExecutionOutput(
                false,
                "Windows PC Bridge is not configured yet. Start pc-bridge/start_jarvis_bridge.ps1 on your PC and paste the printed URL and token into Jarvis Settings -> Ecosystem & integrations."
            )
        }

        val action = args.optString("action").trim().lowercase()
        if (action.isBlank()) {
            return ToolExecutionOutput(false, "Missing 'action' for pc_bridge_action.")
        }

        val payload = JSONObject().put("action", action)
        args.optString("value").trim().takeIf { it.isNotEmpty() }?.let { payload.put("value", it) }
        args.optString("title").trim().takeIf { it.isNotEmpty() }?.let { payload.put("title", it) }
        if (args.has("steps") && !args.isNull("steps")) {
            payload.put("steps", args.optInt("steps", 4).coerceIn(1, 15))
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/action")
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()

                ecosystemHttpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val parsed = runCatching { JSONObject(body) }.getOrNull()
                    if (!resp.isSuccessful || parsed?.optBoolean("ok", false) == false) {
                        val errMsg = parsed?.optString("error").orEmpty().ifBlank { body.take(180) }
                        return@withContext ToolExecutionOutput(
                            false,
                            "PC Bridge returned HTTP ${resp.code}: $errMsg"
                        )
                    }
                    val message = parsed?.optString("message").orEmpty()
                        .ifBlank { "Executed PC Bridge action '$action'." }
                    ToolExecutionOutput(true, message, parsed)
                }
            }.getOrElse { err ->
                ToolExecutionOutput(
                    false,
                    "Could not reach Windows PC Bridge at $baseUrl (ensure start_jarvis_bridge.ps1 is running on the same Wi-Fi): ${err.message}"
                )
            }
        }
    }
}
