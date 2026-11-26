package com.jarvis.assistant.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * 100% local, zero-cloud neural wake-word engine running openWakeWord (Apache-2.0):
 *   1) melspectrogram.onnx  : [1, 1760] raw int16-scale float32 (480 overlap + 1280 chunk) -> 8 x 32 mel frames (x/10 + 2)
 *   2) embedding_model.onnx : [1, 76, 32, 1] sliding mel window -> 96-dim speech embedding
 *   3) hey_jarvis_v0.1.onnx : [1, 16, 96] rolling embeddings -> wake probability [0.0 .. 1.0]
 *
 * Every OnnxTensor and OrtSession.Result is closed deterministically via .use { } for flat memory.
 */
class OpenWakeWordEngine(
    melBytes: ByteArray,
    embeddingBytes: ByteArray,
    wakeWordBytes: ByteArray
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOpts: OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }

    private val melSession: OrtSession = env.createSession(melBytes, sessionOpts)
    private val embSession: OrtSession = env.createSession(embeddingBytes, sessionOpts)
    private val wakeSession: OrtSession = env.createSession(wakeWordBytes, sessionOpts)

    private val melInputName: String = melSession.inputNames.first()
    private val embInputName: String = embSession.inputNames.first()
    private val wakeInputName: String = wakeSession.inputNames.first()

    val requiredFeatureFrames: Int = run {
        val info = wakeSession.inputInfo[wakeInputName]?.info as? TensorInfo
        val dim1 = info?.shape?.getOrNull(1)?.toInt() ?: 16
        if (dim1 in 4..64) dim1 else 16
    }

    // Streaming buffers matching dscripka/openWakeWord AudioFeatures exactly
    private val prevTail = FloatArray(OVERLAP_SAMPLES) // 480 samples (160 * 3)
    private val melInputBuf = FloatArray(OVERLAP_SAMPLES + CHUNK_SAMPLES) // 1760 samples
    private val melFrames = ArrayDeque<FloatArray>(MAX_MEL_FRAMES)
    private val featureFrames = ArrayDeque<FloatArray>(requiredFeatureFrames + 4)

    private val embInputFlat = FloatArray(MEL_WINDOW_FRAMES * MEL_BINS)
    private val wakeInputFlat = FloatArray(requiredFeatureFrames * EMBEDDING_DIM)

    private var closed = false

    init {
        val melInShape = (melSession.inputInfo[melInputName]?.info as? TensorInfo)?.shape?.contentToString()
        val embInShape = (embSession.inputInfo[embInputName]?.info as? TensorInfo)?.shape?.contentToString()
        val wakeInShape = (wakeSession.inputInfo[wakeInputName]?.info as? TensorInfo)?.shape?.contentToString()
        Log.i(
            TAG,
            "Loaded openWakeWord ONNX (mel=$melInputName$melInShape, emb=$embInputName$embInShape, wake=$wakeInputName$wakeInShape, frames=$requiredFeatureFrames)"
        )
    }

    @Synchronized
    fun reset() {
        prevTail.fill(0f)
        melFrames.clear()
        featureFrames.clear()
    }

    /**
     * Feeds one 80ms chunk (1280 samples @ 16 kHz mono 16-bit PCM) into the 3-stage ONNX pipeline.
     * Returns wake-word probability in [0f, 1f], or -1f while initial context buffers are warming up (~1.9s).
     */
    @Synchronized
    fun processPcm16Chunk(chunk: ShortArray): Float {
        if (closed || chunk.size != CHUNK_SAMPLES) return -1f

        // 1. Build 1760-sample raw int16-scale float input (previous 480 overlap + new 1280 chunk)
        System.arraycopy(prevTail, 0, melInputBuf, 0, OVERLAP_SAMPLES)
        for (i in 0 until CHUNK_SAMPLES) {
            melInputBuf[OVERLAP_SAMPLES + i] = chunk[i].toFloat()
        }
        System.arraycopy(melInputBuf, CHUNK_SAMPLES, prevTail, 0, OVERLAP_SAMPLES)

        // Stage 1: melspectrogram.onnx -> 8 frames x 32 bins, calibrated with (x / 10 + 2)
        val rawMel = runMelSpectrogram(melInputBuf)
        val numNewFrames = rawMel.size / MEL_BINS
        if (numNewFrames <= 0) return -1f

        for (f in 0 until numNewFrames) {
            val frame = FloatArray(MEL_BINS)
            val base = f * MEL_BINS
            for (b in 0 until MEL_BINS) {
                frame[b] = rawMel[base + b] / 10.0f + 2.0f
            }
            melFrames.addLast(frame)
            while (melFrames.size > MAX_MEL_FRAMES) {
                melFrames.removeFirst()
            }
        }

        if (melFrames.size < MEL_WINDOW_FRAMES) return -1f

        // Stage 2: embedding_model.onnx on latest 76 mel frames -> 96-dim embedding
        val startFrame = melFrames.size - MEL_WINDOW_FRAMES
        var idx = 0
        for (f in 0 until MEL_WINDOW_FRAMES) {
            val row = melFrames[startFrame + f]
            System.arraycopy(row, 0, embInputFlat, idx, MEL_BINS)
            idx += MEL_BINS
        }
        val embedding = runEmbedding(embInputFlat)
        if (embedding.size < EMBEDDING_DIM) return -1f

        featureFrames.addLast(if (embedding.size == EMBEDDING_DIM) embedding else embedding.copyOf(EMBEDDING_DIM))
        while (featureFrames.size > requiredFeatureFrames) {
            featureFrames.removeFirst()
        }

        if (featureFrames.size < requiredFeatureFrames) return -1f

        // Stage 3: hey_jarvis_v0.1.onnx on latest 16 embeddings -> scalar confidence score
        var fIdx = 0
        for (i in 0 until requiredFeatureFrames) {
            System.arraycopy(featureFrames[i], 0, wakeInputFlat, fIdx, EMBEDDING_DIM)
            fIdx += EMBEDDING_DIM
        }
        return runWakeClassifier(wakeInputFlat).coerceIn(0f, 1f)
    }

    private fun runMelSpectrogram(audio1760: FloatArray): FloatArray {
        val shape = longArrayOf(1L, audio1760.size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(audio1760), shape).use { tensor ->
            melSession.run(mapOf(melInputName to tensor)).use { result ->
                return extractFlatFloats(result[0].value)
            }
        }
    }

    private fun runEmbedding(mel76x32: FloatArray): FloatArray {
        val shape = longArrayOf(1L, MEL_WINDOW_FRAMES.toLong(), MEL_BINS.toLong(), 1L)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mel76x32), shape).use { tensor ->
            embSession.run(mapOf(embInputName to tensor)).use { result ->
                return extractFlatFloats(result[0].value)
            }
        }
    }

    private fun runWakeClassifier(features16x96: FloatArray): Float {
        val shape = longArrayOf(1L, requiredFeatureFrames.toLong(), EMBEDDING_DIM.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features16x96), shape).use { tensor ->
            wakeSession.run(mapOf(wakeInputName to tensor)).use { result ->
                val out = extractFlatFloats(result[0].value)
                return out.firstOrNull() ?: 0f
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { wakeSession.close() }
        runCatching { embSession.close() }
        runCatching { melSession.close() }
        runCatching { sessionOpts.close() }
    }

    companion object {
        private const val TAG = "OpenWakeWord"
        const val SAMPLE_RATE_HZ = 16_000
        const val CHUNK_SAMPLES = 1_280      // 80 ms @ 16 kHz
        private const val OVERLAP_SAMPLES = 480 // 160 * 3
        private const val MEL_BINS = 32
        private const val MEL_WINDOW_FRAMES = 76
        private const val MAX_MEL_FRAMES = 96
        private const val EMBEDDING_DIM = 96

        fun fromAssets(context: Context): OpenWakeWordEngine {
            val assets = context.assets
            val mel = assets.open("wakeword/melspectrogram.onnx").use { it.readBytes() }
            val emb = assets.open("wakeword/embedding_model.onnx").use { it.readBytes() }
            val wake = assets.open("wakeword/hey_jarvis_v0.1.onnx").use { it.readBytes() }
            return OpenWakeWordEngine(mel, emb, wake)
        }

        /** Linear resampler (e.g. 24 kHz TTS PCM -> 16 kHz for self-testing). */
        fun resampleLinear(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
            if (fromRate == toRate || input.isEmpty()) return input
            val outLen = ((input.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
            val ratio = fromRate.toDouble() / toRate.toDouble()
            return ShortArray(outLen) { i ->
                val srcPos = i * ratio
                val idx = srcPos.toInt()
                val frac = srcPos - idx
                val s0 = input[idx.coerceIn(0, input.lastIndex)].toDouble()
                val s1 = input[(idx + 1).coerceIn(0, input.lastIndex)].toDouble()
                (s0 + (s1 - s0) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
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
