package com.jarvis.assistant.agent

import com.jarvis.assistant.tools.ToolSpec
import org.json.JSONArray

sealed class LlmMessage {
    data class User(val content: String) : LlmMessage()
    data class Assistant(
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val rawGeminiParts: JSONArray? = null
    ) : LlmMessage()

    data class ToolOutput(
        val toolCallId: String,
        val toolName: String,
        val output: String
    ) : LlmMessage()
}

data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val rawGeminiParts: JSONArray? = null
)

interface LlmClient {
    suspend fun complete(
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>
    ): LlmResponse
}
