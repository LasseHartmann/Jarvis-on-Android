package com.jarvis.assistant.voice

import android.util.Base64
import com.jarvis.assistant.agent.AiProvider
import com.jarvis.assistant.agent.GeminiLlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Synthesizes authentic Iron Man J.A.R.V.I.S. British Received Pronunciation speech
 * using Google's Gemini Native Audio TTS REST endpoint (24 kHz 16-bit mono PCM).
 */
class GeminiTtsClient(
    private val httpClient: OkHttpClient = GeminiLlmClient.defaultHttpClient()
) {

    @Volatile
    private var cachedWorkingModel: String? = null

    suspend fun synthesizeJarvisSpeech(
        apiKey: String,
        text: String,
        voiceName: String = "Charon"
    ): DecodedPcm? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanText = text.trim()
        if (cleanKey.isBlank() || cleanText.isBlank()) return@withContext null

        val orderedModels = buildList {
            cachedWorkingModel?.let { add(it) }
            DEFAULT_TTS_MODELS.forEach { if (it !in this) add(it) }
        }

        val steeredPrompt = "Speak the following line in a cultured, calm, refined upper-class British Received Pronunciation male accent inspired by Iron Man's J.A.R.V.I.S. AI butler — articulate, composed, dryly witty, crisp pacing. Read ONLY the exact words below:\n\n$cleanText"

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", steeredPrompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", voiceName)
                            )
                        )
                    )
            )
        }

        for (model in orderedModels) {
            val url = "${AiProvider.GEMINI.defaultBaseUrl}/models/$model:generateContent?key=$cleanKey"
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val decoded = runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bodyStr = resp.body?.string().orEmpty()
                    extractPcmFromResponse(bodyStr)
                }
            }.getOrNull()

            if (decoded != null) {
                cachedWorkingModel = model
                return@withContext decoded
            }
        }
        null
    }

    private fun extractPcmFromResponse(bodyStr: String): DecodedPcm? {
        val root = JSONObject(bodyStr)
        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return null

        for (i in 0 until parts.length()) {
            val inlineData = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
            val mime = inlineData.optString("mimeType", "")
            val b64 = inlineData.optString("data", "")
            if (b64.isBlank()) continue
            val rawBytes = Base64.decode(b64, Base64.DEFAULT)

            if (mime.contains("wav", ignoreCase = true)) {
                return StarkVoiceDsp.decodeWavFile(rawBytes)
            }
            val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 24_000
            return DecodedPcm(
                samples = StarkVoiceDsp.pcmBytesToShortsLittleEndian(rawBytes),
                sampleRateHz = rate
            )
        }
        return null
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val DEFAULT_TTS_MODELS = listOf(
            "gemini-2.5-flash-preview-tts",
            "gemini-3.1-flash-tts-preview",
            "gemini-3.8-flash-tts"
        )
    }
}
