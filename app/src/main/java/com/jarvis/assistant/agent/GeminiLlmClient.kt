package com.jarvis.assistant.agent

import android.util.Base64
import android.util.Log
import com.jarvis.assistant.tools.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    override suspend fun completeStreaming(
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit
    ): LlmResponse = withContext(Dispatchers.IO) {
        val cleanModel = model.trim().removePrefix("models/").ifBlank { AiProvider.GEMINI.defaultModel }
        val url = "${baseUrl.trimEnd('/')}/models/$cleanModel:streamGenerateContent?alt=sse&key=${apiKey.trim()}"
        val payload = buildRequestBody(systemPrompt, messages, tools)
        val startMs = System.currentTimeMillis()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val bodyStr = response.body?.string().orEmpty()
                val errMsg = extractErrorMessage(bodyStr).ifBlank { "HTTP ${response.code}" }
                throw IOException("Gemini API error (${response.code}): $errMsg")
            }
            val source = response.body?.source()
                ?: return@use LlmResponse(text = "No response returned by Gemini.")

            val acc = StreamAccumulator()
            var firstTokenLogged = false
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val dataJson = line.removePrefix("data:").trim()
                if (dataJson.isEmpty() || dataJson == "[DONE]") continue

                val delta = consumeSseChunk(dataJson, acc)
                if (delta.isNotEmpty() && acc.toolCalls.isEmpty()) {
                    if (!firstTokenLogged) {
                        firstTokenLogged = true
                        Log.i(TAG, "LLM stream first-token=${System.currentTimeMillis() - startMs}ms model=$cleanModel")
                    }
                    onTextDelta(delta)
                }
            }
            acc.toResponse()
        }
    }

    /**
     * Multimodal vision call (JPEG inlineData -> Gemini 4) for Screen Vision and Camera Peek.
     */
    suspend fun analyzeImage(
        jpegBytes: ByteArray,
        prompt: String,
        systemInstruction: String = "You are J.A.R.V.I.S. visual analysis module. Answer accurately and concisely based strictly on the image."
    ): String = withContext(Dispatchers.IO) {
        val cleanModel = model.trim().removePrefix("models/").ifBlank { AiProvider.GEMINI.defaultModel }
        val url = "${baseUrl.trimEnd('/')}/models/$cleanModel:generateContent?key=${apiKey.trim()}"
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val genConfig = JSONObject()
            .put("temperature", 0.25)
            .put("maxOutputTokens", 768)
        val lower = cleanModel.lowercase()
        if (lower.startsWith("gemini-4") || lower.startsWith("gemini-3")) {
            genConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
        }

        val parts = JSONArray()
            .put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", b64)
                )
            )
            .put(JSONObject().put("text", prompt))

        val root = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", genConfig)

        val req = Request.Builder()
            .url(url)
            .post(root.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errMsg = extractErrorMessage(bodyStr).ifBlank { "HTTP ${resp.code}" }
                throw IOException("Gemini Vision error (${resp.code}): $errMsg")
            }
            parseResponseBody(bodyStr).text.ifBlank { "No visual details returned." }
        }
    }

    /**
     * Live Google Search Grounding call using Gemini's built-in {"google_search": {}} tool.
     * Kept separate from custom functionDeclarations as required by Gemini REST API.
     */
    suspend fun groundedWebSearch(query: String): String = withContext(Dispatchers.IO) {
        val cleanModel = model.trim().removePrefix("models/").ifBlank { AiProvider.GEMINI.defaultModel }
        val url = "${baseUrl.trimEnd('/')}/models/$cleanModel:generateContent?key=${apiKey.trim()}"

        val genConfig = JSONObject()
            .put("temperature", 0.2)
            .put("maxOutputTokens", 768)
        val lower = cleanModel.lowercase()
        if (lower.startsWith("gemini-4") || lower.startsWith("gemini-3")) {
            genConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
        }

        val root = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "Use Google Search grounding to answer with accurate, up-to-date facts. Reply concisely in plain text (no markdown) in the same language as the query."
                        )
                    )
                )
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", query)))
                )
            )
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", genConfig)

        val req = Request.Builder()
            .url(url)
            .post(root.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errMsg = extractErrorMessage(bodyStr).ifBlank { "HTTP ${resp.code}" }
                throw IOException("Gemini Search Grounding error (${resp.code}): $errMsg")
            }
            val parsedText = parseResponseBody(bodyStr).text
            val sources = extractGroundingSources(bodyStr)
            if (sources.isNotEmpty()) {
                "$parsedText\nSources: ${sources.joinToString(", ")}"
            } else {
                parsedText.ifBlank { "No live web answer found." }
            }
        }
    }

    internal fun extractGroundingSources(bodyStr: String): List<String> = runCatching {
        val root = JSONObject(bodyStr)
        val cand = root.optJSONArray("candidates")?.optJSONObject(0) ?: return emptyList()
        val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks") ?: return emptyList()
        val titles = LinkedHashSet<String>()
        for (i in 0 until chunks.length()) {
            val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
            val title = web.optString("title", "").trim()
            if (title.isNotBlank()) titles.add(title)
            if (titles.size >= 3) break
        }
        titles.toList()
    }.getOrDefault(emptyList())

    internal class StreamAccumulator {
        val spokenText = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        val rawParts = JSONArray()
        var blockReason: String = ""

        fun toResponse(): LlmResponse {
            val text = spokenText.toString().trim()
            if (text.isEmpty() && toolCalls.isEmpty() && blockReason.isNotBlank()) {
                return LlmResponse("Request blocked by Gemini safety filter ($blockReason).")
            }
            return LlmResponse(
                text = text,
                toolCalls = toolCalls.toList(),
                rawGeminiParts = if (rawParts.length() > 0) JSONArray(rawParts.toString()) else null
            )
        }
    }

    internal fun consumeSseChunk(dataJson: String, acc: StreamAccumulator): String {
        val root = runCatching { JSONObject(dataJson) }.getOrNull() ?: return ""
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val br = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            if (br.isNotBlank()) acc.blockReason = br
            return ""
        }
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val deltaBuilder = StringBuilder()

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            acc.rawParts.put(JSONObject(part.toString()))

            val isInternalThought = part.optBoolean("thought", false)
            val txt = part.optString("text", "")
            if (txt.isNotEmpty() && !isInternalThought) {
                acc.spokenText.append(txt)
                deltaBuilder.append(txt)
            }

            val fnCall = part.optJSONObject("functionCall")
            if (fnCall != null) {
                val name = fnCall.optString("name", "")
                val args = fnCall.optJSONObject("args") ?: JSONObject()
                val sig = part.optString("thoughtSignature", "")
                    .ifBlank { part.optString("thought_signature", "") }
                    .ifBlank { null }
                if (name.isNotBlank()) {
                    acc.toolCalls.add(
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
        return deltaBuilder.toString()
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

        val genConfig = JSONObject()
            .put("temperature", 0.35)
            .put("maxOutputTokens", 1024)
        val cleanModel = model.trim().removePrefix("models/").lowercase()
        if (cleanModel.startsWith("gemini-4") || cleanModel.startsWith("gemini-3")) {
            genConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
        }

        val root = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            put("contents", contents)
            put("generationConfig", genConfig)
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
        private const val TAG = "GeminiLlm"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
