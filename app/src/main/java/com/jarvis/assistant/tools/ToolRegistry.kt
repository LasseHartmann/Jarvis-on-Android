package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.agent.JarvisSettings
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
    store: JarvisStore,
    settingsProvider: () -> JarvisSettings = { JarvisSettings() },
    prepareScreenForPilot: suspend () -> Unit = {}
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
            // Multimodal Vision (Screen & Rear Camera)
            AnalyzeScreenVisionTool(settingsProvider, prepareScreenForPilot),
            LookWithCameraTool(context, settingsProvider),
            // Live Web Grounding, GPS & Weather
            LiveWebAnswerTool(settingsProvider),
            FetchWebpageTool(),
            GetCurrentLocationTool(context),
            GetWeatherTool(context),
            // App, Navigation & Direct Media/Messaging
            ListInstalledAppsTool(appsRepo),
            OpenAppTool(appsRepo, prepareScreenForPilot),
            NavigateToTool(context, prepareScreenForPilot),
            SendWhatsAppTool(context, prepareScreenForPilot),
            PlayMusicTool(context, prepareScreenForPilot),
            OpenUrlTool(context, prepareScreenForPilot),
            WebSearchTool(context, prepareScreenForPilot),
            // Accessibility Screen Pilot
            ReadScreenTool(prepareScreenForPilot),
            TapElementTool(prepareScreenForPilot),
            TapCoordinatesTool(prepareScreenForPilot),
            TypeTextTool(prepareScreenForPilot),
            ScrollScreenTool(prepareScreenForPilot),
            GlobalActionTool(prepareScreenForPilot),
            WaitBrieflyTool(),
            // Communications & Notifications
            FindContactTool(context),
            MakeCallTool(context),
            SendSmsTool(context),
            GetNotificationsTool(),
            DismissNotificationTool(),
            ReplyNotificationTool(),
            // Productivity, Clock & Scheduled Reminders
            SetAlarmTool(context),
            SetTimerTool(context),
            ScheduleReminderTool(context, store),
            ListRemindersTool(store),
            CancelReminderTool(context, store),
            GetCalendarEventsTool(context),
            CreateCalendarEventTool(context),
            ClipboardTool(context),
            // Suit Diagnostics
            GetDeviceDiagnosticsTool(telemetryMonitor),
            // Hardware IR Blaster & Smart Home / PC Ecosystem
            ControlIrDeviceTool(context),
            ListSmartHomeEntitiesTool(settingsProvider),
            SmartHomeControlTool(settingsProvider),
            PcBridgeActionTool(settingsProvider),
            // Memory & Stark Protocols
            RememberFactTool(store),
            RecallMemoriesTool(store),
            ForgetMemoryTool(store),
            ListProtocolsTool(store),
            CreateProtocolTool(store),
            DeleteProtocolTool(store)
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
