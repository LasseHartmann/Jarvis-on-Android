package com.jarvis.assistant.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

data class DecodedPcm(
    val samples: ShortArray,
    val sampleRateHz: Int
)

/**
 * Pure Kotlin real-time audio DSP inspired by Iron Man's helmet J.A.R.V.I.S. intercom:
 * 1. High-pass clarity filter (~130 Hz) to remove mud
 * 2. Presence peaking EQ (~2.7 kHz, +3.2 dB) for crisp articulation
 * 3. Subtle short multi-tap holographic helmet acoustic reflections (11ms / 22ms / 35ms @ 9% wet)
 * 4. Broadcast soft-knee dynamic leveling
 *
 * Supports both one-shot batch processing and click-free stateful chunk streaming.
 */
object StarkVoiceDsp {

    class StreamingProcessor(val sampleRateHz: Int) {
        private val validRate = sampleRateHz.coerceAtLeast(8000)
        private val hpAlpha: Float
        private val eq: Biquad
        private val d1: Int
        private val d2: Int
        private val d3: Int
        private val ringBuf: FloatArray
        private var ringIdx = 0

        private var prevIn = 0f
        private var prevHp = 0f
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f
        private var env = 0.08f

        init {
            val rc = 1.0f / (2.0f * PI.toFloat() * 130f)
            val dt = 1.0f / validRate.toFloat()
            hpAlpha = rc / (rc + dt)
            eq = computePeakingBiquad(freqHz = 2700f, sampleRateHz = validRate.toFloat(), gainDb = 3.2f, q = 1.15f)
            d1 = (validRate * 0.011f).toInt().coerceAtLeast(1)
            d2 = (validRate * 0.022f).toInt().coerceAtLeast(2)
            d3 = (validRate * 0.035f).toInt().coerceAtLeast(3)
            ringBuf = FloatArray(d3 + 1)
        }

        fun processChunk(pcm16Mono: ShortArray): ShortArray {
            if (pcm16Mono.isEmpty()) return pcm16Mono
            val n = pcm16Mono.size
            val out = ShortArray(n)
            val ringLen = ringBuf.size

            for (i in 0 until n) {
                val sample = pcm16Mono[i] / 32768f

                // 1. One-pole high-pass (~130 Hz)
                val hp = hpAlpha * (prevHp + sample - prevIn)
                prevIn = sample
                prevHp = hp

                // 2. Biquad peaking EQ at 2700 Hz (+3.2 dB)
                val eqOut = eq.b0 * hp + eq.b1 * x1 + eq.b2 * x2 - eq.a1 * y1 - eq.a2 * y2
                x2 = x1
                x1 = hp
                y2 = y1
                y1 = eqOut

                // 3. Multi-tap comb reflections via ring buffer (zero boundary clicks)
                ringBuf[ringIdx] = eqOut
                var idx1 = ringIdx - d1; if (idx1 < 0) idx1 += ringLen
                var idx2 = ringIdx - d2; if (idx2 < 0) idx2 += ringLen
                var idx3 = ringIdx - d3; if (idx3 < 0) idx3 += ringLen
                val wet = ringBuf[idx1] * 0.48f - ringBuf[idx2] * 0.31f + ringBuf[idx3] * 0.21f
                ringIdx++
                if (ringIdx >= ringLen) ringIdx = 0

                // 4. Mix (91% dry + 9% metallic helmet sheen) + smooth broadcast leveling
                val mixed = eqOut * 0.91f + wet * 0.09f
                val level = abs(mixed)
                env = if (level > env) env * 0.985f + level * 0.015f else env * 0.9992f + level * 0.0008f
                val gain = (0.24f / env.coerceAtLeast(0.09f)).coerceIn(0.65f, 1.55f)
                val shaped = tanh((mixed * gain * 1.08f).toDouble()).toFloat()
                out[i] = (shaped * 31500f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return out
        }
    }

    fun processIntercom(pcm16Mono: ShortArray, sampleRateHz: Int): ShortArray {
        if (pcm16Mono.isEmpty() || sampleRateHz <= 0) return pcm16Mono
        return StreamingProcessor(sampleRateHz).processChunk(pcm16Mono)
    }

    fun pcmBytesToShortsLittleEndian(bytes: ByteArray): ShortArray {
        val count = bytes.size / 2
        val shorts = ShortArray(count)
        val buf = ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().get(shorts)
        return shorts
    }

    /** Parses a standard RIFF PCM16 WAV file into mono shorts + sample rate. */
    fun decodeWavFile(wavBytes: ByteArray): DecodedPcm? {
        if (wavBytes.size < 44) return null
        if (wavBytes[0].toInt().toChar() != 'R' || wavBytes[1].toInt().toChar() != 'I' ||
            wavBytes[2].toInt().toChar() != 'F' || wavBytes[3].toInt().toChar() != 'F'
        ) {
            return null
        }

        val bb = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1
        var sampleRate = 24_000
        var bitsPerSample = 16
        var dataStart = -1
        var dataSize = 0

        while (pos + 8 <= wavBytes.size) {
            val id = String(wavBytes, pos, 4)
            val chunkSize = bb.getInt(pos + 4).coerceAtLeast(0)
            val chunkData = pos + 8
            if (id == "fmt " && chunkData + 16 <= wavBytes.size) {
                channels = bb.getShort(chunkData + 2).toInt().coerceAtLeast(1)
                sampleRate = bb.getInt(chunkData + 4).coerceIn(8000, 96000)
                bitsPerSample = bb.getShort(chunkData + 14).toInt()
            } else if (id == "data") {
                dataStart = chunkData
                dataSize = chunkSize.coerceAtMost(wavBytes.size - chunkData)
                break
            }
            pos = chunkData + chunkSize + (chunkSize and 1)
        }

        if (dataStart < 0 || dataSize < 2 || bitsPerSample != 16) return null
        val totalShorts = dataSize / 2
        val raw = ShortArray(totalShorts)
        ByteBuffer.wrap(wavBytes, dataStart, totalShorts * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(raw)

        val mono = if (channels == 1) {
            raw
        } else {
            val frames = totalShorts / channels
            ShortArray(frames) { f ->
                var acc = 0
                for (c in 0 until channels) acc += raw[f * channels + c]
                (acc / channels).toShort()
            }
        }
        return DecodedPcm(samples = mono, sampleRateHz = sampleRate)
    }

    private data class Biquad(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)

    private fun computePeakingBiquad(freqHz: Float, sampleRateHz: Float, gainDb: Float, q: Float): Biquad {
        val a = Math.pow(10.0, gainDb / 40.0).toFloat()
        val omega = (2.0 * PI * freqHz / sampleRateHz).toFloat()
        val sn = sin(omega)
        val cs = cos(omega)
        val alpha = sn / (2f * q)

        val b0 = 1f + alpha * a
        val b1 = -2f * cs
        val b2 = 1f - alpha * a
        val a0 = 1f + alpha / a
        val a1 = -2f * cs
        val a2 = 1f - alpha / a
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}
