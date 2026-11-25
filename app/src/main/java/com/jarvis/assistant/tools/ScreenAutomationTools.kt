package com.jarvis.assistant.tools

import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.ScreenTreeSerializer
import kotlinx.coroutines.delay
import org.json.JSONObject

private suspend fun captureUpdatedScreenSuffix(service: JarvisAccessibilityService, waitMs: Long = 600L): String {
    delay(waitMs)
    val snap = service.captureScreenSnapshot() ?: return ""
    if (snap.nodes.isEmpty() && !snap.isSensitiveApp) return ""
    return "\n\nCURRENT SCREEN:\n${ScreenTreeSerializer.formatForLlm(snap)}"
}

class ReadScreenTool(
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "read_screen",
        description = "Inspect the current foreground screen UI tree (returns numbered interactive elements #1, #2... with labels and coordinates; passwords and banking apps are automatically redacted).",
        params = emptyList(),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val service = JarvisAccessibilityService.instance
            ?: return ToolExecutionOutput(
                false,
                "J.A.R.V.I.S. Phone Pilot (Accessibility Service) is offline. Enable it in Jarvis permissions."
            )
        prepareScreenForPilot()
        val snapshot = service.captureScreenSnapshot()
            ?: return ToolExecutionOutput(false, "No active UI window available to inspect right now.")
        val formatted = ScreenTreeSerializer.formatForLlm(snapshot)
        return ToolExecutionOutput(
            true,
            formatted,
            JSONObject()
                .put("package", snapshot.packageName)
                .put("node_count", snapshot.nodes.size)
                .put("sensitive_app", snapshot.isSensitiveApp)
        )
    }
}

class TapElementTool(
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "tap_element",
        description = "Tap a UI element on the current screen by its #node_id or visible text / content description. Automatically returns the updated CURRENT SCREEN tree.",
        params = listOf(
            ToolParam("node_id", "integer", "Element index number from the most recent CURRENT SCREEN output.", required = false),
            ToolParam("query", "string", "Visible text or button label to tap if node_id is omitted.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val service = JarvisAccessibilityService.instance
            ?: return ToolExecutionOutput(false, "Accessibility Service is offline. Enable it in Jarvis permissions.")
        prepareScreenForPilot()
        val nodeId = if (args.has("node_id") && !args.isNull("node_id")) args.optInt("node_id") else null
        val query = args.optString("query", "").takeIf { it.isNotBlank() }
        val (ok, msg) = service.tapElement(nodeId, query)
        val suffix = if (ok) captureUpdatedScreenSuffix(service) else ""
        return ToolExecutionOutput(ok, msg + suffix)
    }
}

class TapCoordinatesTool(
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "tap_coordinates",
        description = "Tap or long-press normalized screen coordinates (0.0 to 1.0 along width and height). Returns updated CURRENT SCREEN.",
        params = listOf(
            ToolParam("x_norm", "number", "Horizontal coordinate from 0.0 (left) to 1.0 (right).", required = true),
            ToolParam("y_norm", "number", "Vertical coordinate from 0.0 (top) to 1.0 (bottom).", required = true),
            ToolParam("long_press", "boolean", "If true, performs a 700ms long press.", required = false)
        ),
        defaultRisk = RiskLevel.SENSITIVE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val service = JarvisAccessibilityService.instance
            ?: return ToolExecutionOutput(false, "Accessibility Service is offline.")
        prepareScreenForPilot()
        val x = args.optDouble("x_norm", 0.5).toFloat()
        val y = args.optDouble("y_norm", 0.5).toFloat()
        val longPress = args.optBoolean("long_press", false)
        val (ok, msg) = service.tapNormalizedCoordinates(x, y, longPress)
        val suffix = if (ok) captureUpdatedScreenSuffix(service) else ""
        return ToolExecutionOutput(ok, msg + suffix)
    }
}

class TypeTextTool(
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "type_text",
        description = "Type text into a specified editable node_id or the currently focused input field. Returns updated CURRENT SCREEN.",
        params = listOf(
            ToolParam("text", "string", "The text to enter.", required = true),
            ToolParam("node_id", "integer", "Optional editable node_id from CURRENT SCREEN.", required = false),
            ToolParam("submit", "boolean", "If true, presses the IME enter/search key after typing.", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val service = JarvisAccessibilityService.instance
            ?: return ToolExecutionOutput(false, "Accessibility Service is offline.")
        prepareScreenForPilot()
        val text = args.optString("text", "")
        val nodeId = if (args.has("node_id") && !args.isNull("node_id")) args.optInt("node_id") else null
        val submit = args.optBoolean("submit", false)
        val (ok, msg) = service.typeText(nodeId, text, submit)
        val suffix = if (ok) captureUpdatedScreenSuffix(service, waitMs = 700L) else ""
        return ToolExecutionOutput(ok, msg + suffix)
    }
}

class ScrollScreenTool(
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "scroll_screen",
        description = "Scroll or swipe the current screen in the specified direction. Returns updated CURRENT SCREEN.",
        params = listOf(
            ToolParam("direction", "string", "Direction to move viewport content.", required = true, enumValues = listOf("down", "up", "left", "right"))
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val service = JarvisAccessibilityService.instance
            ?: return ToolExecutionOutput(false, "Accessibility Service is offline.")
        prepareScreenForPilot()
        val dir = args.optString("direction", "down")
        val (ok, msg) = service.scrollScreen(dir)
        val suffix = if (ok) captureUpdatedScreenSuffix(service, waitMs = 500L) else ""
        return ToolExecutionOutput(ok, msg + suffix)
    }
}

class GlobalActionTool(
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "global_action",
        description = "Perform an Android global navigation or system hardware action (home, back, recents, notifications, quick_settings, lock_screen, screenshot, power_dialog).",
        params = listOf(
            ToolParam(
                "action",
                "string",
                "Global system action name.",
                required = true,
                enumValues = listOf("home", "back", "recents", "notifications", "quick_settings", "lock_screen", "screenshot", "power_dialog")
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val service = JarvisAccessibilityService.instance
            ?: return ToolExecutionOutput(false, "Accessibility Service is offline. Enable Phone Pilot in Jarvis permissions.")
        prepareScreenForPilot()
        val action = args.optString("action", "home")
        val (ok, msg) = service.performGlobalActionByName(action)
        return ToolExecutionOutput(ok, msg)
    }
}

class WaitBrieflyTool : JarvisTool {
    override val spec = ToolSpec(
        name = "wait_briefly",
        description = "Pause briefly (250-3000ms) only if waiting for a slow network load.",
        params = listOf(
            ToolParam("milliseconds", "integer", "Duration to wait in milliseconds (default 900).", required = false)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val ms = args.optLong("milliseconds", 900L).coerceIn(250L, 3000L)
        delay(ms)
        return ToolExecutionOutput(true, "Waited ${ms}ms.")
    }
}
