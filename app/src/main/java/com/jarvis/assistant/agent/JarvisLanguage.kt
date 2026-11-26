package com.jarvis.assistant.agent

import java.util.Locale

enum class JarvisLanguageMode(
    val displayName: String,
    val subtitle: String
) {
    BILINGUAL_EN_DE(
        displayName = "English + Deutsch",
        subtitle = "Auto-detect & reply in English or German"
    ),
    ENGLISH_ONLY(
        displayName = "English",
        subtitle = "Listen and respond in English"
    ),
    GERMAN_ONLY(
        displayName = "Deutsch",
        subtitle = "Immer auf Deutsch zuhören und antworten"
    )
}

enum class SpokenLanguage(
    val ttsBcp47: String,
    val sttBcp47: String,
    val locale: Locale
) {
    ENGLISH("en-GB", "en-US", Locale.UK),
    GERMAN("de-DE", "de-DE", Locale.GERMANY);

    companion object {
        fun fromBcp47(tag: String?): SpokenLanguage? {
            val norm = tag?.trim()?.lowercase().orEmpty()
            return when {
                norm.startsWith("de") -> GERMAN
                norm.startsWith("en") -> ENGLISH
                else -> null
            }
        }
    }
}

/**
 * Fast, deterministic English vs German language detector combining Android's
 * live SpeechRecognizer language detection hint with lexical & diacritic scoring.
 */
object JarvisLanguageDetector {

    private val germanWords = setOf(
        "ich", "du", "er", "sie", "es", "wir", "ihr", "mir", "mich", "dir", "dich", "uns",
        "mein", "meine", "meinen", "meinem", "dein", "deine",
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer",
        "und", "oder", "aber", "nicht", "kein", "keine", "noch", "schon", "auch", "sehr", "bitte", "danke",
        "ist", "sind", "war", "bin", "bist", "habe", "hast", "hat", "haben", "kann", "kannst", "will", "möchte", "soll", "wird",
        "wie", "was", "wann", "wo", "wer", "warum", "wieso", "welche", "welcher", "welches",
        "auf", "an", "aus", "mit", "für", "fuer", "von", "zu", "zum", "zur", "nach", "über", "ueber", "unter", "bei", "im", "am", "ins",
        "öffne", "oeffne", "öffnen", "starte", "starten", "mach", "mache", "machen", "stell", "stelle", "stellen",
        "schalte", "schalt", "sende", "senden", "schreib", "schreibe", "schreiben", "ruf", "rufe", "anrufen",
        "zeig", "zeige", "zeigen", "lies", "vorlesen", "such", "suche", "suchen", "spiel", "spiele", "abspielen",
        "pausiere", "pausieren", "stopp", "stoppen", "weiter", "nächster", "naechster", "nächstes", "vorheriger", "letzter",
        "zurück", "zurueck", "geh", "gehe", "startbildschirm", "bildschirm", "sperren", "sperre",
        "taschenlampe", "licht", "lautstärke", "lautstaerke", "lauter", "leiser", "stumm", "lautlos", "vibrieren", "ton",
        "helligkeit", "automatische", "nicht", "stören", "stoeren", "wecker", "weck", "wecke", "akku", "akkustand", "batterie",
        "benachrichtigungen", "nachricht", "nachrichten", "minuten", "minute", "sekunden", "sekunde", "stunden", "stunde", "uhr",
        "prozent", "modus", "protokoll", "nachtprotokoll", "fokusmodus", "tarnmodus", "bildschirmfoto", "wetter", "zeit", "spät",
        "hallo", "guten", "morgen", "abend", "nacht", "ja", "nein", "bestätigen", "bestaetigen", "bestätige", "abbrechen",
        "schließen", "schliessen", "nichts", "vergiss", "eingeschaltet", "ausgeschaltet", "erledigt", "gestartet", "geöffnet",
        "verstanden", "gerne", "sofort", "aktuell", "heute", "morgen", "gestern", "etwas", "alles", "klar", "los", "bitte"
    )

    private val englishWords = setOf(
        "i", "you", "we", "they", "me", "my", "your", "our", "the", "a", "an", "this", "that", "these", "those",
        "and", "or", "but", "not", "no", "yes", "please", "thanks", "thank", "with", "for", "from", "to", "in", "on", "off", "at", "by", "of", "about",
        "is", "are", "was", "were", "am", "be", "have", "has", "do", "does", "can", "could", "would", "will", "should",
        "what", "how", "when", "where", "who", "why", "which",
        "open", "launch", "start", "close", "turn", "switch", "set", "make", "put", "send", "text", "write", "call", "dial",
        "show", "read", "check", "find", "search", "tell", "say", "play", "pause", "resume", "skip", "next", "previous", "stop",
        "go", "back", "home", "screen", "lock", "scroll", "tap", "click", "type", "take", "screenshot",
        "flashlight", "torch", "volume", "louder", "quieter", "softer", "mute", "unmute", "silent", "vibrate", "ringer",
        "brightness", "adaptive", "disturb", "timer", "alarm", "wake", "battery", "diagnostics", "notifications", "message", "chat",
        "minutes", "minute", "seconds", "second", "hours", "hour", "percent", "weather", "time", "today", "tomorrow",
        "hello", "good", "morning", "evening", "night", "confirm", "cancel", "abort", "dismiss", "never", "mind", "stand", "down",
        "anything", "else", "ready", "done", "launched", "opened", "enabled", "disabled", "engaged", "extinguished", "calibrated"
    )

    private val tokenRegex = Regex("[a-zA-ZäöüÄÖÜß]+")

    fun detect(
        text: String,
        sttHintTag: String? = null,
        mode: JarvisLanguageMode = JarvisLanguageMode.BILINGUAL_EN_DE,
        fallback: SpokenLanguage = SpokenLanguage.ENGLISH
    ): SpokenLanguage {
        when (mode) {
            JarvisLanguageMode.ENGLISH_ONLY -> return SpokenLanguage.ENGLISH
            JarvisLanguageMode.GERMAN_ONLY -> return SpokenLanguage.GERMAN
            JarvisLanguageMode.BILINGUAL_EN_DE -> Unit
        }

        val trimmed = text.trim()
        val hintLang = SpokenLanguage.fromBcp47(sttHintTag)
        if (trimmed.isEmpty()) return hintLang ?: fallback

        var deScore = 0
        var enScore = 0

        // German diacritics are an immediate high-confidence indicator
        if (trimmed.any { it in "äöüÄÖÜß" }) {
            deScore += 4
        }

        val tokens = tokenRegex.findAll(trimmed.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (tok in tokens) {
            // Note: intentionally exclude shared words like "jarvis", "sir", "whatsapp", "spotify", "timer", "ok", "okay"
            if (tok in germanWords) deScore += 2
            if (tok in englishWords) enScore += 2
        }

        if (hintLang == SpokenLanguage.GERMAN) deScore += 2
        if (hintLang == SpokenLanguage.ENGLISH) enScore += 2

        return when {
            deScore > enScore -> SpokenLanguage.GERMAN
            enScore > deScore -> SpokenLanguage.ENGLISH
            else -> hintLang ?: fallback
        }
    }

    fun detectReplyLanguage(
        replyText: String,
        fallback: SpokenLanguage
    ): SpokenLanguage = detect(
        text = replyText,
        sttHintTag = null,
        mode = JarvisLanguageMode.BILINGUAL_EN_DE,
        fallback = fallback
    )
}
