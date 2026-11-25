package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.agent.ToolCall
import com.jarvis.assistant.agent.ToolResult
import com.jarvis.assistant.data.JarvisStore
import com.jarvis.assistant.device.DeviceTelemetryMonitor
import com.jarvis.assistant.device.InstalledAppsRepository

interface ToolExecutor {
    fun allSpecs(): List<ToolSpec>
    fun findSpec(name: String): ToolSpec?
    suspend fun execute(call: ToolCall): ToolResult
}

class ToolRegistry(
    context: Context,
    appsRepo: InstalledAppsRepository,
    telemetryMonitor: DeviceTelemetryMonitor,
    store: JarvisStore
) : ToolExecutor {

    private val tools: Map<String, JarvisTool>

    init {
        val list: List<JarvisTool> = listOf(
            // System Hardware & OS Controls
            SetFlashlightTool(context),
            SetVolumeTool(context),
            SetRingerModeTool(context),
            SetBrightnessTool(context),
            SetDndTool(context),
            MediaControlTool(context),
            OpenSystemPanelTool(context),
            // App & Web Navigation
            ListInstalledAppsTool(appsRepo),
            OpenAppTool(appsRepo),
            OpenUrlTool(context),
            WebSearchTool(context),
            // Accessibility Screen Pilot
            ReadScreenTool(),
            TapElementTool(),
            TapCoordinatesTool(),
            TypeTextTool(),
            ScrollScreenTool(),
            GlobalActionTool(),
            WaitBrieflyTool(),
            // Communications & Notifications
            FindContactTool(context),
            MakeCallTool(context),
            SendSmsTool(context),
            GetNotificationsTool(),
            DismissNotificationTool(),
            ReplyNotificationTool(),
            // Productivity & Clock
            SetAlarmTool(context),
            SetTimerTool(context),
            GetCalendarEventsTool(context),
            CreateCalendarEventTool(context),
            ClipboardTool(context),
            // Suit Diagnostics
            GetDeviceDiagnosticsTool(telemetryMonitor),
            // Memory & Protocols
            RememberFactTool(store),
            RecallMemoriesTool(store),
            ForgetMemoryTool(store),
            ListProtocolsTool(store)
        )
        tools = list.associateBy { it.spec.name }
    }

    override fun allSpecs(): List<ToolSpec> = tools.values.map { it.spec }

    override fun findSpec(name: String): ToolSpec? = tools[name]?.spec

    override suspend fun execute(call: ToolCall): ToolResult {
        val tool = tools[call.name]
            ?: return ToolResult(
                toolCallId = call.id,
                toolName = call.name,
                success = false,
                output = "Unknown tool '${call.name}'."
            )
        return runCatching {
            val out = tool.execute(call.arguments)
            ToolResult(
                toolCallId = call.id,
                toolName = call.name,
                success = out.success,
                output = out.message,
                data = out.data
            )
        }.getOrElse { err ->
            ToolResult(
                toolCallId = call.id,
                toolName = call.name,
                success = false,
                output = "Tool '${call.name}' threw an error: ${err.message}"
            )
        }
    }
}
