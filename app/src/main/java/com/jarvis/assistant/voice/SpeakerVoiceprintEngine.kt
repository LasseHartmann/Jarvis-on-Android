package com.jarvis.assistant.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 100% local on-device biometric Speaker Verification engine powered by
 * NVIDIA NeMo SpeakerNet (Apache-2.0, 192-dim neural speaker embeddings).
 *
 * Pipeline (matches sherpa-onnx SpeakerEmbeddingExtractorNeMoImpl):
 *   1) Trim leading/trailing silence + normalize PCM16 -> [-1, 1]
 *   2) Pre-emphasis (0.97) + 25ms Hann window + 10ms hop + 512-pt FFT
 *   3) 64-bin Slaney/librosa mel filterbank + ln(mel + 1e-5)
 *   4) Per-feature zero-mean, unit-variance normalization + pad to multiple of 16
 *   5) ONNX inference -> 192-dim L2-normalized speaker vector
 */
class SpeakerVoiceprintEngine(
    modelBytes: ByteArray
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOpts: OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
    }
    private val session: OrtSession = env.createSession(modelBytes, sessionOpts)

    private val inputNames: List<String> = session.inputNames.toList()
    private val outputNames: List<String> = session.outputNames.toList()
    private val embOutputName: String = outputNames.firstOrNull {
        it.contains("emb", ignoreCase = true)
    } ?: outputNames.getOrElse(1) { outputNames.first() }

    private val featDim: Int
    private val sampleRate: Int
    private val winLength: Int
    private val hopLength: Int
    private val melFilters: Array<FloatArray>
    private val hannWindow: FloatArray

    @Volatile
    private var closed = false

    init {
        val meta = runCatching { session.metadata.customMetadata }.getOrDefault(emptyMap())
        featDim = meta["feat_dim"]?.toIntOrNull()?.coerceIn(32, 128) ?: 64
        sampleRate = meta["sample_rate"]?.toIntOrNull() ?: 16_000
        val winMs = meta["window_size_ms"]?.toDoubleOrNull() ?: 25.0
        val hopMs = meta["window_stride_ms"]?.toDoubleOrNull() ?: 10.0
        winLength = ((sampleRate * winMs) / 1000.0).toInt().coerceIn(160, FFT_SIZE)
        hopLength = ((sampleRate * hopMs) / 1000.0).toInt().coerceIn(80, winLength)

        melFilters = buildSlaneyMelFilterbank(
            sampleRate = sampleRate,
            nFft = FFT_SIZE,
            nMels = featDim,
            fMin = 0.0,
            fMax = sampleRate / 2.0
        )
        hannWindow = FloatArray(winLength) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (winLength - 1))).toFloat()
        }
        Log.i(
            TAG,
            "Loaded SpeakerNet ONNX (inputs=$inputNames, outputs=$outputNames, embOut=$embOutputName, meta=$meta, featDim=$featDim, sr=$sampleRate, win=$winLength, hop=$hopLength)"
        )
    }

    /**
     * Extracts a 192-dim L2-normalized speaker embedding from 16 kHz mono PCM-16 audio.
     * Returns null if the clip has insufficient speech energy.
     */
    @Synchronized
    fun extractEmbedding(pcm16: ShortArray): FloatArray? {
        if (closed || pcm16.size < winLength * 4) return null

        val trimmed = trimSilence(pcm16)
        if (trimmed.size < winLength * 4) return null

        val (transposedFeatures, numFrames, paddedFrames) = computeNormalizedMelFeatures(trimmed)
        if (numFrames < 8) return null

        val featShape = longArrayOf(1L, featDim.toLong(), paddedFrames.toLong())
        val lenShape = longArrayOf(1L)

        return OnnxTensor.createTensor(env, FloatBuffer.wrap(transposedFeatures), featShape).use { sigTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(numFrames.toLong())), lenShape).use { lenTensor ->
                val inputs = buildMap<String, OnnxTensor> {
                    put(inputNames[0], sigTensor)
                    if (inputNames.size >= 2) {
                        put(inputNames[1], lenTensor)
                    }
                }
                session.run(inputs, setOf(embOutputName)).use { result ->
                    val raw = extractFlatFloats(result[0].value)
                    if (raw.isEmpty()) null else l2Normalize(raw)
                }
            }
        }
    }

    fun verify(pcm16: ShortArray, enrolledEmbeddings: List<FloatArray>): Float {
        if (enrolledEmbeddings.isEmpty()) return 0f
        val candidate = extractEmbedding(pcm16) ?: return 0f
        return matchScore(candidate, enrolledEmbeddings)
    }

    /**
     * Returns (features [featDim x paddedFrames] row-major, validFrames, paddedFrames).
     */
    internal fun computeNormalizedMelFeatures(pcm16: ShortArray): Triple<FloatArray, Int, Int> {
        val nSamples = pcm16.size
        val numFrames = ((nSamples - winLength) / hopLength) + 1
        if (numFrames <= 0) return Triple(FloatArray(0), 0, 0)

        // Normalized float waveform in [-1, 1]
        val samples = FloatArray(nSamples) { pcm16[it] / 32768f }
        // Frame-major [numFrames][featDim]
        val frameMels = Array(numFrames) { FloatArray(featDim) }
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        val power = FloatArray(FFT_SIZE / 2 + 1)

        for (t in 0 until numFrames) {
            val offset = t * hopLength
            real.fill(0f)
            imag.fill(0f)

            // Pre-emphasis (0.97) + Hann window
            real[0] = samples[offset] * (1f - PREEMPH) * hannWindow[0]
            for (i in 1 until winLength) {
                val pre = samples[offset + i] - PREEMPH * samples[offset + i - 1]
                real[i] = pre * hannWindow[i]
            }

            fftRadix2(real, imag)

            for (k in power.indices) {
                power[k] = real[k] * real[k] + imag[k] * imag[k]
            }

            val melRow = frameMels[t]
            for (m in 0 until featDim) {
                val filter = melFilters[m]
                var sum = 0.0
                for (k in filter.indices) {
                    val w = filter[k]
                    if (w != 0f) sum += w * power[k]
                }
                melRow[m] = ln(sum + LOG_ZERO_GUARD).toFloat()
            }
        }

        // Per-feature zero-mean, unit-variance normalization across time
        val paddedFrames = if (numFrames % 16 == 0) numFrames else numFrames + (16 - numFrames % 16)
        val transposed = FloatArray(featDim * paddedFrames) // zero-padded tail

        for (m in 0 until featDim) {
            var sum = 0.0
            for (t in 0 until numFrames) sum += frameMels[t][m]
            val mean = sum / numFrames

            var varSum = 0.0
            for (t in 0 until numFrames) {
                val d = frameMels[t][m] - mean
                varSum += d * d
            }
            val std = sqrt(varSum / numFrames) + 1e-5

            val base = m * paddedFrames
            for (t in 0 until numFrames) {
                transposed[base + t] = ((frameMels[t][m] - mean) / std).toFloat()
            }
        }

        return Triple(transposed, numFrames, paddedFrames)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { session.close() }
        runCatching { sessionOpts.close() }
    }

    companion object {
        private const val TAG = "JarvisVoiceprint"
        const val ASSET_PATH = "voiceprint/speakernet.onnx"
        const val SAMPLE_RATE_HZ = 16_000
        private const val FFT_SIZE = 512
        private const val PREEMPH = 0.97f
        private const val LOG_ZERO_GUARD = 5.9604645e-8 // 2^-24 (NeMo / sherpa-onnx exact floor)

        fun fromAssets(context: Context): SpeakerVoiceprintEngine {
            val bytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
            return SpeakerVoiceprintEngine(bytes)
        }

        fun l2Normalize(v: FloatArray): FloatArray {
            var sumSq = 0.0
            for (x in v) sumSq += x.toDouble() * x.toDouble()
            val norm = sqrt(sumSq).toFloat()
            if (norm <= 1e-12f) return v.copyOf()
            return FloatArray(v.size) { v[it] / norm }
        }

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || a.size != b.size) return 0f
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                val va = a[i].toDouble()
                val vb = b[i].toDouble()
                dot += va * vb
                na += va * va
                nb += vb * vb
            }
            val denom = sqrt(na) * sqrt(nb)
            if (denom <= 1e-12) return 0f
            return (dot / denom).toFloat().coerceIn(-1f, 1f)
        }

        fun computeCentroid(embeddings: List<FloatArray>): FloatArray {
            if (embeddings.isEmpty()) return FloatArray(0)
            val dim = embeddings.first().size
            val mean = FloatArray(dim)
            for (e in embeddings) {
                if (e.size != dim) continue
                for (i in 0 until dim) mean[i] += e[i]
            }
            return l2Normalize(mean)
        }

        /**
         * Combines cosine similarity against the enrolled centroid (70%) and best individual
         * sample (30%) so slight volume/prosody variations still verify reliably while rejecting
         * other speakers and TV/YouTube voices.
         */
        fun matchScore(candidate: FloatArray, enrolled: List<FloatArray>): Float {
            if (candidate.isEmpty() || enrolled.isEmpty()) return 0f
            val centroid = computeCentroid(enrolled)
            val centroidSim = cosineSimilarity(candidate, centroid)
            val maxSim = enrolled.maxOf { cosineSimilarity(candidate, it) }
            return (0.70f * centroidSim + 0.30f * maxSim).coerceIn(-1f, 1f)
        }

        fun serializeVoiceprints(embeddings: List<FloatArray>): String {
            val outer = JSONArray()
            for (vec in embeddings) {
                val inner = JSONArray()
                for (f in vec) inner.put(f.toDouble())
                outer.put(inner)
            }
            return outer.toString()
        }

        fun parseVoiceprints(json: String): List<FloatArray> {
            if (json.isBlank()) return emptyList()
            return runCatching {
                val outer = JSONArray(json)
                buildList {
                    for (i in 0 until outer.length()) {
                        val inner = outer.optJSONArray(i) ?: continue
                        if (inner.length() < 32) continue
                        val vec = FloatArray(inner.length()) { j -> inner.optDouble(j, 0.0).toFloat() }
                        add(l2Normalize(vec))
                    }
                }
            }.getOrDefault(emptyList())
        }

        /**
         * Strips dead silence at the edges using 20ms frame RMS while keeping 160ms padding
         * around active speech so short wake phrases ("Hey Jarvis") retain full vocal timbre.
         */
        fun trimSilence(pcm16: ShortArray): ShortArray {
            val frameLen = 320 // 20ms @ 16kHz
            val nFrames = pcm16.size / frameLen
            if (nFrames < 4) return pcm16

            val frameRms = FloatArray(nFrames)
            var peakRms = 0f
            for (f in 0 until nFrames) {
                var sumSq = 0.0
                val base = f * frameLen
                for (i in 0 until frameLen) {
                    val s = pcm16[base + i].toDouble()
                    sumSq += s * s
                }
                val rms = sqrt(sumSq / frameLen).toFloat()
                frameRms[f] = rms
                if (rms > peakRms) peakRms = rms
            }
            // Reject near-silent clips (< ~180 RMS on 16-bit scale)
            if (peakRms < 180f) return ShortArray(0)

            val thresh = max(160f, peakRms * 0.14f)
            var firstActive = 0
            while (firstActive < nFrames && frameRms[firstActive] < thresh) firstActive++
            var lastActive = nFrames - 1
            while (lastActive > firstActive && frameRms[lastActive] < thresh) lastActive--

            if (firstActive >= lastActive) return ShortArray(0)

            val padFrames = 8 // 160ms context on each side
            val startSample = max(0, (firstActive - padFrames) * frameLen)
            val endSample = min(pcm16.size, (lastActive + 1 + padFrames) * frameLen)
            return pcm16.copyOfRange(startSample, endSample)
        }

        /**
         * Records a fixed-length 16 kHz mono PCM-16 clip on IO dispatcher for Settings enrollment / test.
         */
        @SuppressLint("MissingPermission")
        suspend fun recordEnrollmentClip(
            context: Context,
            durationMs: Long = 2_400L,
            onRms: (Float) -> Unit = {}
        ): ShortArray? = withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return@withContext null
            }
            val totalSamples = ((SAMPLE_RATE_HZ * durationMs) / 1000L).toInt()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, 16_000)
                )
            }.getOrNull() ?: return@withContext null

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@withContext null
            }

            val out = ShortArray(totalSamples)
            var offset = 0
            try {
                recorder.startRecording()
                val chunk = ShortArray(640) // 40ms updates
                while (offset < totalSamples) {
                    val toRead = min(chunk.size, totalSamples - offset)
                    val n = recorder.read(chunk, 0, toRead, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    System.arraycopy(chunk, 0, out, offset, n)
                    offset += n

                    var sumSq = 0.0
                    for (i in 0 until n) sumSq += chunk[i].toDouble() * chunk[i].toDouble()
                    val rmsNorm = (sqrt(sumSq / n) / 6000.0).toFloat().coerceIn(0f, 1f)
                    onRms(rmsNorm)
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                onRms(0f)
            }
            if (offset < SAMPLE_RATE_HZ / 2) null else out.copyOf(offset)
        }

        /**
         * Slaney-scale triangular mel filterbank matching librosa.filters.mel(..., htk=False, norm='slaney').
         */
        internal fun buildSlaneyMelFilterbank(
            sampleRate: Int,
            nFft: Int,
            nMels: Int,
            fMin: Double,
            fMax: Double
        ): Array<FloatArray> {
            val nFreqs = nFft / 2 + 1
            val melMin = hzToMelSlaney(fMin)
            val melMax = hzToMelSlaney(fMax)
            val melPoints = DoubleArray(nMels + 2) { i ->
                melMin + (melMax - melMin) * i / (nMels + 1)
            }
            val hzPoints = DoubleArray(nMels + 2) { i -> melToHzSlaney(melPoints[i]) }
            val fftFreqs = DoubleArray(nFreqs) { k -> (sampleRate.toDouble() * k) / nFft }

            return Array(nMels) { m ->
                val fLeft = hzPoints[m]
                val fCenter = hzPoints[m + 1]
                val fRight = hzPoints[m + 2]
                val enorm = 2.0 / (fRight - fLeft)
                FloatArray(nFreqs) { k ->
                    val f = fftFreqs[k]
                    val weight = when {
                        f <= fLeft || f >= fRight -> 0.0
                        f < fCenter -> (f - fLeft) / (fCenter - fLeft)
                        else -> (fRight - f) / (fRight - fCenter)
                    }
                    (weight * enorm).toFloat()
                }
            }
        }

        private fun hzToMelSlaney(hz: Double): Double {
            val fMin = 0.0
            val fSp = 200.0 / 3.0
            val minLogHz = 1000.0
            val minLogMel = (minLogHz - fMin) / fSp
            val logStep = ln(6.4) / 27.0
            return if (hz >= minLogHz) {
                minLogMel + ln(hz / minLogHz) / logStep
            } else {
                (hz - fMin) / fSp
            }
        }

        private fun melToHzSlaney(mel: Double): Double {
            val fMin = 0.0
            val fSp = 200.0 / 3.0
            val minLogHz = 1000.0
            val minLogMel = (minLogHz - fMin) / fSp
            val logStep = ln(6.4) / 27.0
            return if (mel >= minLogMel) {
                minLogHz * kotlin.math.exp(logStep * (mel - minLogMel))
            } else {
                fMin + fSp * mel
            }
        }

        /** In-place iterative radix-2 Cooley-Tukey FFT (length must be a power of 2). */
        internal fun fftRadix2(real: FloatArray, imag: FloatArray) {
            val n = real.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tr = real[i]; real[i] = real[j]; real[j] = tr
                    val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val half = len shr 1
                val ang = -2.0 * PI / len
                val wLenR = cos(ang).toFloat()
                val wLenI = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var wR = 1f
                    var wI = 0f
                    for (k in 0 until half) {
                        val uR = real[i + k]
                        val uI = imag[i + k]
                        val vR = real[i + k + half] * wR - imag[i + k + half] * wI
                        val vI = real[i + k + half] * wI + imag[i + k + half] * wR
                        real[i + k] = uR + vR
                        imag[i + k] = uI + vI
                        real[i + k + half] = uR - vR
                        imag[i + k + half] = uI - vI
                        val nextR = wR * wLenR - wI * wLenI
                        wI = wR * wLenI + wI * wLenR
                        wR = nextR
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        private fun extractFlatFloats(value: Any?): FloatArray {
            if (value is FloatArray) return value
            val list = ArrayList<Float>(256)
            collectFloats(value, list)
            return FloatArray(list.size) { list[it] }
        }

        private fun collectFloats(obj: Any?, out: MutableList<Float>) {
            when (obj) {
                is Float -> out.add(obj)
                is FloatArray -> for (f in obj) out.add(f)
                is Array<*> -> for (item in obj) collectFloats(item, out)
            }
        }
    }
}
