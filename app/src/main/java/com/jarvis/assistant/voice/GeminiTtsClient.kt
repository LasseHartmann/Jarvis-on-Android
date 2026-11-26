package com.jarvis.assistant.voice

import android.util.Base64
import android.util.Log
import com.jarvis.assistant.agent.AiProvider
import com.jarvis.assistant.agent.SpokenLanguage
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
import java.util.concurrent.TimeUnit

/**
 * Streams authentic Iron Man J.A.R.V.I.S. speech (British RP English & cultured German)
 * via Google's Gemini Native Audio TTS SSE endpoint (24 kHz 16-bit mono PCM) so audio starts
 * playing as soon as the very first PCM chunk arrives instead of waiting for the full clip.
 */
class GeminiTtsClient(
    private val httpClient: OkHttpClient = defaultTtsHttpClient()
) {

    @Volatile
    private var cachedWorkingModel: String? = null

    /**
     * Streams PCM chunks incrementally via streamGenerateContent?alt=sse.
     * Returns true if at least one audio chunk was delivered.
     */
    suspend fun streamJarvisSpeech(
        apiKey: String,
        text: String,
        voiceName: String = "Charon",
        language: SpokenLanguage = SpokenLanguage.ENGLISH,
        onPcmChunk: suspend (DecodedPcm) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanText = text.trim()
        if (cleanKey.isBlank() || cleanText.isBlank()) return@withContext false

        val orderedModels = buildList {
            cachedWorkingModel?.let { add(it) }
            DEFAULT_TTS_MODELS.forEach { if (it !in this) add(it) }
        }

        val payloadStr = buildTtsPayload(cleanText, voiceName, language).toString()
        val startMs = System.currentTimeMillis()

        for (model in orderedModels) {
            currentCoroutineContext().ensureActive()
            val url = "${AiProvider.GEMINI.defaultBaseUrl}/models/$model:streamGenerateContent?alt=sse&key=$cleanKey"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var emittedAny = false
            var carryByte: Byte? = null

            val succeeded = runCatching {
                val call = httpClient.newCall(req)
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "TTS stream HTTP ${resp.code} on $model")
                        return@use false
                    }
                    val source = resp.body?.source() ?: return@use false
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val dataJson = line.removePrefix("data:").trim()
                        if (dataJson.isEmpty() || dataJson == "[DONE]") continue

                        val chunks = extractAudioChunksFromJson(dataJson, carryByte)
                        carryByte = chunks.second
                        for (pcm in chunks.first) {
                            if (pcm.samples.isNotEmpty()) {
                                if (!emittedAny) {
                                    emittedAny = true
                                    cachedWorkingModel = model
                                    Log.i(TAG, "TTS TTFA=${System.currentTimeMillis() - startMs}ms model=$model lang=$language chars=${cleanText.length}")
                                }
                                onPcmChunk(pcm)
                            }
                        }
                    }
                    emittedAny
                }
            }.onFailure { err ->
                if (err is kotlinx.coroutines.CancellationException) throw err
                Log.w(TAG, "TTS stream failed on $model: ${err.message}")
            }.getOrDefault(false)

            if (succeeded || emittedAny) {
                return@withContext true
            }
        }

        // Final fallback: non-streaming generateContent if SSE wasn't supported by an endpoint
        val batch = synthesizeJarvisSpeech(cleanKey, cleanText, voiceName, language)
        if (batch != null && batch.samples.isNotEmpty()) {
            onPcmChunk(batch)
            return@withContext true
        }
        false
    }

    suspend fun synthesizeJarvisSpeech(
        apiKey: String,
        text: String,
        voiceName: String = "Charon",
        language: SpokenLanguage = SpokenLanguage.ENGLISH
    ): DecodedPcm? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanText = text.trim()
        if (cleanKey.isBlank() || cleanText.isBlank()) return@withContext null

        val orderedModels = buildList {
            cachedWorkingModel?.let { add(it) }
            DEFAULT_TTS_MODELS.forEach { if (it !in this) add(it) }
        }

        val payloadStr = buildTtsPayload(cleanText, voiceName, language).toString()

        for (model in orderedModels) {
            val url = "${AiProvider.GEMINI.defaultBaseUrl}/models/$model:generateContent?key=$cleanKey"
            val req = Request.Builder()
                .url(url)
                .post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val decoded = runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bodyStr = resp.body?.string().orEmpty()
                    extractAudioChunksFromJson(bodyStr, null).first.firstOrNull()
                }
            }.getOrNull()

            if (decoded != null) {
                cachedWorkingModel = model
                return@withContext decoded
            }
        }
        null
    }

    private fun buildTtsPayload(
        cleanText: String,
        voiceName: String,
        language: SpokenLanguage
    ): JSONObject {
        val steeredPrompt = when (language) {
            SpokenLanguage.GERMAN ->
                "Kultivierter, ruhiger deutscher KI-Butler (J.A.R.V.I.S.), klar und präzise. Lies nur:\n$cleanText"

            SpokenLanguage.ENGLISH ->
                "Refined British RP male AI butler (J.A.R.V.I.S.), calm and crisp. Read only:\n$cleanText"
        }

        return JSONObject().apply {
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
    }

    internal fun extractAudioChunksFromJson(
        bodyStr: String,
        initialCarry: Byte?
    ): Pair<List<DecodedPcm>, Byte?> {
        val root = runCatching { JSONObject(bodyStr) }.getOrNull() ?: return emptyList<DecodedPcm>() to initialCarry
        val candidates = root.optJSONArray("candidates") ?: return emptyList<DecodedPcm>() to initialCarry
        if (candidates.length() == 0) return emptyList<DecodedPcm>() to initialCarry
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: return emptyList<DecodedPcm>() to initialCarry

        val out = mutableListOf<DecodedPcm>()
        var carry = initialCarry

        for (i in 0 until parts.length()) {
            val inlineData = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
            val mime = inlineData.optString("mimeType", "")
            val b64 = inlineData.optString("data", "")
            if (b64.isBlank()) continue
            val rawBytes = Base64.decode(b64, Base64.DEFAULT)
            if (rawBytes.isEmpty()) continue

            if (mime.contains("wav", ignoreCase = true) && rawBytes.size >= 44 &&
                rawBytes[0].toInt().toChar() == 'R' && rawBytes[1].toInt().toChar() == 'I'
            ) {
                StarkVoiceDsp.decodeWavFile(rawBytes)?.let { out.add(it) }
                carry = null
                continue
            }

            val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 24_000
            val combined = if (carry != null) {
                ByteArray(rawBytes.size + 1).apply {
                    this[0] = carry!!
                    System.arraycopy(rawBytes, 0, this, 1, rawBytes.size)
                }
            } else rawBytes

            val evenLen = combined.size and 1.inv()
            carry = if ((combined.size and 1) == 1) combined[combined.size - 1] else null

            if (evenLen >= 2) {
                val aligned = if (evenLen == combined.size) combined else combined.copyOf(evenLen)
                out.add(
                    DecodedPcm(
                        samples = StarkVoiceDsp.pcmBytesToShortsLittleEndian(aligned),
                        sampleRateHz = rate
                    )
                )
            }
        }
        return out to carry
    }

    companion object {
        private const val TAG = "GeminiTts"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val DEFAULT_TTS_MODELS = listOf(
            "gemini-3.1-flash-tts-preview",
            "gemini-2.5-flash-preview-tts",
            "gemini-3.8-flash-tts"
        )

        fun defaultTtsHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(28, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
