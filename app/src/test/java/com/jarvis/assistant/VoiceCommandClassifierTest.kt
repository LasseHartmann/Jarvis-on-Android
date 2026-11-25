package com.jarvis.assistant

import com.jarvis.assistant.assist.VoiceCommandClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandClassifierTest {

    @Test
    fun recognizesDismissPhrases() {
        assertTrue(VoiceCommandClassifier.isDismissCommand("Stand down"))
        assertTrue(VoiceCommandClassifier.isDismissCommand("Hey Jarvis, never mind"))
        assertTrue(VoiceCommandClassifier.isDismissCommand("Power down."))
        assertFalse(VoiceCommandClassifier.isDismissCommand("Open Spotify"))
    }

    @Test
    fun parsesVoiceConfirmationsSafely() {
        assertEquals(true, VoiceCommandClassifier.parseConfirmation("Confirm"))
        assertEquals(true, VoiceCommandClassifier.parseConfirmation("Yes, go ahead"))
        assertEquals(true, VoiceCommandClassifier.parseConfirmation("Ja"))
        assertEquals(false, VoiceCommandClassifier.parseConfirmation("Cancel"))
        assertEquals(false, VoiceCommandClassifier.parseConfirmation("No, stop"))
        assertEquals(false, VoiceCommandClassifier.parseConfirmation("Nein abbrechen"))
        assertNull(VoiceCommandClassifier.parseConfirmation("what time is it"))
    }

    @Test
    fun formatsConciseInstantConfirmationPrompts() {
        assertEquals(
            "Confirm tapping Send, Sir?",
            com.jarvis.assistant.assist.JarvisTaskCoordinator.formatConfirmationPrompt("Tap 'Send'", "Sir")
        )
        assertEquals(
            "Confirm tapping Confirm Payment, Sir?",
            com.jarvis.assistant.assist.JarvisTaskCoordinator.formatConfirmationPrompt("Tap 'Confirm Payment'", "Sir")
        )
        assertEquals(
            "Confirm calling +491234, Sir?",
            com.jarvis.assistant.assist.JarvisTaskCoordinator.formatConfirmationPrompt("Dial +491234", "Sir")
        )
    }
}
