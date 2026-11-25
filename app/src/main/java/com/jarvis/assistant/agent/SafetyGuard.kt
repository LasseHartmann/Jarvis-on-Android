package com.jarvis.assistant.agent

import com.jarvis.assistant.services.ScreenTreeSerializer
import com.jarvis.assistant.services.UiNodeSnapshot
import com.jarvis.assistant.tools.ToolSpec

data class SafetyAssessment(
    val riskLevel: RiskLevel,
    val requiresConfirmation: Boolean,
    val title: String,
    val detail: String,
    val reason: String
)

object SafetyGuard {

    private val criticalUiKeywords = listOf(
        "pay", "purchase", "buy now", "checkout", "transfer", "wire", "confirm payment",
        "delete", "erase", "remove permanently", "uninstall", "factory reset", "format",
        "authorize", "approve transaction", "place order"
    )

    private val sensitiveUiKeywords = listOf(
        "send", "post", "publish", "tweet", "share now", "call", "dial", "submit"
    )

    private val screenMutationTools = setOf("tap_element", "tap_coordinates", "type_text")

    fun assess(
        call: ToolCall,
        spec: ToolSpec?,
        safetyMode: SafetyMode,
        foregroundPackage: String,
        lastNodes: List<UiNodeSnapshot>
    ): SafetyAssessment {
        var risk = spec?.defaultRisk ?: RiskLevel.SENSITIVE
        var reason = when (risk) {
            RiskLevel.SAFE -> "Standard device action."
            RiskLevel.SENSITIVE -> "Action performs outbound communication or system policy change."
            RiskLevel.CRITICAL -> "High-impact action requiring explicit confirmation."
        }

        // Security shield for financial/password apps
        if (call.name in screenMutationTools && ScreenTreeSerializer.isSensitivePackage(foregroundPackage)) {
            risk = RiskLevel.CRITICAL
            reason = "Foreground app '$foregroundPackage' is protected under the Stark Security Shield."
        }

        // Inspect tap targets for destructive/payment/outbound labels
        if (call.name == "tap_element") {
            val targetLabel = resolveTargetLabel(call, lastNodes).lowercase()
            if (targetLabel.isNotBlank()) {
                when {
                    criticalUiKeywords.any { targetLabel.contains(it) } -> {
                        risk = RiskLevel.CRITICAL
                        reason = "Target UI button ('$targetLabel') appears financial or destructive."
                    }

                    sensitiveUiKeywords.any { targetLabel.contains(it) } && risk == RiskLevel.SAFE -> {
                        risk = RiskLevel.SENSITIVE
                        reason = "Target UI button ('$targetLabel') may transmit or publish data."
                    }
                }
            }
        }

        if (call.name == "global_action" && call.arguments.optString("action").lowercase() in setOf("power_dialog")) {
            risk = RiskLevel.SENSITIVE
            reason = "System power menu interaction."
        }

        val requiresConfirmation = when (safetyMode) {
            SafetyMode.STRICT -> risk != RiskLevel.SAFE || call.name in screenMutationTools
            SafetyMode.BALANCED -> risk == RiskLevel.SENSITIVE || risk == RiskLevel.CRITICAL
            SafetyMode.AUTONOMOUS -> risk == RiskLevel.CRITICAL
        }

        val title = formatActionTitle(call)
        val detail = formatActionDetail(call, lastNodes)

        return SafetyAssessment(
            riskLevel = risk,
            requiresConfirmation = requiresConfirmation,
            title = title,
            detail = detail,
            reason = reason
        )
    }

    private fun resolveTargetLabel(call: ToolCall, lastNodes: List<UiNodeSnapshot>): String {
        val query = call.arguments.optString("query", "").trim()
        if (query.isNotBlank()) return query
        val nodeId = if (call.arguments.has("node_id") && !call.arguments.isNull("node_id")) {
            call.arguments.optInt("node_id", -1)
        } else -1
        if (nodeId > 0) {
            val node = lastNodes.firstOrNull { it.index == nodeId }
            if (node != null) return node.primaryLabel
        }
        return ""
    }

    private fun formatActionTitle(call: ToolCall): String = when (call.name) {
        "send_sms" -> "Authorize Outbound SMS"
        "make_call" -> "Authorize Phone Call"
        "reply_notification" -> "Authorize Notification Reply"
        "set_dnd" -> "Authorize Do Not Disturb Change"
        "tap_element", "tap_coordinates" -> "Authorize Screen Interaction"
        "type_text" -> "Authorize Text Entry"
        "create_calendar_event" -> "Authorize Calendar Entry"
        else -> "Authorize ${call.name.replace('_', ' ').replaceFirstChar { it.uppercase() }}"
    }

    private fun formatActionDetail(call: ToolCall, lastNodes: List<UiNodeSnapshot>): String = when (call.name) {
        "send_sms" -> "To ${call.arguments.optString("phone_number")}: \"${call.arguments.optString("message")}\""
        "make_call" -> "Dial ${call.arguments.optString("phone_number")}"
        "reply_notification" -> "Reply: \"${call.arguments.optString("reply_text")}\""
        "set_dnd" -> if (call.arguments.optBoolean("enabled")) "Enable Do Not Disturb" else "Disable Do Not Disturb"
        "tap_element" -> {
            val label = resolveTargetLabel(call, lastNodes)
            if (label.isNotBlank()) "Tap '$label'" else "Tap node #${call.arguments.optInt("node_id")}"
        }

        else -> call.arguments.toString().take(180)
    }
}
