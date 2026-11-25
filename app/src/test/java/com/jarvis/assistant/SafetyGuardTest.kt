package com.jarvis.assistant

import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.agent.SafetyGuard
import com.jarvis.assistant.agent.SafetyMode
import com.jarvis.assistant.agent.ToolCall
import com.jarvis.assistant.services.UiNodeSnapshot
import com.jarvis.assistant.tools.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGuardTest {

    private val tapSpec = ToolSpec(name = "tap_element", description = "Tap UI", defaultRisk = RiskLevel.SAFE)
    private val smsSpec = ToolSpec(name = "send_sms", description = "Send SMS", defaultRisk = RiskLevel.SENSITIVE)

    @Test
    fun balancedModeAutoRunsSafeAndConfirmsSensitive() {
        val safeTap = ToolCall("1", "tap_element", JSONObject().put("query", "Settings"))
        val safeAssess = SafetyGuard.assess(safeTap, tapSpec, SafetyMode.BALANCED, "com.android.launcher", emptyList())
        assertEquals(RiskLevel.SAFE, safeAssess.riskLevel)
        assertFalse(safeAssess.requiresConfirmation)

        val smsCall = ToolCall("2", "send_sms", JSONObject().put("phone_number", "+491234").put("message", "Hi"))
        val smsAssess = SafetyGuard.assess(smsCall, smsSpec, SafetyMode.BALANCED, "com.jarvis.assistant", emptyList())
        assertEquals(RiskLevel.SENSITIVE, smsAssess.riskLevel)
        assertTrue(smsAssess.requiresConfirmation)
    }

    @Test
    fun bankingAppElevatesAnyScreenTapToCriticalEvenInAutonomousMode() {
        val call = ToolCall("3", "tap_element", JSONObject().put("query", "Continue"))
        val assess = SafetyGuard.assess(call, tapSpec, SafetyMode.AUTONOMOUS, "com.paypal.android.p2pmobile", emptyList())
        assertEquals(RiskLevel.CRITICAL, assess.riskLevel)
        assertTrue(assess.requiresConfirmation)
    }

    @Test
    fun payOrDeleteButtonNodeElevatesToCritical() {
        val nodes = listOf(
            UiNodeSnapshot(
                index = 9,
                text = "Confirm Payment",
                contentDescription = "",
                className = "android.widget.Button",
                viewId = "btn_pay",
                left = 0,
                top = 0,
                right = 100,
                bottom = 50,
                clickable = true,
                editable = false,
                scrollable = false,
                checked = null,
                focused = false,
                isPassword = false
            )
        )
        val call = ToolCall("4", "tap_element", JSONObject().put("node_id", 9))
        val assess = SafetyGuard.assess(call, tapSpec, SafetyMode.AUTONOMOUS, "com.shop.app", nodes)
        assertEquals(RiskLevel.CRITICAL, assess.riskLevel)
        assertTrue(assess.requiresConfirmation)
    }

    @Test
    fun strictModeConfirmsEvenSafeTaps() {
        val call = ToolCall("5", "tap_element", JSONObject().put("query", "Home"))
        val assess = SafetyGuard.assess(call, tapSpec, SafetyMode.STRICT, "com.spotify.music", emptyList())
        assertTrue(assess.requiresConfirmation)
    }

    @Test
    fun autonomousIsDefaultAndAutoRunsStandardSendTaps() {
        assertEquals(SafetyMode.AUTONOMOUS, com.jarvis.assistant.agent.JarvisSettings().safetyMode)
        val sendTap = ToolCall("6", "tap_element", JSONObject().put("query", "Send"))
        val assess = SafetyGuard.assess(sendTap, tapSpec, SafetyMode.AUTONOMOUS, "com.whatsapp", emptyList())
        assertEquals(RiskLevel.SENSITIVE, assess.riskLevel)
        assertFalse(assess.requiresConfirmation)
    }
}
