package com.jarvis.assistant.agent

import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class LocalCommandPlan(
    val toolCalls: List<ToolCall>,
    val composeReply: (List<ToolResult>, String) -> String
)

object LocalCommandRouter {

    fun match(rawInput: String, languageOverride: SpokenLanguage? = null): LocalCommandPlan? {
        val text = rawInput.trim()
        if (text.isBlank()) return null
        val lower = text.lowercase(Locale.ROOT)
            .removePrefix("hey jarvis")
            .removePrefix("ok jarvis")
            .removePrefix("okay jarvis")
            .removePrefix("hallo jarvis")
            .removePrefix("jarvis")
            .trim(',', '.', '!', '?', ' ')
        if (lower.isBlank()) return null

        val lang = languageOverride ?: JarvisLanguageDetector.detect(rawInput)
        val de = lang == SpokenLanguage.GERMAN

        // Built-in offline protocols work in both English & German
        matchBuiltInProtocolOffline(lower, de)?.let { return it }

        // Do not intercept multi-clause requests in English or German — escalate to the LLM agent loop
        if (lower.contains(" and ") ||
            lower.contains(" then ") ||
            lower.contains(" und ") ||
            lower.contains(" dann ") ||
            (lower.contains("?") && !isSimpleStatusQuestion(lower))
        ) {
            return null
        }

        // 1. Flashlight / Torch (EN + DE)
        if (Regex("^(?:turn |switch |mach |mache |schalte |schalt )?(?:on )?(?:the |die |das )?(?:flashlight|torch|taschenlampe|licht)(?: on| an| ein| einschalten| anmachen)?$").matches(lower) &&
            (lower.contains("on") || lower.contains("an") || lower.contains("ein") || lower in setOf("flashlight", "torch", "taschenlampe")) &&
            !lower.contains("off") && !lower.contains("aus")
        ) {
            return single("set_flashlight", JSONObject().put("enabled", true)) { res, title ->
                if (res.success) {
                    if (de) "Taschenlampe eingeschaltet, $title." else "Flashlight engaged, $title."
                } else res.output
            }
        }
        if (Regex("^(?:turn |switch |mach |mache |schalte |schalt )?(?:off )?(?:the |die |das )?(?:flashlight|torch|taschenlampe|licht)(?: off| aus| ausschalten| ausmachen)?$").matches(lower) &&
            (lower.contains("off") || lower.contains("aus"))
        ) {
            return single("set_flashlight", JSONObject().put("enabled", false)) { res, title ->
                if (res.success) {
                    if (de) "Taschenlampe ausgeschaltet, $title." else "Flashlight extinguished, $title."
                } else res.output
            }
        }

        // 2. Volume controls (EN + DE)
        val volSetMatch = Regex("^(?:set |stell(?:e)? (?:die )?)?(media |medien|ring |klingel|ringer |alarm |wecker|notification |call )?(?:volume|lautstärke|lautstaerke)(?: to| auf)? (\\d{1,3})(?: ?%| prozent)?$").find(lower)
        if (volSetMatch != null) {
            val rawStream = volSetMatch.groupValues[1].trim()
            val stream = when {
                rawStream.startsWith("klingel") -> "ring"
                rawStream.startsWith("wecker") -> "alarm"
                rawStream.startsWith("medien") || rawStream.isBlank() -> "media"
                else -> rawStream
            }
            val pct = volSetMatch.groupValues[2].toInt().coerceIn(0, 100)
            return single("set_volume", JSONObject().put("stream", stream).put("percent", pct)) { res, title ->
                if (res.success) {
                    if (de) "Lautstärke auf $pct Prozent eingestellt, $title."
                    else "Audio calibrated — $stream volume is now at $pct%, $title."
                } else res.output
            }
        }
        if (lower in setOf("volume up", "louder", "turn up volume", "turn up the volume", "lauter", "mach lauter", "lautstärke hoch", "mach es lauter")) {
            return single("set_volume", JSONObject().put("stream", "media").put("delta", 20)) { res, title ->
                if (res.success) {
                    if (de) "Lautstärke erhöht, $title." else "Increased media volume, $title."
                } else res.output
            }
        }
        if (lower in setOf("volume down", "quieter", "softer", "turn down volume", "turn down the volume", "leiser", "mach leiser", "lautstärke runter", "mach es leiser")) {
            return single("set_volume", JSONObject().put("stream", "media").put("delta", -20)) { res, title ->
                if (res.success) {
                    if (de) "Lautstärke verringert, $title." else "Lowered media volume, $title."
                } else res.output
            }
        }
        if (lower in setOf("mute", "mute phone", "silent mode", "silence phone", "go silent", "stumm", "lautlos", "handy stumm", "stumm schalten", "stummschalten")) {
            return single("set_ringer_mode", JSONObject().put("mode", "silent")) { res, title ->
                if (res.success) {
                    if (de) "Klingelton stummgeschaltet, $title." else "Ringer silenced, $title."
                } else res.output
            }
        }
        if (lower in setOf("vibrate", "vibrate mode", "set vibrate", "vibrieren", "vibrationsmodus")) {
            return single("set_ringer_mode", JSONObject().put("mode", "vibrate")) { res, title ->
                if (res.success) {
                    if (de) "Auf Vibration umgestellt, $title." else "Ringer switched to vibrate, $title."
                } else res.output
            }
        }
        if (lower in setOf("unmute", "normal ringer", "sound on", "ringer normal", "ton an", "klingelton an", "nicht mehr stumm")) {
            return single("set_ringer_mode", JSONObject().put("mode", "normal")) { res, title ->
                if (res.success) {
                    if (de) "Klingelton wieder aktiviert, $title." else "Ringer restored to normal profile, $title."
                } else res.output
            }
        }

        // 3. Brightness controls (EN + DE)
        val brightMatch = Regex("^(?:set |stell(?:e)? (?:die )?)?(?:screen |bildschirm)?(?:brightness|helligkeit)(?: to| auf)? (\\d{1,3})(?: ?%| prozent)?$").find(lower)
        if (brightMatch != null) {
            val pct = brightMatch.groupValues[1].toInt().coerceIn(5, 100)
            return single("set_brightness", JSONObject().put("percent", pct)) { res, title ->
                if (res.success) {
                    if (de) "Helligkeit auf $pct Prozent eingestellt, $title."
                    else "Display brightness calibrated to $pct%, $title."
                } else res.output
            }
        }
        if (lower in setOf("auto brightness", "adaptive brightness", "automatic brightness", "automatische helligkeit", "auto helligkeit")) {
            return single("set_brightness", JSONObject().put("auto", true)) { res, title ->
                if (res.success) {
                    if (de) "Automatische Helligkeit aktiviert, $title." else "Adaptive brightness engaged, $title."
                } else res.output
            }
        }

        // 4. Do Not Disturb (EN + DE)
        if (lower in setOf(
                "enable do not disturb", "turn on do not disturb", "do not disturb on", "dnd on",
                "nicht stören an", "nicht stören einschalten", "nicht stören aktivieren", "bitte nicht stören an"
            )
        ) {
            return single("set_dnd", JSONObject().put("enabled", true)) { res, title ->
                if (res.success) {
                    if (de) "Nicht stören ist aktiv, $title." else "Do Not Disturb is active, $title."
                } else res.output
            }
        }
        if (lower in setOf(
                "disable do not disturb", "turn off do not disturb", "do not disturb off", "dnd off",
                "nicht stören aus", "nicht stören ausschalten", "nicht stören deaktivieren", "bitte nicht stören aus"
            )
        ) {
            return single("set_dnd", JSONObject().put("enabled", false)) { res, title ->
                if (res.success) {
                    if (de) "Nicht stören deaktiviert, $title." else "Do Not Disturb deactivated, $title."
                } else res.output
            }
        }

        // 5. Media Playback (EN + DE)
        val mediaAction = when (lower) {
            "play", "play music", "resume", "resume music", "musik abspielen", "abspielen", "weiter spielen", "musik weiter" -> "play"
            "pause", "pause music", "stop music", "musik pausieren", "pausieren", "musik stoppen", "musik aus" -> "pause"
            "next", "next song", "next track", "skip", "skip song", "skip track", "nächster song", "nächstes lied", "überspringen", "weiter" -> "next"
            "previous", "previous song", "previous track", "last song", "vorheriger song", "vorheriges lied", "letztes lied" -> "previous"
            else -> null
        }
        if (mediaAction != null) {
            return single("media_control", JSONObject().put("action", mediaAction)) { res, title ->
                if (res.success) {
                    if (de) {
                        when (mediaAction) {
                            "play" -> "Wiedergabe gestartet, $title."
                            "pause" -> "Wiedergabe pausiert, $title."
                            "next" -> "Nächster Titel, $title."
                            else -> "Vorheriger Titel, $title."
                        }
                    } else "Media $mediaAction dispatched, $title."
                } else res.output
            }
        }

        // 6. Global Navigation & System Actions (EN + DE)
        val globalAction = when (lower) {
            "go home", "home screen", "startbildschirm", "geh zum startbildschirm", "zurück zum startbildschirm" -> "home"
            "go back", "back", "zurück", "geh zurück" -> "back"
            "recents", "recent apps", "overview", "letzte apps", "app übersicht" -> "recents"
            "screenshot", "take screenshot", "take a screenshot", "screenshot machen", "mach einen screenshot", "bildschirmfoto", "bildschirmfoto machen" -> "screenshot"
            "lock screen", "lock phone", "lock my phone", "bildschirm sperren", "handy sperren", "sperr den bildschirm" -> "lock_screen"
            "open notifications", "show notification shade", "benachrichtigungen öffnen", "zeig benachrichtigungen" -> "notifications"
            "open quick settings", "control center", "schnelleinstellungen öffnen", "kontrollzentrum öffnen" -> "quick_settings"
            else -> null
        }
        if (globalAction != null) {
            return single("global_action", JSONObject().put("action", globalAction)) { res, title ->
                if (res.success) {
                    if (de) "Ausgeführt, $title." else "Executed ${globalAction.replace('_', ' ')}, $title."
                } else res.output
            }
        }

        // 7. System Settings Panels (EN + DE)
        val panelMatch = Regex("^(?:open |show |öffne |oeffne |zeig(?:e)? )?(wifi|wi-fi|wlan|internet|bluetooth|nfc|battery|akku|display|bildschirm|location|standort|airplane|flugmodus|settings|einstellungen)(?: panel| settings| einstellungen)?$").find(lower)
        if (panelMatch != null) {
            val rawPanel = panelMatch.groupValues[1]
            val panel = when (rawPanel) {
                "wi-fi", "wlan" -> "wifi"
                "akku" -> "battery"
                "bildschirm" -> "display"
                "standort" -> "location"
                "flugmodus" -> "airplane"
                "einstellungen" -> "settings"
                else -> rawPanel
            }
            return single("open_system_panel", JSONObject().put("panel", panel)) { res, title ->
                if (res.success) {
                    if (de) "Öffne ${rawPanel.replaceFirstChar { it.uppercase() }}-Einstellungen, $title."
                    else "Opening $panel controls, $title."
                } else res.output
            }
        }

        // 8. Timer (EN + DE)
        val timerMatch = Regex("^(?:set (?:a )?|stell(?:e)? (?:einen )?)?timer(?: for| auf| für)? (\\d+) ?(second|seconds|sec|s|sekunde|sekunden|minute|minutes|min|m|minuten|hour|hours|hr|h|stunde|stunden)$").find(lower)
        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1].toInt()
            val unit = timerMatch.groupValues[2]
            val seconds = when {
                unit.startsWith("h") || unit.startsWith("stund") -> amount * 3600
                unit.startsWith("m") -> amount * 60
                else -> amount
            }
            return single("set_timer", JSONObject().put("seconds", seconds).put("label", "J.A.R.V.I.S. Timer")) { res, title ->
                if (res.success) {
                    if (de) "Timer auf $amount $unit gestellt, $title." else "${res.output} Ready, $title."
                } else res.output
            }
        }

        // 9. Alarm (EN + DE, e.g. "set alarm for 7:30", "wecker auf 7:30", "weck mich um 6:45")
        val alarmMatch = Regex("^(?:set (?:an )?alarm(?: for)?|wake me(?: up)? at|stell(?:e)? (?:einen )?wecker(?: auf| für)?|wecker(?: auf)?|weck(?:e)? mich(?: um)?) (\\d{1,2})(?::(\\d{2}))?(?: uhr)?( ?am| ?pm)?$").find(lower)
        if (alarmMatch != null) {
            var hour = alarmMatch.groupValues[1].toInt()
            val min = alarmMatch.groupValues[2].ifBlank { "0" }.toInt()
            val ampm = alarmMatch.groupValues[3].trim()
            if (ampm == "pm" && hour in 1..11) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            if (hour in 0..23 && min in 0..59) {
                val timeStr = "%02d:%02d".format(hour, min)
                return single("set_alarm", JSONObject().put("hour", hour).put("minute", min)) { res, title ->
                    if (res.success) {
                        if (de) "Wecker auf $timeStr Uhr gestellt, $title." else "${res.output} Rest assured, $title."
                    } else res.output
                }
            }
        }

        // 10. Open App (EN + DE: "open spotify", "öffne whatsapp", "starte kamera")
        val openMatch = Regex("^(?:open|launch|start|öffne|oeffne|starte) ([a-z0-9äöüß ._+&-]{2,40})$").find(lower)
        if (openMatch != null) {
            val appName = openMatch.groupValues[1].trim()
            if (appName !in setOf("notifications", "quick settings", "benachrichtigungen", "schnelleinstellungen")) {
                return single("open_app", JSONObject().put("app_name", appName)) { res, title ->
                    if (res.success) {
                        val clean = res.output.substringBefore("\n\nCURRENT SCREEN:").trim()
                        if (de) {
                            val label = clean.removePrefix("Launched ").substringBefore(" (").trim().ifBlank { appName }
                            "$label geöffnet, $title."
                        } else {
                            "$clean At your service, $title."
                        }
                    } else {
                        res.output
                    }
                }
            }
        }

        // 11. Web search (EN + DE)
        val searchMatch = Regex("^(?:google(?: nach)?|search(?: the web)? for|suche(?: im web)? nach) (.+)$").find(lower)
        if (searchMatch != null) {
            val query = searchMatch.groupValues[1].trim()
            return single("web_search", JSONObject().put("query", query)) { res, title ->
                if (res.success) {
                    if (de) "Ich suche nach \"$query\", $title." else "Searching for \"$query\", $title."
                } else res.output
            }
        }

        // 12. Diagnostics & Battery Status (EN + DE)
        if (lower in setOf(
                "diagnostics", "suit diagnostics", "system diagnostics", "status report", "system status",
                "battery status", "check battery", "how is my battery", "how's my battery", "phone status",
                "akku", "akkustand", "wie ist mein akku", "wie viel akku habe ich", "wieviel akku habe ich",
                "systemstatus", "diagnose", "statusbericht"
            )
        ) {
            return single("get_device_diagnostics", JSONObject()) { res, title ->
                if (res.success) {
                    if (de) "Systemdiagnose abgeschlossen, $title. ${res.output}"
                    else "All telemetry compiled, $title. ${res.output}"
                } else res.output
            }
        }

        // 13. Read Notifications (EN + DE)
        if (lower in setOf(
                "read notifications", "check notifications", "any notifications", "my notifications",
                "benachrichtigungen vorlesen", "lies benachrichtigungen", "neue benachrichtigungen", "meine benachrichtigungen"
            )
        ) {
            return single("get_notifications", JSONObject()) { res, title ->
                if (res.success) {
                    if (de) "Hier sind deine Benachrichtigungen, $title:\n${res.output}"
                    else "Here is your notification feed, $title:\n${res.output}"
                } else res.output
            }
        }

        return null
    }

    private fun matchBuiltInProtocolOffline(lower: String, de: Boolean): LocalCommandPlan? {
        return when (lower) {
            "stealth mode", "initiate stealth mode", "activate stealth mode", "tarnmodus", "tarnmodus aktivieren" -> LocalCommandPlan(
                toolCalls = listOf(
                    ToolCall(newId(), "set_ringer_mode", JSONObject().put("mode", "silent")),
                    ToolCall(newId(), "set_volume", JSONObject().put("stream", "media").put("percent", 0)),
                    ToolCall(newId(), "set_flashlight", JSONObject().put("enabled", false)),
                    ToolCall(newId(), "set_brightness", JSONObject().put("percent", 15))
                ),
                composeReply = { results, title ->
                    val okCount = results.count { it.success }
                    if (de) "Tarnmodus aktiviert ($okCount/${results.size} Systeme lautlos und gedimmt), $title."
                    else "Stealth Mode engaged ($okCount/${results.size} subsystems silent and dimmed), $title."
                }
            )

            "night protocol", "initiate night protocol", "goodnight protocol", "nachtprotokoll", "nachtmodus aktivieren", "gute nacht protokoll" -> LocalCommandPlan(
                toolCalls = listOf(
                    ToolCall(newId(), "set_brightness", JSONObject().put("percent", 10)),
                    ToolCall(newId(), "set_ringer_mode", JSONObject().put("mode", "silent")),
                    ToolCall(newId(), "set_dnd", JSONObject().put("enabled", true)),
                    ToolCall(newId(), "get_device_diagnostics", JSONObject())
                ),
                composeReply = { results, title ->
                    val diag = results.lastOrNull()?.output.orEmpty()
                    if (de) "Nachtprotokoll abgeschlossen, $title. Display gedimmt und Benachrichtigungen stumm. $diag"
                    else "Night Protocol complete, $title. Display dimmed, notifications silenced. $diag"
                }
            )

            "focus protocol", "initiate focus protocol", "activate focus mode", "fokusmodus", "fokusprotokoll", "fokusmodus aktivieren" -> LocalCommandPlan(
                toolCalls = listOf(
                    ToolCall(newId(), "set_dnd", JSONObject().put("enabled", true)),
                    ToolCall(newId(), "set_volume", JSONObject().put("stream", "media").put("percent", 25))
                ),
                composeReply = { results, title ->
                    val ok = results.count { it.success }
                    if (de) "Fokusmodus aktiviert ($ok/${results.size} Anpassungen aktiv), $title."
                    else "Focus Protocol engaged ($ok/${results.size} adjustments applied), $title."
                }
            )

            else -> null
        }
    }

    private fun isSimpleStatusQuestion(lower: String): Boolean =
        lower.contains("battery") || lower.contains("akku") || lower.contains("notification") || lower.contains("benachrichtigung") || lower.contains("status")

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
