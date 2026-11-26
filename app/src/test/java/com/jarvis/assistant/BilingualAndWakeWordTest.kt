package com.jarvis.assistant

import com.jarvis.assistant.agent.JarvisLanguageDetector
import com.jarvis.assistant.agent.JarvisLanguageMode
import com.jarvis.assistant.agent.JarvisPersona
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.LocalCommandRouter
import com.jarvis.assistant.agent.SpokenLanguage
import com.jarvis.assistant.agent.ToolResult
import com.jarvis.assistant.assist.JarvisTaskCoordinator
import com.jarvis.assistant.voice.WakeWordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilingualAndWakeWordTest {

    @Test
    fun detectsGermanAndEnglishCommandsAccurately() {
        assertEquals(SpokenLanguage.GERMAN, JarvisLanguageDetector.detect("Öffne WhatsApp und schreib Thomas"))
        assertEquals(SpokenLanguage.GERMAN, JarvisLanguageDetector.detect("Mach die Taschenlampe an"))
        assertEquals(SpokenLanguage.GERMAN, JarvisLanguageDetector.detect("Wie viel Akku habe ich?"))
        assertEquals(SpokenLanguage.ENGLISH, JarvisLanguageDetector.detect("Open WhatsApp and open the chat with Thomas"))
        assertEquals(SpokenLanguage.ENGLISH, JarvisLanguageDetector.detect("Turn on the flashlight please"))

        // Ambiguous single app name follows STT hint
        assertEquals(SpokenLanguage.GERMAN, JarvisLanguageDetector.detect("Spotify", sttHintTag = "de-DE"))
        assertEquals(SpokenLanguage.ENGLISH, JarvisLanguageDetector.detect("Spotify", sttHintTag = "en-US"))

        // Forced language modes override text
        assertEquals(
            SpokenLanguage.GERMAN,
            JarvisLanguageDetector.detect("Hello world", mode = JarvisLanguageMode.GERMAN_ONLY)
        )
        assertEquals(
            SpokenLanguage.ENGLISH,
            JarvisLanguageDetector.detect("Guten Morgen", mode = JarvisLanguageMode.ENGLISH_ONLY)
        )

        // Reply containing "Sir" in German stays German
        assertEquals(
            SpokenLanguage.GERMAN,
            JarvisLanguageDetector.detectReplyLanguage("Taschenlampe eingeschaltet, Sir.", fallback = SpokenLanguage.GERMAN)
        )
    }

    @Test
    fun localCommandRouterHandlesGermanCommandsAndRepliesInGerman() {
        val torch = LocalCommandRouter.match("Mach die Taschenlampe an")
        assertNotNull(torch)
        assertEquals("set_flashlight", torch!!.toolCalls.first().name)
        assertTrue(torch.toolCalls.first().arguments.getBoolean("enabled"))
        assertEquals(
            "Taschenlampe eingeschaltet, Sir.",
            torch.composeReply(listOf(ToolResult("1", "set_flashlight", true, "ok")), "Sir")
        )

        val vol = LocalCommandRouter.match("Stelle die Lautstärke auf 60 Prozent")
        assertNotNull(vol)
        assertEquals("set_volume", vol!!.toolCalls.first().name)
        assertEquals(60, vol.toolCalls.first().arguments.getInt("percent"))

        val alarm = LocalCommandRouter.match("Weck mich um 7:30 Uhr")
        assertNotNull(alarm)
        assertEquals("set_alarm", alarm!!.toolCalls.first().name)
        assertEquals(7, alarm.toolCalls.first().arguments.getInt("hour"))
        assertEquals(30, alarm.toolCalls.first().arguments.getInt("minute"))

        val timer = LocalCommandRouter.match("Stell einen Timer auf 5 Minuten")
        assertNotNull(timer)
        assertEquals(300, timer!!.toolCalls.first().arguments.getInt("seconds"))

        val openApp = LocalCommandRouter.match("Öffne WhatsApp")
        assertNotNull(openApp)
        assertEquals("whatsapp", openApp!!.toolCalls.first().arguments.getString("app_name"))
        assertEquals(
            "WhatsApp geöffnet, Sir.",
            openApp.composeReply(listOf(ToolResult("1", "open_app", true, "Launched WhatsApp (com.whatsapp).\n\nCURRENT SCREEN:\n...")), "Sir")
        )

        // Multi-clause German command must escalate to cloud LLM
        assertNull(LocalCommandRouter.match("Öffne WhatsApp und öffne den Chat mit Thomas"))
        assertNull(LocalCommandRouter.match("Starte Spotify und dann mach lauter"))
    }

    @Test
    fun wakeWordDetectorMatchesStandaloneOneShotAndRejectsFalsePositives() {
        val standalone = WakeWordDetector.parse("Jarvis")
        assertTrue(standalone.matched)
        assertEquals("", standalone.trailingCommand)

        val heyDe = WakeWordDetector.parse("Hey Jarvis, öffne WhatsApp")
        assertTrue(heyDe.matched)
        assertEquals("öffne WhatsApp", heyDe.trailingCommand)

        val oneShotEn = WakeWordDetector.parse("Jarvis turn on the flashlight")
        assertTrue(oneShotEn.matched)
        assertEquals("turn on the flashlight", oneShotEn.trailingCommand)

        val phoneticDe = WakeWordDetector.parse("Hallo Djarvis wie spät ist es")
        assertTrue(phoneticDe.matched)
        assertEquals("wie spät ist es", phoneticDe.trailingCommand)

        val fillerOnly = WakeWordDetector.parse("Hey Jarvis, bitte")
        assertTrue(fillerOnly.matched)
        assertEquals("", fillerOnly.trailingCommand)

        // Non-matches must never false-trigger
        assertFalse(WakeWordDetector.containsWakeWord("Open customer service"))
        assertFalse(WakeWordDetector.containsWakeWord("Travis went to the harvest festival"))
        assertFalse(WakeWordDetector.containsWakeWord("Mach das Licht an"))
    }

    @Test
    fun confirmationPromptAndPersonaSupportGermanAndEnglish() {
        assertEquals(
            "Soll ich Senden antippen, Sir?",
            JarvisTaskCoordinator.formatConfirmationPrompt("Tap 'Senden'", "Sir", SpokenLanguage.GERMAN)
        )
        assertEquals(
            "Confirm tapping Send, Sir?",
            JarvisTaskCoordinator.formatConfirmationPrompt("Tap 'Send'", "Sir", SpokenLanguage.ENGLISH)
        )

        val promptDe = JarvisPersona.buildSystemPrompt(
            settings = JarvisSettings(languageMode = JarvisLanguageMode.BILINGUAL_EN_DE),
            telemetryContext = "Battery 90%",
            memories = emptyList(),
            replyLanguage = SpokenLanguage.GERMAN
        )
        assertTrue(promptDe.contains("German (Deutsch)"))
        assertTrue(promptDe.contains("English and German"))
    }
}
