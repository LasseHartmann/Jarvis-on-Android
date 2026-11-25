package com.jarvis.assistant

import com.jarvis.assistant.agent.JarvisState
import com.jarvis.assistant.assist.AmbientPillStateResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientPillStateTest {

    @Test
    fun hidesPillWhileFullScreenWakeOverlayIsVisible() {
        val snap = AmbientPillStateResolver.resolve(
            overlayVisible = true,
            orchestratorState = JarvisState.THINKING,
            statusDetail = "Neural cycle 1/10...",
            isListening = true,
            isSpeakingHeadless = true
        )
        assertFalse(snap.visible)
    }

    @Test
    fun showsDistinctPassiveStatesWhenOverlayHidden() {
        val listening = AmbientPillStateResolver.resolve(
            overlayVisible = false,
            orchestratorState = JarvisState.IDLE,
            statusDetail = "",
            isListening = true,
            isSpeakingHeadless = false
        )
        assertTrue(listening.visible)
        assertEquals(JarvisState.LISTENING, listening.state)
        assertEquals("Listening", listening.label)

        val thinking = AmbientPillStateResolver.resolve(
            overlayVisible = false,
            orchestratorState = JarvisState.THINKING,
            statusDetail = "Neural cycle 2/10...",
            isListening = false,
            isSpeakingHeadless = false
        )
        assertTrue(thinking.visible)
        assertEquals(JarvisState.THINKING, thinking.state)
        assertEquals("Thinking", thinking.label)

        val executing = AmbientPillStateResolver.resolve(
            overlayVisible = false,
            orchestratorState = JarvisState.EXECUTING,
            statusDetail = "Step 1 • open_app",
            isListening = false,
            isSpeakingHeadless = false
        )
        assertTrue(executing.visible)
        assertEquals(JarvisState.EXECUTING, executing.state)
        assertEquals("Opening app", executing.label)

        val tapping = AmbientPillStateResolver.resolve(
            overlayVisible = false,
            orchestratorState = JarvisState.EXECUTING,
            statusDetail = "Step 2 • Tapping Thomas",
            isListening = false,
            isSpeakingHeadless = false
        )
        assertEquals("Tapping Thomas", tapping.label)

        val openingNamed = AmbientPillStateResolver.resolve(
            overlayVisible = false,
            orchestratorState = JarvisState.EXECUTING,
            statusDetail = "Step 1 • Opening WhatsApp",
            isListening = false,
            isSpeakingHeadless = false
        )
        assertEquals("Opening WhatsApp", openingNamed.label)

        val idle = AmbientPillStateResolver.resolve(
            overlayVisible = false,
            orchestratorState = JarvisState.IDLE,
            statusDetail = "Standby",
            isListening = false,
            isSpeakingHeadless = false
        )
        assertFalse(idle.visible)
    }
}
