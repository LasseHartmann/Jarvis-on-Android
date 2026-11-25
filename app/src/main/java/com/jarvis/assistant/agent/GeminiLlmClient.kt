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
import java.util.concurrent.TimeUnit

class GeminiLlmClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = AiProvider.GEMINI.defaultBaseUrl,
    private val httpClient: OkHttpClient = defaultHttpClient()
) : LlmClient {

    override suspend fun complete(
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>
    ): LlmResponse = withContext(Dispatchers.IO) {
        val cleanModel = model.trim().removePrefix("models/").ifBlank { AiProvider.GEMINI.defaultModel }
        val url = "${baseUrl.trimEnd('/')}/models/$cleanModel:generateContent?key=${apiKey.trim()}"
        val payload = buildRequestBody(systemPrompt, messages, tools)

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errMsg = extractErrorMessage(bodyStr).ifBlank { "HTTP ${response.code}" }
                throw IOException("Gemini API error (${response.code}): $errMsg")
            }
            parseResponseBody(bodyStr)
        }
    }

    internal fun buildRequestBody(
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>
    ): JSONObject {
        val contents = JSONArray()
        for (msg in messages) {
            when (msg) {
                is LlmMessage.User -> {
                    contents.put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
                    )
                }

                is LlmMessage.Assistant -> {
                    val parts = if (msg.rawGeminiParts != null && msg.rawGeminiParts.length() > 0) {
                        // Preserve exact raw model parts (including thoughtSignature & thought blocks)
                        JSONArray(msg.rawGeminiParts.toString())
                    } else {
                        JSONArray().apply {
                            if (msg.content.isNotBlank()) {
                                put(JSONObject().put("text", msg.content))
                            }
                            for (tc in msg.toolCalls) {
                                val callPart = JSONObject().put(
                                    "functionCall",
                                    JSONObject()
                                        .put("name", tc.name)
                                        .put("args", tc.arguments)
                                )
                                if (!tc.thoughtSignature.isNullOrBlank()) {
                                    callPart.put("thoughtSignature", tc.thoughtSignature)
                                }
                                put(callPart)
                            }
                        }
                    }
                    if (parts.length() > 0) {
                        contents.put(JSONObject().put("role", "model").put("parts", parts))
                    }
                }

                is LlmMessage.ToolOutput -> {
                    val fnResp = JSONObject()
                        .put("name", msg.toolName)
                        .put("response", JSONObject().put("result", msg.output))
                    val part = JSONObject().put("functionResponse", fnResp)

                    // Group consecutive functionResponse parts into one user turn as required by Gemini API
                    val lastTurn = if (contents.length() > 0) contents.getJSONObject(contents.length() - 1) else null
                    val lastParts = lastTurn?.optJSONArray("parts")
                    val canAppend = lastTurn?.optString("role") == "user" &&
                        lastParts != null &&
                        lastParts.length() > 0 &&
                        lastParts.getJSONObject(0).has("functionResponse")

                    if (canAppend) {
                        lastParts!!.put(part)
                    } else {
                        contents.put(
                            JSONObject()
                                .put("role", "user")
                                .put("parts", JSONArray().put(part))
                        )
                    }
                }
            }
        }

        val root = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            put("contents", contents)
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.4)
                    .put("maxOutputTokens", 1024)
            )
        }

        if (tools.isNotEmpty()) {
            val declarations = JSONArray()
            for (tool in tools) {
                declarations.put(
                    JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                        .put("parameters", toGeminiSchema(tool.toJsonSchema()))
                )
            }
            root.put(
                "tools",
                JSONArray().put(JSONObject().put("functionDeclarations", declarations))
            )
        }
        return root
    }

    internal fun parseResponseBody(bodyStr: String): LlmResponse {
        val root = JSONObject(bodyStr)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            return LlmResponse(
                text = if (blockReason.isNotBlank()) "Request blocked by Gemini safety filter ($blockReason)." else "No response returned by Gemini."
            )
        }
        val content = candidates.getJSONObject(0).optJSONObject("content")
        val parts = content?.optJSONArray("parts") ?: JSONArray()

        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val isInternalThought = part.optBoolean("thought", false)
            val txt = part.optString("text", "")
            if (txt.isNotBlank() && !isInternalThought) {
                textParts.add(txt)
            }

            val fnCall = part.optJSONObject("functionCall")
            if (fnCall != null) {
                val name = fnCall.optString("name", "")
                val args = fnCall.optJSONObject("args") ?: JSONObject()
                val sig = part.optString("thoughtSignature", "")
                    .ifBlank { part.optString("thought_signature", "") }
                    .ifBlank { null }
                if (name.isNotBlank()) {
                    toolCalls.add(
                        ToolCall(
                            id = "gem_${UUID.randomUUID().toString().take(8)}",
                            name = name,
                            arguments = args,
                            thoughtSignature = sig
                        )
                    )
                }
            }
        }
        val rawCopy = if (parts.length() > 0) JSONArray(parts.toString()) else null
        return LlmResponse(
            text = textParts.joinToString("\n").trim(),
            toolCalls = toolCalls,
            rawGeminiParts = rawCopy
        )
    }

    private fun toGeminiSchema(schema: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = schema.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = schema.get(k)
            when {
                k == "type" && v is String -> out.put(k, v.uppercase())
                k == "properties" && v is JSONObject -> {
                    val propsOut = JSONObject()
                    val pKeys = v.keys()
                    while (pKeys.hasNext()) {
                        val pk = pKeys.next()
                        val pv = v.getJSONObject(pk)
                        propsOut.put(pk, toGeminiSchema(pv))
                    }
                    out.put(k, propsOut)
                }

                else -> out.put(k, v)
            }
        }
        return out
    }

    private fun extractErrorMessage(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
    }.getOrDefault(body.take(200))

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
