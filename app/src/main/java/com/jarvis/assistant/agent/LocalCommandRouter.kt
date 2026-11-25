package com.jarvis.assistant.agent

import org.json.JSONObject
import java.util.UUID

data class LocalCommandPlan(
    val toolCalls: List<ToolCall>,
    val composeReply: (List<ToolResult>, String) -> String
)

object LocalCommandRouter {

    fun match(rawInput: String): LocalCommandPlan? {
        val text = rawInput.trim()
        if (text.isBlank()) return null
        val lower = text.lowercase()
            .removePrefix("hey jarvis")
            .removePrefix("jarvis")
            .trim(',', '.', '!', '?', ' ')
        if (lower.isBlank()) return null

        // Do not intercept multi-clause requests with "and then" / complex conversational questions
        // unless it matches a known built-in offline protocol trigger
        matchBuiltInProtocolOffline(lower)?.let { return it }

        if (lower.contains(" and ") || lower.contains(" then ") || lower.contains("?") && !isSimpleStatusQuestion(lower)) {
            return null
        }

        // 1. Flashlight / Torch
        if (Regex("^(turn |switch )?(on |the )?(flashlight|torch|taschenlampe)( on| an)?$").matches(lower) ||
            lower in setOf("flashlight on", "torch on", "turn on flashlight", "turn on the flashlight", "turn flashlight on", "taschenlampe an")
        ) {
            return single("set_flashlight", JSONObject().put("enabled", true)) { res, title ->
                if (res.success) "Flashlight engaged, $title." else res.output
            }
        }
        if (Regex("^(turn |switch )?(off |the )?(flashlight|torch|taschenlampe)( off| aus)?$").matches(lower) ||
            lower in setOf("flashlight off", "torch off", "turn off flashlight", "turn off the flashlight", "turn flashlight off", "taschenlampe aus")
        ) {
            return single("set_flashlight", JSONObject().put("enabled", false)) { res, title ->
                if (res.success) "Flashlight extinguished, $title." else res.output
            }
        }

        // 2. Volume controls
        val volSetMatch = Regex("^(?:set )?(media |ring |ringer |alarm |notification |call )?volume(?: to)? (\\d{1,3})%?$").find(lower)
        if (volSetMatch != null) {
            val stream = volSetMatch.groupValues[1].trim().ifBlank { "media" }
            val pct = volSetMatch.groupValues[2].toInt().coerceIn(0, 100)
            return single("set_volume", JSONObject().put("stream", stream).put("percent", pct)) { res, title ->
                if (res.success) "Audio calibrated — $stream volume is now at $pct%, $title." else res.output
            }
        }
        if (lower in setOf("volume up", "louder", "turn up volume", "turn up the volume", "lauter")) {
            return single("set_volume", JSONObject().put("stream", "media").put("delta", 20)) { res, title ->
                if (res.success) "Increased media volume, $title." else res.output
            }
        }
        if (lower in setOf("volume down", "quieter", "softer", "turn down volume", "turn down the volume", "leiser")) {
            return single("set_volume", JSONObject().put("stream", "media").put("delta", -20)) { res, title ->
                if (res.success) "Lowered media volume, $title." else res.output
            }
        }
        if (lower in setOf("mute", "mute phone", "silent mode", "silence phone", "go silent")) {
            return single("set_ringer_mode", JSONObject().put("mode", "silent")) { res, title ->
                if (res.success) "Ringer silenced, $title." else res.output
            }
        }
        if (lower in setOf("vibrate", "vibrate mode", "set vibrate")) {
            return single("set_ringer_mode", JSONObject().put("mode", "vibrate")) { res, title ->
                if (res.success) "Ringer switched to vibrate, $title." else res.output
            }
        }
        if (lower in setOf("unmute", "normal ringer", "sound on", "ringer normal")) {
            return single("set_ringer_mode", JSONObject().put("mode", "normal")) { res, title ->
                if (res.success) "Ringer restored to normal profile, $title." else res.output
            }
        }

        // 3. Brightness controls
        val brightMatch = Regex("^(?:set )?(?:screen )?brightness(?: to)? (\\d{1,3})%?$").find(lower)
        if (brightMatch != null) {
            val pct = brightMatch.groupValues[1].toInt().coerceIn(5, 100)
            return single("set_brightness", JSONObject().put("percent", pct)) { res, title ->
                if (res.success) "Display brightness calibrated to $pct%, $title." else res.output
            }
        }
        if (lower in setOf("auto brightness", "adaptive brightness", "automatic brightness")) {
            return single("set_brightness", JSONObject().put("auto", true)) { res, title ->
                if (res.success) "Adaptive brightness engaged, $title." else res.output
            }
        }

        // 4. Do Not Disturb
        if (lower in setOf("enable do not disturb", "turn on do not disturb", "do not disturb on", "dnd on")) {
            return single("set_dnd", JSONObject().put("enabled", true)) { res, title ->
                if (res.success) "Do Not Disturb is active, $title." else res.output
            }
        }
        if (lower in setOf("disable do not disturb", "turn off do not disturb", "do not disturb off", "dnd off")) {
            return single("set_dnd", JSONObject().put("enabled", false)) { res, title ->
                if (res.success) "Do Not Disturb deactivated, $title." else res.output
            }
        }

        // 5. Media Playback
        val mediaAction = when (lower) {
            "play", "play music", "resume", "resume music" -> "play"
            "pause", "pause music", "stop music" -> "pause"
            "next", "next song", "next track", "skip", "skip song", "skip track" -> "next"
            "previous", "previous song", "previous track", "last song" -> "previous"
            else -> null
        }
        if (mediaAction != null) {
            return single("media_control", JSONObject().put("action", mediaAction)) { res, title ->
                if (res.success) "Media $mediaAction dispatched, $title." else res.output
            }
        }

        // 6. Global Navigation & System Actions
        val globalAction = when (lower) {
            "go home", "home screen" -> "home"
            "go back", "back" -> "back"
            "recents", "recent apps", "overview" -> "recents"
            "screenshot", "take screenshot", "take a screenshot" -> "screenshot"
            "lock screen", "lock phone", "lock my phone" -> "lock_screen"
            "open notifications", "show notification shade" -> "notifications"
            "open quick settings", "control center" -> "quick_settings"
            else -> null
        }
        if (globalAction != null) {
            return single("global_action", JSONObject().put("action", globalAction)) { res, title ->
                if (res.success) "Executed ${globalAction.replace('_', ' ')}, $title." else res.output
            }
        }

        // 7. System Settings Panels
        val panelMatch = Regex("^(?:open |show )(wifi|wi-fi|internet|bluetooth|nfc|battery|display|location|airplane|settings)(?: panel| settings)?$").find(lower)
        if (panelMatch != null) {
            val panel = panelMatch.groupValues[1].replace("wi-fi", "wifi")
            return single("open_system_panel", JSONObject().put("panel", panel)) { res, title ->
                if (res.success) "Opening $panel controls, $title." else res.output
            }
        }

        // 8. Timer
        val timerMatch = Regex("^(?:set (?:a )?)?timer(?: for)? (\\d+) ?(second|seconds|sec|s|minute|minutes|min|m|hour|hours|hr|h)$").find(lower)
        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1].toInt()
            val unit = timerMatch.groupValues[2]
            val seconds = when {
                unit.startsWith("h") -> amount * 3600
                unit.startsWith("m") -> amount * 60
                else -> amount
            }
            return single("set_timer", JSONObject().put("seconds", seconds).put("label", "J.A.R.V.I.S. Timer")) { res, title ->
                if (res.success) "${res.output} Ready, $title." else res.output
            }
        }

        // 9. Alarm (e.g. "set alarm for 7:30", "set alarm 06:45")
        val alarmMatch = Regex("^(?:set (?:an )?alarm(?: for)?|wake me(?: up)? at) (\\d{1,2})(?::(\\d{2}))?( ?am| ?pm)?$").find(lower)
        if (alarmMatch != null) {
            var hour = alarmMatch.groupValues[1].toInt()
            val min = alarmMatch.groupValues[2].ifBlank { "0" }.toInt()
            val ampm = alarmMatch.groupValues[3].trim()
            if (ampm == "pm" && hour in 1..11) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            if (hour in 0..23 && min in 0..59) {
                return single("set_alarm", JSONObject().put("hour", hour).put("minute", min)) { res, title ->
                    if (res.success) "${res.output} Rest assured, $title." else res.output
                }
            }
        }

        // 10. Open App ("open spotify", "launch camera", "öffne whatsapp")
        val openMatch = Regex("^(?:open|launch|start|öffne) ([a-z0-9 ._+&-]{2,40})$").find(lower)
        if (openMatch != null) {
            val appName = openMatch.groupValues[1].trim()
            if (appName !in setOf("notifications", "quick settings")) {
                return single("open_app", JSONObject().put("app_name", appName)) { res, title ->
                    if (res.success) {
                        val clean = res.output.substringBefore("\n\nCURRENT SCREEN:").trim()
                        "$clean At your service, $title."
                    } else {
                        res.output
                    }
                }
            }
        }

        // 11. Web search
        val searchMatch = Regex("^(?:google|search(?: the web)? for) (.+)$").find(lower)
        if (searchMatch != null) {
            val query = searchMatch.groupValues[1].trim()
            return single("web_search", JSONObject().put("query", query)) { res, title ->
                if (res.success) "Searching for \"$query\", $title." else res.output
            }
        }

        // 12. Diagnostics & Battery Status
        if (lower in setOf(
                "diagnostics", "suit diagnostics", "system diagnostics", "status report", "system status",
                "battery status", "check battery", "how is my battery", "how's my battery", "phone status"
            )
        ) {
            return single("get_device_diagnostics", JSONObject()) { res, title ->
                if (res.success) "All telemetry compiled, $title. ${res.output}" else res.output
            }
        }

        // 13. Read Notifications
        if (lower in setOf("read notifications", "check notifications", "any notifications", "my notifications")) {
            return single("get_notifications", JSONObject()) { res, title ->
                if (res.success) "Here is your notification feed, $title:\n${res.output}" else res.output
            }
        }

        return null
    }

    private fun matchBuiltInProtocolOffline(lower: String): LocalCommandPlan? {
        return when (lower) {
            "stealth mode", "initiate stealth mode", "activate stealth mode" -> LocalCommandPlan(
                toolCalls = listOf(
                    ToolCall(newId(), "set_ringer_mode", JSONObject().put("mode", "silent")),
                    ToolCall(newId(), "set_volume", JSONObject().put("stream", "media").put("percent", 0)),
                    ToolCall(newId(), "set_flashlight", JSONObject().put("enabled", false)),
                    ToolCall(newId(), "set_brightness", JSONObject().put("percent", 15))
                ),
                composeReply = { results, title ->
                    val okCount = results.count { it.success }
                    "Stealth Mode engaged ($okCount/${results.size} subsystems silent and dimmed), $title."
                }
            )

            "night protocol", "initiate night protocol", "goodnight protocol" -> LocalCommandPlan(
                toolCalls = listOf(
                    ToolCall(newId(), "set_brightness", JSONObject().put("percent", 10)),
                    ToolCall(newId(), "set_ringer_mode", JSONObject().put("mode", "silent")),
                    ToolCall(newId(), "set_dnd", JSONObject().put("enabled", true)),
                    ToolCall(newId(), "get_device_diagnostics", JSONObject())
                ),
                composeReply = { results, title ->
                    val diag = results.lastOrNull()?.output.orEmpty()
                    "Night Protocol complete, $title. Display dimmed, notifications silenced. $diag"
                }
            )

            "focus protocol", "initiate focus protocol", "activate focus mode" -> LocalCommandPlan(
                toolCalls = listOf(
                    ToolCall(newId(), "set_dnd", JSONObject().put("enabled", true)),
                    ToolCall(newId(), "set_volume", JSONObject().put("stream", "media").put("percent", 25))
                ),
                composeReply = { results, title ->
                    val ok = results.count { it.success }
                    "Focus Protocol engaged ($ok/${results.size} adjustments applied), $title."
                }
            )

            else -> null
        }
    }

    private fun isSimpleStatusQuestion(lower: String): Boolean =
        lower.contains("battery") || lower.contains("notification") || lower.contains("status")

    private fun single(
        toolName: String,
        args: JSONObject,
        reply: (ToolResult, String) -> String
    ): LocalCommandPlan = LocalCommandPlan(
        toolCalls = listOf(ToolCall(id = newId(), name = toolName, arguments = args)),
        composeReply = { results, title ->
            val first = results.firstOrNull() ?: ToolResult("", toolName, false, "No result.")
            reply(first, title)
        }
    )

    private fun newId(): String = "local_${UUID.randomUUID().toString().take(8)}"
}
