package com.jarvis.assistant

import com.jarvis.assistant.agent.GeminiLlmClient
import com.jarvis.assistant.agent.LlmMessage
import com.jarvis.assistant.agent.OpenAiCompatibleLlmClient
import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.agent.ToolCall
import com.jarvis.assistant.tools.ToolParam
import com.jarvis.assistant.tools.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPayloadParsingTest {

    private val sampleTools = listOf(
        ToolSpec(
            name = "set_volume",
            description = "Set audio volume",
            params = listOf(
                ToolParam("stream", "string", "Stream", required = true, enumValues = listOf("media", "ring")),
                ToolParam("percent", "integer", "Level 0-100", required = true)
            ),
            defaultRisk = RiskLevel.SAFE
        )
    )

    @Test
    fun geminiRequestUsesUppercaseSchemaTypesAndParsesFunctionCalls() {
        val client = GeminiLlmClient(apiKey = "test-key", model = "gemini-4-hydrogen")
        val body = client.buildRequestBody(
            systemPrompt = "You are JARVIS",
            messages = listOf(
                LlmMessage.User("Set volume to 50"),
                LlmMessage.Assistant("", listOf(ToolCall("c1", "set_volume", JSONObject().put("stream", "media").put("percent", 50)))),
                LlmMessage.ToolOutput("c1", "set_volume", "Volume set to 50%")
            ),
            tools = sampleTools
        )

        val decl = body.getJSONArray("tools").getJSONObject(0)
            .getJSONArray("functionDeclarations").getJSONObject(0)
        assertEquals("OBJECT", decl.getJSONObject("parameters").getString("type"))
        assertEquals("INTEGER", decl.getJSONObject("parameters").getJSONObject("properties").getJSONObject("percent").getString("type"))

        val mockResponse = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    { "functionCall": { "name": "set_volume", "args": { "stream": "media", "percent": 75 } } }
                  ]
                }
              }]
            }
        """.trimIndent()
        val parsed = client.parseResponseBody(mockResponse)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("set_volume", parsed.toolCalls[0].name)
        assertEquals(75, parsed.toolCalls[0].arguments.getInt("percent"))
    }

    @Test
    fun openAiCompatibleRequestAndToolCallParsing() {
        val client = OpenAiCompatibleLlmClient(apiKey = "sk-test", baseUrl = "http://localhost:11434/v1", model = "qwen2.5")
        val body = client.buildRequestBody(
            systemPrompt = "You are JARVIS",
            messages = listOf(LlmMessage.User("Hello")),
            tools = sampleTools
        )
        assertEquals("qwen2.5", body.getString("model"))
        assertTrue(body.has("tools"))

        val mockResponse = """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_abc",
                    "type": "function",
                    "function": {
                      "name": "set_volume",
                      "arguments": "{\"stream\":\"ring\",\"percent\":30}"
                    }
                  }]
                }
              }]
            }
        """.trimIndent()
        val parsed = client.parseResponseBody(mockResponse)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("call_abc", parsed.toolCalls[0].id)
        assertEquals(30, parsed.toolCalls[0].arguments.getInt("percent"))
    }
}
