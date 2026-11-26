package com.jarvis.assistant.voice

import java.util.Locale

data class WakeWordMatch(
    val matched: Boolean,
    val trailingCommand: String = "",
    val triggerToken: String = ""
)

/**
 * Pure Kotlin wake-word parser supporting both English and German pronunciations
 * and one-shot commands (e.g. "Jarvis" or "Hey Jarvis, öffne WhatsApp").
 */
object WakeWordDetector {

    private val wakeTokens = setOf(
        "jarvis", "jarwis", "jarviss", "jarves", "jarviz",
        "djarvis", "tscharvis", "charvis", "yarvis", "javis", "jarvi"
    )

    // Match optional greeting + wake word token or spelled "j.a.r.v.i.s" / "j a r v i s"
    private val wakeRegex = Regex(
        pattern = """(?i)(?:^|[\s,;.!?])(?:(?:hey|ok|okay|hi|hallo|yo)\s+)?(j\.?\s*a\.?\s*r\.?\s*v\.?\s*i\.?\s*s\.?|jarvis|jarwis|jarviss|jarves|jarviz|djarvis|tscharvis|charvis|yarvis|javis|jarvi)(?:$|[\s,;.!?:])""",
        option = RegexOption.IGNORE_CASE
    )

    private val fillerOnly = setOf("please", "bitte", "sir", "uh", "um", "äh", "ähm")

    fun parse(transcript: String): WakeWordMatch {
        val raw = transcript.trim()
        if (raw.isEmpty()) return WakeWordMatch(matched = false)

        val match = wakeRegex.find(raw) ?: return WakeWordMatch(matched = false)
        val token = match.groupValues.getOrNull(1).orEmpty().trim()
        val normToken = token.lowercase(Locale.ROOT).replace(".", "").replace(" ", "")
        if (normToken !in wakeTokens && normToken != "jarvis") {
            return WakeWordMatch(matched = false)
        }

        val afterIndex = match.range.last + 1
        val rawTrailing = if (afterIndex <= raw.length) raw.substring(afterIndex) else ""
        val cleanTrailing = rawTrailing.trimStart(' ', ',', '.', '!', '?', ':', ';', '-').trim()
        val meaningfulTrailing = if (cleanTrailing.lowercase(Locale.ROOT) in fillerOnly) "" else cleanTrailing

        return WakeWordMatch(
            matched = true,
            trailingCommand = meaningfulTrailing,
            triggerToken = token
        )
    }

    fun containsWakeWord(transcript: String): Boolean = parse(transcript).matched
}
