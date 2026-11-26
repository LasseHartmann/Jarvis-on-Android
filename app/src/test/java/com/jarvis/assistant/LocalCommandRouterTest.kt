package com.jarvis.assistant

import com.jarvis.assistant.agent.LocalCommandRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandRouterTest {

    @Test
    fun flashlightCommandsEnglishAndGerman() {
        val onEn = LocalCommandRouter.match("Hey Jarvis, turn on the flashlight")
        assertNotNull(onEn)
        assertEquals("set_flashlight", onEn!!.toolCalls.first().name)
        assertTrue(onEn.toolCalls.first().arguments.getBoolean("enabled"))

        val offDe = LocalCommandRouter.match("Taschenlampe aus")
        assertNotNull(offDe)
        assertEquals("set_flashlight", offDe!!.toolCalls.first().name)
        assertEquals(false, offDe.toolCalls.first().arguments.getBoolean("enabled"))
    }

    @Test
    fun volumeAndBrightnessParsing() {
        val vol = LocalCommandRouter.match("set media volume to 65%")
        assertNotNull(vol)
        assertEquals("set_volume", vol!!.toolCalls.first().name)
        assertEquals("media", vol.toolCalls.first().arguments.getString("stream"))
        assertEquals(65, vol.toolCalls.first().arguments.getInt("percent"))

        val bright = LocalCommandRouter.match("brightness 85%")
        assertNotNull(bright)
        assertEquals("set_brightness", bright!!.toolCalls.first().name)
        assertEquals(85, bright.toolCalls.first().arguments.getInt("percent"))
    }

    @Test
    fun timerAndAlarmParsing() {
        val timer = LocalCommandRouter.match("set a timer for 5 minutes")
        assertNotNull(timer)
        assertEquals("set_timer", timer!!.toolCalls.first().name)
        assertEquals(300, timer.toolCalls.first().arguments.getInt("seconds"))

        val alarm = LocalCommandRouter.match("wake me up at 7:30 am")
        assertNotNull(alarm)
        assertEquals("set_alarm", alarm!!.toolCalls.first().name)
        assertEquals(7, alarm.toolCalls.first().arguments.getInt("hour"))
        assertEquals(30, alarm.toolCalls.first().arguments.getInt("minute"))
    }

    @Test
    fun openAppAndNightProtocol() {
        val app = LocalCommandRouter.match("Jarvis open Spotify")
        assertNotNull(app)
        assertEquals("open_app", app!!.toolCalls.first().name)
        assertEquals("spotify", app.toolCalls.first().arguments.getString("app_name"))

        val night = LocalCommandRouter.match("Night Protocol")
        assertNotNull(night)
        assertEquals(4, night!!.toolCalls.size)
        assertEquals("set_brightness", night.toolCalls[0].name)
        assertEquals("set_dnd", night.toolCalls[2].name)
    }

    @Test
    fun complexMultiStepRequestEscalatesToLlm() {
        val plan = LocalCommandRouter.match("Open WhatsApp and tell Alex I will be 10 minutes late")
        assertNull(plan)
    }

    @Test
    fun openAppLocalReplyStripsCurrentScreenTree() {
        val plan = LocalCommandRouter.match("Open WhatsApp")
        assertNotNull(plan)
        val rawToolResult = com.jarvis.assistant.agent.ToolResult(
            toolCallId = "1",
            toolName = "open_app",
            success = true,
            output = "Launched WhatsApp (com.whatsapp).\n\nCURRENT SCREEN:\nAPP: com.whatsapp\n#1 [Text] \"Thomas\""
        )
        val spoken = plan!!.composeReply(listOf(rawToolResult), "Sir")
        assertEquals("Launched WhatsApp (com.whatsapp). At your service, Sir.", spoken)
    }

    @Test
    fun openUrlPreservesNavigationSchemesAndDetectsMapsLinks() {
        assertEquals(
            "google.navigation:q=Olympiaturm",
            com.jarvis.assistant.tools.OpenUrlTool.normalizeUriString("google.navigation:q=Olympiaturm")
        )
        assertEquals(
            "https://example.com",
            com.jarvis.assistant.tools.OpenUrlTool.normalizeUriString("example.com")
        )
        assertTrue(com.jarvis.assistant.tools.OpenUrlTool.shouldRouteToGoogleMaps("google.navigation:q=Olympiaturm"))
        assertTrue(com.jarvis.assistant.tools.OpenUrlTool.shouldRouteToGoogleMaps("https://www.google.com/maps/dir/?api=1&destination=Munich"))
        assertEquals(false, com.jarvis.assistant.tools.OpenUrlTool.shouldRouteToGoogleMaps("https://wikipedia.org"))
    }
}
