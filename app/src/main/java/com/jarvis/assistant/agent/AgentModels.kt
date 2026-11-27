package com.jarvis.assistant.agent

import org.json.JSONObject

enum class JarvisState(val label: String) {
    IDLE("Standby"),
    LISTENING("Listening"),
    THINKING("Thinking"),
    EXECUTING("Piloting"),
    AWAITING_AUTH("Confirmation"),
    SPEAKING("Speaking"),
    ERROR("Attention")
}

enum class HudColorTheme(
    val displayName: String,
    val primaryArgb: Int,
    val secondaryArgb: Int,
    val accentArgb: Int
) {
    JARVIS_AMBER(
        displayName = "Amber",
        primaryArgb = 0xFFF0B44C.toInt(),
        secondaryArgb = 0xFFC87D20.toInt(),
        accentArgb = 0xFFFFF3D6.toInt()
    ),
    ARC_FROST(
        displayName = "Frost",
        primaryArgb = 0xFF7DD3FC.toInt(),
        secondaryArgb = 0xFF38BDF8.toInt(),
        accentArgb = 0xFFF0F9FF.toInt()
    ),
    OBSIDIAN_MONO(
        displayName = "Mono",
        primaryArgb = 0xFFF4F4F5.toInt(),
        secondaryArgb = 0xFFA1A1AA.toInt(),
        accentArgb = 0xFFFFFFFF.toInt()
    );

    companion object {
        fun fromStored(raw: String?): HudColorTheme = when (raw?.trim()) {
            JARVIS_AMBER.name, "MARK_85" -> JARVIS_AMBER
            ARC_FROST.name, "ARC_CYAN" -> ARC_FROST
            OBSIDIAN_MONO.name, "STEALTH_BLUE" -> OBSIDIAN_MONO
            else -> JARVIS_AMBER
        }
    }
}

enum class GeminiModel(
    val apiId: String,
    val displayName: String,
    val description: String
) {
    HYDROGEN(
        apiId = "gemini-4-hydrogen",
        displayName = "Gemini 4 Hydrogen",
        description = "Fastest voice response & everyday phone control"
    ),
    ARGON(
        apiId = "gemini-4-argon",
        displayName = "Gemini 4 Argon",
        description = "High-precision structured tool execution"
    ),
    NEON(
        apiId = "gemini-4-neon",
        displayName = "Gemini 4 Neon",
        description = "Balanced frontier intelligence & multi-step piloting"
    ),
    CARBON(
        apiId = "gemini-4-carbon",
        displayName = "Gemini 4 Carbon",
        description = "Deepest reasoning for complex tasks"
    );

    companion object {
        fun fromApiId(id: String?): GeminiModel =
            entries.firstOrNull { it.apiId.equals(id?.trim(), ignoreCase = true) } ?: HYDROGEN
    }
}

enum class AiProvider(
    val displayName: String,
    val defaultModel: String,
    val defaultBaseUrl: String
) {
    GEMINI(
        displayName = "Google Gemini",
        defaultModel = GeminiModel.HYDROGEN.apiId,
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta"
    ),
    OPENAI_COMPATIBLE(
        displayName = "OpenAI / Local Ollama",
        defaultModel = "gpt-4o-mini",
        defaultBaseUrl = "https://api.openai.com/v1"
    ),
    LOCAL_ONLY(
        displayName = "Offline Reflex Core",
        defaultModel = "local-reflex-v1",
        defaultBaseUrl = ""
    )
}

enum class SafetyMode(val displayName: String, val description: String) {
    STRICT(
        displayName = "Strict",
        description = "Ask voice confirmation for sensitive actions, UI taps, and outbound comms."
    ),
    BALANCED(
        displayName = "Balanced",
        description = "Auto-run safe controls; ask voice confirmation for calls, SMS, replies, DND, and pay/delete taps."
    ),
    AUTONOMOUS(
        displayName = "Autonomous",
        description = "Auto-run most actions; still blocks password leaks and asks voice confirmation for financial/destructive taps."
    )
}

enum class PersonaStyle(val displayName: String) {
    CLASSIC_STARK("Classic J.A.R.V.I.S."),
    CONCISE_TACTICAL("Tactical Brief"),
    WARM_COMPANION("Conversational")
}

