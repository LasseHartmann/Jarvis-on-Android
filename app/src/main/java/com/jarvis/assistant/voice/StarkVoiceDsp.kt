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
 * 2. Presence peaking EQ (~2.7 kHz, +3.2 dB) for crisp British RP articulation
 * 3. Broadcast soft-knee dynamic leveling
 * 4. Subtle short multi-tap holographic helmet acoustic reflections (11ms / 22ms / 35ms @ 8% wet)
 */
object StarkVoiceDsp {

    fun processIntercom(pcm16Mono: ShortArray, sampleRateHz: Int): ShortArray {
        if (pcm16Mono.isEmpty() || sampleRateHz <= 0) return pcm16Mono
        val n = pcm16Mono.size
        val work = FloatArray(n) { pcm16Mono[it] / 32768f }

        // 1. One-pole high-pass (~130 Hz)
        val rc = 1.0f / (2.0f * PI.toFloat() * 130f)
        val dt = 1.0f / sampleRateHz.toFloat()
        val hpAlpha = rc / (rc + dt)
        var prevIn = 0f
        var prevHp = 0f
        for (i in 0 until n) {
            val x = work[i]
            val y = hpAlpha * (prevHp + x - prevIn)
            prevIn = x
            prevHp = y
            work[i] = y
        }

        // 2. Biquad peaking EQ at 2700 Hz (+3.2 dB, Q=1.15) for Stark comms crispness
        val eq = computePeakingBiquad(freqHz = 2700f, sampleRateHz = sampleRateHz.toFloat(), gainDb = 3.2f, q = 1.15f)
        var x1 = 0f
        var x2 = 0f
        var y1 = 0f
        var y2 = 0f
        for (i in 0 until n) {
            val x0 = work[i]
            val y0 = eq.b0 * x0 + eq.b1 * x1 + eq.b2 * x2 - eq.a1 * y1 - eq.a2 * y2
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
            work[i] = y0
        }

        // 3. Subtle holographic helmet early reflections (multi-tap comb)
        val d1 = (sampleRateHz * 0.011f).toInt().coerceAtLeast(1)
        val d2 = (sampleRateHz * 0.022f).toInt().coerceAtLeast(2)
        val d3 = (sampleRateHz * 0.035f).toInt().coerceAtLeast(3)
        val wet = FloatArray(n)
        for (i in 0 until n) {
            var r = 0f
            if (i >= d1) r += work[i - d1] * 0.48f
            if (i >= d2) r -= work[i - d2] * 0.31f
            if (i >= d3) r += work[i - d3] * 0.21f
            wet[i] = r
        }

        // 4. Mix (91% dry + 9% metallic helmet sheen) + smooth broadcast leveling
        val out = ShortArray(n)
        var env = 0.08f
        for (i in 0 until n) {
            val mixed = work[i] * 0.91f + wet[i] * 0.09f
            val level = abs(mixed)
            env = if (level > env) env * 0.985f + level * 0.015f else env * 0.9992f + level * 0.0008f
            val gain = (0.24f / env.coerceAtLeast(0.09f)).coerceIn(0.65f, 1.55f)
            val shaped = tanh((mixed * gain * 1.08f).toDouble()).toFloat()
            out[i] = (shaped * 31500f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
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
