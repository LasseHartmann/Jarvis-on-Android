package com.jarvis.assistant

import com.jarvis.assistant.agent.GeminiLlmClient
import com.jarvis.assistant.agent.JarvisMemory
import com.jarvis.assistant.agent.JarvisProtocol
import com.jarvis.assistant.agent.JarvisReminder
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.LocalCommandRouter
import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.agent.WakeWordModel
import com.jarvis.assistant.assist.AmbientPillStateResolver
import com.jarvis.assistant.data.JarvisStore
import com.jarvis.assistant.device.DeviceTelemetry
import com.jarvis.assistant.tools.AlexaHelper
import com.jarvis.assistant.tools.CreateProtocolTool
import com.jarvis.assistant.tools.DeleteProtocolTool
import com.jarvis.assistant.tools.FetchWebpageTool
import com.jarvis.assistant.tools.GetWeatherTool
import com.jarvis.assistant.tools.IrProtocolEncoder
import com.jarvis.assistant.tools.ListRemindersTool
import com.jarvis.assistant.tools.SendWhatsAppTool
import com.jarvis.assistant.tools.TransitParser
import com.jarvis.assistant.voice.SpeakerVoiceprintEngine
import com.jarvis.assistant.widget.JarvisHudWidgetProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mark2SuperpowersTest {

    @Test
    fun whatsappPhoneNormalizationHandlesInternationalAndGermanNumbers() {
        assertEquals("4917612345678", SendWhatsAppTool.normalizeWhatsAppPhone("+49 176 1234-5678"))
        assertEquals("4917612345678", SendWhatsAppTool.normalizeWhatsAppPhone("0049 (176) 12345678"))
        assertEquals("4917612345678", SendWhatsAppTool.normalizeWhatsAppPhone("0176 12345678"))
        assertEquals("14155550199", SendWhatsAppTool.normalizeWhatsAppPhone("+1 (415) 555-0199"))
    }

    @Test
    fun htmlStrippingRemovesScriptsStylesAndEntities() {
        val html = """
            <html><head><style>body{color:red}</style><script>alert(1)</script></head>
            <body><h1>Munich&nbsp;Weather</h1><p>Sunny &amp; clear &quot;today&quot;.</p></body></html>
        """.trimIndent()
        val clean = FetchWebpageTool.stripHtmlToText(html)
        assertEquals("Munich Weather Sunny & clear \"today\".", clean)
        assertFalse(clean.contains("alert"))
        assertFalse(clean.contains("color:red"))
    }

    @Test
    fun wmoWeatherCodesMapToHumanConditions() {
        assertEquals("Clear sky", GetWeatherTool.wmoCondition(0))
        assertEquals("Partly cloudy", GetWeatherTool.wmoCondition(2))
        assertEquals("Rain", GetWeatherTool.wmoCondition(63))
        assertEquals("Thunderstorm", GetWeatherTool.wmoCondition(95))
    }

    @Test
    fun irEncodersGenerateValidAlternatingMicrosecondPatterns() {
        val samsung = IrProtocolEncoder.resolvePreset("samsung", "power")
        assertNotNull(samsung)
        assertEquals(38_000, samsung!!.frequencyHz)
        assertEquals(4500, samsung.patternUs[0])
        assertEquals(4500, samsung.patternUs[1])
        assertEquals(68, samsung.patternUs.size) // 2 leader + 64 data + 2 stop
        assertTrue(samsung.patternUs.all { it > 0 })

        val lg = IrProtocolEncoder.resolvePreset("lg", "mute")
        assertNotNull(lg)
        assertEquals(38_000, lg!!.frequencyHz)
        assertEquals(9000, lg.patternUs[0])
        assertEquals(4500, lg.patternUs[1])
        assertEquals(68, lg.patternUs.size)

        val sony = IrProtocolEncoder.resolvePreset("sony", "volume_up")
        assertNotNull(sony)
        assertEquals(40_000, sony!!.frequencyHz)
        assertEquals(2400, sony.patternUs[0])
        assertEquals(3 * 26, sony.patternUs.size) // 3 frames * (2 leader + 24 bit pulses)

        val philips = IrProtocolEncoder.resolvePreset("philips", "power")
        assertNotNull(philips)
        assertEquals(36_000, philips!!.frequencyHz)
        assertTrue(philips.patternUs.size >= 14)
        assertEquals(0, philips.patternUs.size % 2)
        assertTrue(philips.patternUs.all { it > 0 })

        val hisense = IrProtocolEncoder.resolvePreset("hisense", "volume_down")
        assertNotNull(hisense)
        val xiaomi = IrProtocolEncoder.resolvePreset("xiaomi", "home")
        assertNotNull(xiaomi)

        assertNull(IrProtocolEncoder.resolvePreset("unknown_brand", "power"))
        assertNotNull(IrProtocolEncoder.parseRawPattern("9000, 4500, 560, 560"))
        assertNull(IrProtocolEncoder.parseRawPattern("9000, -5, 560, 560"))
    }

    @Test
    fun geminiGroundingSourceExtractorDeduplicatesAndCapsToThree() {
        val body = """
            {
              "candidates": [{
                "content": { "parts": [{ "text": "Dieter Reiter is Mayor of Munich." }] },
                "groundingMetadata": {
                  "groundingChunks": [
                    { "web": { "title": "muenchen.de", "uri": "https://muenchen.de" } },
                    { "web": { "title": "Wikipedia", "uri": "https://en.wikipedia.org" } },
                    { "web": { "title": "muenchen.de", "uri": "https://muenchen.de/2" } },
                    { "web": { "title": "BR24", "uri": "https://br.de" } },
                    { "web": { "title": "SZ", "uri": "https://sz.de" } }
                  ]
                }
              }]
            }
        """.trimIndent()
        val client = GeminiLlmClient(apiKey = "test", model = "gemini-4-hydrogen")
        val sources = client.extractGroundingSources(body)
        assertEquals(listOf("muenchen.de", "Wikipedia", "BR24"), sources)
    }

    @Test
    fun customProtocolsAndRemindersToolsOperateAgainstStore() = runTest {
        val store = InMemoryMark2Store()
        val createProto = CreateProtocolTool(store)
        val deleteProto = DeleteProtocolTool(store)

        val created = createProto.execute(
            JSONObject()
                .put("name", "Gym Mode")
                .put("trigger_phrase", "gym mode")
                .put("description", "Volume 80%, DND on, play workout mix")
                .put("command_prompt", "Set media volume to 80%, turn on DND, and play workout mix on Spotify.")
        )
        assertTrue(created.success)
        assertEquals(1, store.getProtocolsSnapshot().size)
        assertEquals("gym mode", store.getProtocolsSnapshot().first().triggerPhrase)

        val deleted = deleteProto.execute(JSONObject().put("name", "gym mode"))
        assertTrue(deleted.success)
        assertTrue(store.getProtocolsSnapshot().isEmpty())

        store.addReminder("Morning Briefing", "Daily briefing", System.currentTimeMillis() + 60_000L, repeatDaily = true, isBriefing = true)
        val listTool = ListRemindersTool(store)
        val listOut = listTool.execute(JSONObject())
        assertTrue(listOut.success)
        assertTrue(listOut.message.contains("Morning Briefing"))
        assertTrue(listOut.message.contains("briefing"))
        assertTrue(listOut.message.contains("daily"))
    }

    @Test
    fun ambientPillHumanizesMark2ToolNames() {
        assertEquals("Inspecting screen", AmbientPillStateResolver.humanizeActionLabel("analyze_screen_vision"))
        assertEquals("Using camera", AmbientPillStateResolver.humanizeActionLabel("look_with_camera"))
        assertEquals("Searching web", AmbientPillStateResolver.humanizeActionLabel("live_web_answer"))
        assertEquals("Checking weather", AmbientPillStateResolver.humanizeActionLabel("get_weather"))
        assertEquals("WhatsApp", AmbientPillStateResolver.humanizeActionLabel("send_whatsapp"))
        assertEquals("Starting music", AmbientPillStateResolver.humanizeActionLabel("play_music"))
        assertEquals("IR blaster", AmbientPillStateResolver.humanizeActionLabel("control_ir_device"))
        assertEquals("Smart home", AmbientPillStateResolver.humanizeActionLabel("smart_home_control"))
        assertEquals("Amazon Alexa", AmbientPillStateResolver.humanizeActionLabel("alexa_trigger_routine"))
        assertEquals("PC bridge", AmbientPillStateResolver.humanizeActionLabel("pc_bridge_action"))
    }

    @Test
    fun localRouterDoesNotHijackPcUrlsOrAlexaCommands() {
        assertNull(LocalCommandRouter.match("open youtube.com on my pc"))
        assertNull(LocalCommandRouter.match("öffne youtube.com auf meinem pc"))
        assertNull(LocalCommandRouter.match("open youtube.com"))
        assertNull(LocalCommandRouter.match("open reddit.com"))
        assertNull(LocalCommandRouter.match("tell alexa to turn on the living room lights"))
        assertNull(LocalCommandRouter.match("turn off the samsung tv"))
        assertNull(LocalCommandRouter.match("lock my pc"))

        // Plain app launches still stay fast offline
        val spotify = LocalCommandRouter.match("open spotify")
        assertNotNull(spotify)
        assertEquals("open_app", spotify!!.toolCalls.first().name)
        assertEquals("spotify", spotify.toolCalls.first().arguments.getString("app_name"))

        val whatsapp = LocalCommandRouter.match("öffne whatsapp")
        assertNotNull(whatsapp)
        assertEquals("open_app", whatsapp!!.toolCalls.first().name)
    }

    @Test
    fun alexaHelpersFormatWebhooksAndVoiceMonkeyPayloads() {
        val url = AlexaHelper.resolveWebhookUrl("https://hook.example.com/trigger/{routine}", "movie night")
        assertEquals("https://hook.example.com/trigger/movie+night", url)

        val trig = AlexaHelper.buildVoiceMonkeyTriggerBody("tok123", "living-room-lights")
        assertEquals("tok123", trig.getString("token"))
        assertEquals("living-room-lights", trig.getString("device"))

        val v3 = AlexaHelper.buildVoiceMonkeyAnnounceBody("tok123", "kitchen-echo", "Dinner is ready", v2 = false)
        assertEquals("Dinner is ready", v3.getString("speech"))
        assertFalse(v3.has("text"))

        val v2 = AlexaHelper.buildVoiceMonkeyAnnounceBody("tok123", "kitchen-echo", "Dinner is ready", v2 = true)
        assertEquals("Dinner is ready", v2.getString("text"))
        assertFalse(v2.has("speech"))
    }

    @Test
    fun speakerVoiceprintMathAndSerializationWorkAccurately() {
        val v1 = SpeakerVoiceprintEngine.l2Normalize(FloatArray(192) { if (it % 2 == 0) 1f else 0.1f })
        val v2 = SpeakerVoiceprintEngine.l2Normalize(FloatArray(192) { if (it % 2 == 0) 0.95f else 0.15f })
        val other = SpeakerVoiceprintEngine.l2Normalize(FloatArray(192) { if (it % 2 == 1) 1f else -0.2f })

        val same = SpeakerVoiceprintEngine.matchScore(v2, listOf(v1))
        val diff = SpeakerVoiceprintEngine.matchScore(other, listOf(v1))
        assertTrue("Expected high same-speaker match, got $same", same > 0.95f)
        assertTrue("Expected low different-speaker match, got $diff", diff < 0.25f)

        val serialized = SpeakerVoiceprintEngine.serializeVoiceprints(listOf(v1, v2))
        val parsed = SpeakerVoiceprintEngine.parseVoiceprints(serialized)
        assertEquals(2, parsed.size)
        assertEquals(192, parsed[0].size)

        val filters = SpeakerVoiceprintEngine.buildSlaneyMelFilterbank(16_000, 512, 64, 0.0, 8000.0)
        assertEquals(64, filters.size)
        assertEquals(257, filters[0].size)
        assertTrue(filters.all { row -> row.any { it > 0f } })
    }

    @Test
    fun transitParsersFormatMvgAndDbResponsesCleanly() {
        val mvgDeps = """
            [{"plannedDepartureTime":1795811940000,"realtimeDepartureTime":1795812180000,"delayInMinutes":4,"label":"U6","destination":"Goetheplatz","platform":2,"cancelled":false}]
        """.trimIndent()
        val depSummary = TransitParser.formatMvgDepartures("Marienplatz", mvgDeps)
        assertTrue(depSummary.contains("U6 -> Goetheplatz"))
        assertTrue(depSummary.contains("+4m delay"))
        assertTrue(depSummary.contains("Pl. 2"))

        val dbDeps = """
            {"departures":[{"train":"ICE 528","destination":"Frankfurt(Main)Hbf","scheduledDeparture":"14:15","delayDeparture":0,"platform":"18","isCancelled":0}]}
        """.trimIndent()
        val dbSummary = TransitParser.formatDbDepartures("München Hbf", dbDeps)
        assertTrue(dbSummary.contains("ICE 528 -> Frankfurt(Main)Hbf"))
        assertTrue(dbSummary.contains("Pl. 18"))

        val parsedClock = TransitParser.parseClockOrIsoTimeMs("18:30", nowMs = 1_700_000_000_000L)
        assertNotNull(parsedClock)
        assertEquals("Checking transit", AmbientPillStateResolver.humanizeActionLabel("get_public_transit"))
    }

    @Test
    fun amoledWidgetFormattersReflectTelemetryWakeWordAndReminders() {
        val s = JarvisSettings(
            wakeWordEnabled = true,
            wakeWordModel = WakeWordModel.COMPUTER,
            pcBridgeUrl = "http://192.168.1.5:8765",
            pcBridgeToken = "tok"
        )
        val t = DeviceTelemetry(batteryPercent = 88, isCharging = true, ramUsedPercent = 42, accessibilityEnabled = true)
        val badge = JarvisHudWidgetProvider.formatStatusBadge(s, t)
        assertTrue(badge.contains("88%"))
        assertTrue(badge.contains("PC"))
        assertEquals("Say \"Computer\"", JarvisHudWidgetProvider.formatWakeLine(s))
        assertTrue(JarvisHudWidgetProvider.formatReminderOrTelemetryLine(null, t).contains("RAM 42%"))
    }

    private class InMemoryMark2Store : JarvisStore {
        private val protocols = mutableListOf<JarvisProtocol>()
        private val reminders = mutableListOf<JarvisReminder>()
        private var nextId = 1L

        override fun getMemoriesSnapshot(): List<JarvisMemory> = emptyList()
        override fun upsertMemory(key: String, content: String, category: String) = Unit
        override fun deleteMemoryByKey(key: String): Boolean = false
        override fun getProtocolsSnapshot(): List<JarvisProtocol> = protocols.toList()
        override fun addCustomProtocol(name: String, triggerPhrase: String, description: String, commandPrompt: String) {
            protocols.removeAll { it.triggerPhrase.equals(triggerPhrase, ignoreCase = true) }
            protocols.add(JarvisProtocol(nextId++, name, triggerPhrase.lowercase(), description, commandPrompt, isBuiltIn = false))
        }

        override fun deleteProtocolByName(nameOrTrigger: String): Boolean =
            protocols.removeAll {
                !it.isBuiltIn && (it.name.equals(nameOrTrigger, ignoreCase = true) || it.triggerPhrase.equals(nameOrTrigger, ignoreCase = true))
            }

        override fun getRemindersSnapshot(): List<JarvisReminder> = reminders.filter { it.active }
        override fun addReminder(title: String, message: String, triggerAtMs: Long, repeatDaily: Boolean, isBriefing: Boolean): Long {
            val id = nextId++
            reminders.add(JarvisReminder(id, title, message, triggerAtMs, repeatDaily, isBriefing, active = true))
            return id
        }

        override fun deleteReminder(id: Long): Boolean = reminders.removeAll { it.id == id }
        override fun logAudit(toolName: String, argumentsJson: String, riskLevel: RiskLevel, approved: Boolean, success: Boolean, summary: String) = Unit
    }
}