enum class JarvisVoiceProfile(
    val geminiVoiceName: String,
    val displayName: String,
    val subtitle: String
) {
    CHARON("Charon", "J.A.R.V.I.S. Classic (Charon)", "Calm, refined British butler timbre"),
    ORUS("Orus", "Stark Tactical (Orus)", "Firm, crisp mission-control cadence"),
    ENCELADUS("Enceladus", "Deep Core (Enceladus)", "Smooth, resonant lower register")
}

enum class RiskLevel {
    SAFE,
    SENSITIVE,
    CRITICAL
}

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject,
    val thoughtSignature: String? = null
)

data class ToolResult(
    val toolCallId: String,
    val toolName: String,
    val success: Boolean,
    val output: String,
    val data: JSONObject? = null
)

data class ToolExecutionTrace(
    val toolName: String,
    val argumentsSummary: String,
    val success: Boolean,
    val output: String,
    val riskLevel: RiskLevel,
    val durationMs: Long,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: Long = 0L,
    val role: MessageRole,
    val content: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val traces: List<ToolExecutionTrace> = emptyList()
)

data class PendingConfirmation(
    val id: String,
    val toolCall: ToolCall,
    val riskLevel: RiskLevel,
    val title: String,
    val detail: String,
    val reason: String
)

data class JarvisMemory(
    val id: Long = 0L,
    val key: String,
    val content: String,
    val category: String = "general",
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class AuditLogEntry(
    val id: Long = 0L,
    val timestampMs: Long,
    val toolName: String,
    val argumentsJson: String,
    val riskLevel: RiskLevel,
    val approved: Boolean,
    val success: Boolean,
    val summary: String
)

data class JarvisProtocol(
    val id: Long = 0L,
    val name: String,
    val triggerPhrase: String,
    val description: String,
    val commandPrompt: String,
    val isBuiltIn: Boolean = false
)

data class JarvisReminder(
    val id: Long = 0L,
    val title: String,
    val message: String,
    val triggerAtMs: Long,
    val repeatDaily: Boolean = false,
    val isBriefing: Boolean = false,
    val active: Boolean = true
)

data class JarvisSettings(
    val provider: AiProvider = AiProvider.GEMINI,
    val geminiApiKey: String = "",
    val geminiModel: String = AiProvider.GEMINI.defaultModel,
    val openAiApiKey: String = "",
    val openAiBaseUrl: String = AiProvider.OPENAI_COMPATIBLE.defaultBaseUrl,
    val openAiModel: String = AiProvider.OPENAI_COMPATIBLE.defaultModel,
    val safetyMode: SafetyMode = SafetyMode.AUTONOMOUS,
    val personaStyle: PersonaStyle = PersonaStyle.CLASSIC_STARK,
    val hudColorTheme: HudColorTheme = HudColorTheme.JARVIS_AMBER,
    val userTitle: String = "Sir",
    val customSystemNotes: String = "",
    val ttsEnabled: Boolean = true,
    val neuralJarvisVoice: Boolean = true,
    val voiceProfile: JarvisVoiceProfile = JarvisVoiceProfile.CHARON,
    val intercomDspEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.03f,
    val ttsPitch: Float = 0.88f,
    val soundEffectsEnabled: Boolean = true,
    val localReflexFirst: Boolean = true,
    val maxAgentSteps: Int = 10,
    val continuousConversation: Boolean = true,
    val autoDismissAfterReply: Boolean = true,
    val languageMode: JarvisLanguageMode = JarvisLanguageMode.BILINGUAL_EN_DE,
    val wakeWordEnabled: Boolean = true,
    val wakeWordPauseScreenOff: Boolean = true,
    val bluetoothWhisperEnabled: Boolean = true,
    val homeAssistantUrl: String = "",
    val homeAssistantToken: String = "",
    val pcBridgeUrl: String = "",
    val pcBridgeToken: String = "",
    val alexaVoiceMonkeyToken: String = "",
    val alexaDefaultDevice: String = "",
    val alexaWebhookUrl: String = ""
) {
    fun hasActiveCloudKey(): Boolean = when (provider) {
        AiProvider.GEMINI -> geminiApiKey.isNotBlank()
        AiProvider.OPENAI_COMPATIBLE -> openAiBaseUrl.isNotBlank() &&
            (openAiApiKey.isNotBlank() || openAiBaseUrl.contains("localhost") || openAiBaseUrl.contains("127.0.0.1") || openAiBaseUrl.contains("192.168.") || openAiBaseUrl.contains("10."))
        AiProvider.LOCAL_ONLY -> false
    }
}
