package com.jarvis.assistant.voice

import java.util.Locale

/**
 * Buffers streaming LLM text deltas and emits speakable sentences as soon as a clean
 * sentence boundary arrives — avoiding splits inside abbreviations like "J.A.R.V.I.S.",
 * "Mr.", "Dr.", "z.B.", decimal numbers ("3.5"), or version tags ("v1.2").
 */
class SentenceChunker(
    private val minSentenceChars: Int = 22
) {
    private val buffer = StringBuilder()

    fun append(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        return extractReadySentences()
    }

    fun flush(): String? {
        val rem = cleanSpeechChunk(buffer.toString())
        buffer.setLength(0)
        return rem.takeIf { it.isNotBlank() }
    }

    private fun extractReadySentences(): List<String> {
        val emitted = mutableListOf<String>()
        while (true) {
            val splitIdx = findSplitIndex(buffer)
            if (splitIdx < 0) break
            val candidate = cleanSpeechChunk(buffer.substring(0, splitIdx + 1))
            buffer.delete(0, splitIdx + 1)
            while (buffer.isNotEmpty() && buffer[0].isWhitespace()) {
                buffer.deleteCharAt(0)
            }
            if (candidate.isNotBlank()) {
                emitted.add(candidate)
            }
        }
        return emitted
    }

    private fun findSplitIndex(sb: CharSequence): Int {
        val len = sb.length
        for (i in 0 until len) {
            val ch = sb[i]
            if (ch == '\n' && i >= minSentenceChars) {
                return i
            }
            if (ch != '.' && ch != '!' && ch != '?') continue
            // Require whitespace after punctuation so we never split mid-abbreviation or mid-token
            if (i + 1 >= len || !sb[i + 1].isWhitespace()) continue

            if (ch == '.' && isProtectedDot(sb, i)) continue

            val trimmedSoFar = sb.subSequence(0, i + 1).toString().trim()
            if (trimmedSoFar.length >= minSentenceChars) {
                return i
            }
        }
        return -1
    }

    private fun isProtectedDot(sb: CharSequence, dotIdx: Int): Boolean {
        // Decimal or numbered list like "3.14" or "1."
        if (dotIdx > 0 && sb[dotIdx - 1].isDigit()) {
            // Protect single-digit list markers ("1. ") or version/decimal numbers
            val before = sb.subSequence(0, dotIdx).toString().trimEnd()
            val lastTok = before.substringAfterLast(' ').substringAfterLast('\n')
            if (lastTok.all { it.isDigit() } && lastTok.length <= 2) return true
        }

        // Single-letter dotted acronyms like "J.A.R.V.I.S." or "U.S."
        if (dotIdx >= 1 && sb[dotIdx - 1].isLetter()) {
            val start = findWordStart(sb, dotIdx)
            val word = sb.subSequence(start, dotIdx + 1).toString()
            if (word.count { it == '.' } >= 2) return true
            if (word.length == 2 && word[0].isUpperCase()) return true // e.g. "A."
            val lower = word.lowercase(Locale.ROOT)
            if (lower in protectedAbbreviations) return true
        }
        return false
    }

    private fun findWordStart(sb: CharSequence, idx: Int): Int {
        var i = idx
        while (i > 0 && !sb[i - 1].isWhitespace()) {
            i--
        }
        return i
    }

    private fun cleanSpeechChunk(raw: String): String =
        raw.replace(Regex("[*`#_~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        private val protectedAbbreviations = setOf(
            "mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "jr.", "sr.", "vs.", "etc.", "e.g.", "i.e.",
            "z.b.", "d.h.", "u.a.", "ca.", "nr.", "bzw.", "usw.", "inkl.", "max.", "min."
        )
    }
}
