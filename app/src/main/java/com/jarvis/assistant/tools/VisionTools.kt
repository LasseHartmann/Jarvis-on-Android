package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.agent.GeminiLlmClient
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.device.CameraPeekHelper
import com.jarvis.assistant.services.JarvisAccessibilityService
import kotlinx.coroutines.delay
import org.json.JSONObject

class AnalyzeScreenVisionTool(
    private val settingsProvider: () -> JarvisSettings,
    private val prepareScreenForPilot: suspend () -> Unit = {}
) : JarvisTool {
    override val spec = ToolSpec(
        name = "analyze_screen_vision",
        description = "Capture an actual visual screenshot of the current phone screen and inspect it with Gemini 4 Vision. Use this whenever the user asks about photos, images, maps, charts, videos, translations, QR codes, visual layouts, or when read_screen lacks text labels for an icon button.",
        params = listOf(
            ToolParam(
                "question",
                "string",
                "What to inspect, identify, translate, summarize, or locate on the screen (mention if you need normalized 0.0-1.0 x,y coordinates for tapping an unlabeled icon).",
                required = true
            )
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val question = args.optString("question", "").trim().ifBlank { "Describe what is currently shown on the screen." }
        val settings = settingsProvider()
        if (settings.geminiApiKey.isBlank()) {
            return ToolExecutionOutput(false, "Gemini API key is required for visual screen analysis.")
        }
        val a11y = JarvisAccessibilityService.instance
            ?: return ToolExecutionOutput(false, "Accessibility Phone Pilot is offline. Enable it in Jarvis permissions for Screen Vision.")

        prepareScreenForPilot()
        delay(120L)

        val (jpeg, msg) = a11y.captureScreenJpeg()
        if (jpeg == null || jpeg.isEmpty()) {
            return ToolExecutionOutput(false, msg)
        }

        val client = GeminiLlmClient(apiKey = settings.geminiApiKey, model = settings.geminiModel)
        val prompt = """
            Analyze this smartphone screenshot to answer the user's request.
            If asked to locate a clickable icon/button, also include its approximate normalized screen coordinates (x_norm, y_norm from 0.0 to 1.0).
            Request: $question
        """.trimIndent()
        val answer = client.analyzeImage(jpeg, prompt)
        return ToolExecutionOutput(true, answer)
    }
}

class LookWithCameraTool(
    private val context: Context,
    private val settingsProvider: () -> JarvisSettings
) : JarvisTool {
    override val spec = ToolSpec(
        name = "look_with_camera",
        description = "Snap a quick rear-camera photo and analyze the real world around the user with Gemini 4 Vision (identify objects/landmarks, read signs, nutrition labels, documents, or translate physical text).",
        params = listOf(
            ToolParam("question", "string", "What to look for, identify, read, or translate through the camera.", required = true)
        ),
        defaultRisk = RiskLevel.SAFE
    )

    override suspend fun execute(args: JSONObject): ToolExecutionOutput {
        val question = args.optString("question", "").trim().ifBlank { "Describe what is in front of the camera." }
        val settings = settingsProvider()
        if (settings.geminiApiKey.isBlank()) {
            return ToolExecutionOutput(false, "Gemini API key is required for camera visual analysis.")
        }

        val (jpeg, msg) = CameraPeekHelper.captureRearCameraJpeg(context)
        if (jpeg == null || jpeg.isEmpty()) {
            return ToolExecutionOutput(false, msg)
        }

        val client = GeminiLlmClient(apiKey = settings.geminiApiKey, model = settings.geminiModel)
        val answer = client.analyzeImage(jpeg, question)
        return ToolExecutionOutput(true, answer)
    }
}
