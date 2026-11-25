package com.jarvis.assistant.agent

import com.jarvis.assistant.tools.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class OpenAiCompatibleLlmClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val httpClient: OkHttpClient = GeminiLlmClient.defaultHttpClient()
) : LlmClient {

    override suspend fun complete(
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>
    ): LlmResponse = withContext(Dispatchers.IO) {
        val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"
        val payload = buildRequestBody(systemPrompt, messages, tools)

        val builder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${apiKey.trim()}")
        }

        httpClient.newCall(builder.build()).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val err = runCatching {
                    JSONObject(bodyStr).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault(bodyStr.take(200))
                throw IOException("LLM API error (${response.code}): ${err.ifBlank { "HTTP ${response.code}" }}")
            }
            parseResponseBody(bodyStr)
        }
    }

    internal fun buildRequestBody(
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>
    ): JSONObject {
        val msgArr = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        for (msg in messages) {
            when (msg) {
                is LlmMessage.User -> {
                    msgArr.put(JSONObject().put("role", "user").put("content", msg.content))
                }

                is LlmMessage.Assistant -> {
                    val obj = JSONObject().put("role", "assistant")
                    obj.put("content", msg.content.ifBlank { JSONObject.NULL })
                    if (msg.toolCalls.isNotEmpty()) {
                        val callsArr = JSONArray()
                        for (tc in msg.toolCalls) {
                            callsArr.put(
                                JSONObject()
                                    .put("id", tc.id)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", tc.name)
                                            .put("arguments", tc.arguments.toString())
                                    )
                            )
                        }
                        obj.put("tool_calls", callsArr)
                    }
                    msgArr.put(obj)
                }

                is LlmMessage.ToolOutput -> {
                    msgArr.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", msg.toolCallId)
                            .put("name", msg.toolName)
                            .put("content", msg.output)
                    )
                }
            }
        }

        val root = JSONObject().apply {
            put("model", model.trim().ifBlank { AiProvider.OPENAI_COMPATIBLE.defaultModel })
            put("messages", msgArr)
            put("temperature", 0.4)
        }
        if (tools.isNotEmpty()) {
            val toolsArr = JSONArray()
            for (t in tools) {
                toolsArr.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", t.name)
                                .put("description", t.description)
                                .put("parameters", t.toJsonSchema())
                        )
                )
            }
            root.put("tools", toolsArr)
            root.put("tool_choice", "auto")
        }
        return root
    }

    internal fun parseResponseBody(bodyStr: String): LlmResponse {
        val root = JSONObject(bodyStr)
        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return LlmResponse("No choices returned from model.")
        }
        val msg = choices.getJSONObject(0).optJSONObject("message") ?: return LlmResponse("")
        val text = if (msg.isNull("content")) "" else msg.optString("content", "").trim()
        val toolCalls = mutableListOf<ToolCall>()

        val rawCalls = msg.optJSONArray("tool_calls")
        if (rawCalls != null) {
            for (i in 0 until rawCalls.length()) {
                val tc = rawCalls.getJSONObject(i)
                val id = tc.optString("id", "oai_${UUID.randomUUID().toString().take(8)}")
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name", "")
                val argsRaw = fn.optString("arguments", "{}")
                val args = runCatching { JSONObject(argsRaw) }.getOrDefault(JSONObject())
                if (name.isNotBlank()) {
                    toolCalls.add(ToolCall(id = id, name = name, arguments = args))
                }
            }
        }
        return LlmResponse(text = text, toolCalls = toolCalls)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
