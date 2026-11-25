package com.jarvis.assistant.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class StarkCue {
    WAKE,
    EXECUTE,
    AUTH_ALERT,
    DONE,
    ABORT
}

class StarkSoundEffects(private val enabledProvider: () -> Boolean) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun play(cue: StarkCue) {
        if (!enabledProvider()) return
        scope.launch {
            runCatching {
                val pcm = when (cue) {
                    StarkCue.WAKE -> synthChirp(520.0, 1040.0, 110, 0.22)
                    StarkCue.EXECUTE -> synthDualTone(780.0, 1170.0, 75, 0.16)
                    StarkCue.AUTH_ALERT -> concat(
                        synthDualTone(660.0, 880.0, 90, 0.24),
                        silence(45),
                        synthDualTone(660.0, 880.0, 110, 0.24)
                    )
                    StarkCue.DONE -> synthChirp(680.0, 980.0, 95, 0.14)
                    StarkCue.ABORT -> synthChirp(620.0, 260.0, 140, 0.22)
                }
                playPcm(pcm)
            }
        }
    }

    private fun synthChirp(startHz: Double, endHz: Double, durationMs: Int, gain: Double): ShortArray {
        val count = (SAMPLE_RATE * durationMs) / 1000
        val out = ShortArray(count)
        var phase = 0.0
        for (i in 0 until count) {
            val t = i.toDouble() / count
            val freq = startHz + (endHz - startHz) * t
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val env = sin(PI * t) * exp(-1.2 * t)
            val sample = (sin(phase) + 0.35 * sin(phase * 2.0)) * env * gain
            out[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun synthDualTone(hz1: Double, hz2: Double, durationMs: Int, gain: Double): ShortArray {
        val count = (SAMPLE_RATE * durationMs) / 1000
        val out = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toDouble() / count
            val env = sin(PI * t)
            val sec = i.toDouble() / SAMPLE_RATE
            val sample = (0.65 * sin(2.0 * PI * hz1 * sec) + 0.35 * sin(2.0 * PI * hz2 * sec)) * env * gain
            out[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun silence(durationMs: Int): ShortArray = ShortArray((SAMPLE_RATE * durationMs) / 1000)

    private fun concat(vararg arrays: ShortArray): ShortArray {
        val total = arrays.sumOf { it.size }
        val res = ShortArray(total)
        var offset = 0
        for (a in arrays) {
            System.arraycopy(a, 0, res, offset, a.size)
            offset += a.size
        }
        return res
    }

    private fun playPcm(samples: ShortArray) {
        val byteLen = samples.size * 2
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, byteLen))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(samples, 0, samples.size)
        track.play()
        val sleepMs = ((samples.size * 1000L) / SAMPLE_RATE) + 40L
        Thread.sleep(sleepMs)
        track.stop()
        track.release()
    }

    companion object {
        private const val SAMPLE_RATE = 24_000
    }
}
