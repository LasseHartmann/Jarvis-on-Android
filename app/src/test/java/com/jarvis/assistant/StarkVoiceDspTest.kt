package com.jarvis.assistant

import com.jarvis.assistant.voice.StarkVoiceDsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class StarkVoiceDspTest {

    @Test
    fun processesIntercomPcmWithoutClippingOrSilence() {
        val sampleRate = 24_000
        val input = ShortArray(sampleRate / 4) { i ->
            (sin(2.0 * PI * 440.0 * i / sampleRate) * 12_000).toInt().toShort()
        }
        val output = StarkVoiceDsp.processIntercom(input, sampleRate)
        assertEquals(input.size, output.size)
        val peak = output.maxOf { abs(it.toInt()) }
        assertTrue("Expected audible processed signal, got peak=$peak", peak > 2_000)
        assertTrue("Expected no hard overflow", peak <= Short.MAX_VALUE)
    }

    @Test
    fun decodesMinimalPcm16WavHeader() {
        val samples = shortArrayOf(1000, -1000, 2000, -2000)
        val dataBytes = samples.size * 2
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataBytes)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)      // PCM
        bb.putShort(1)      // Mono
        bb.putInt(24_000)   // Sample rate
        bb.putInt(48_000)   // Byte rate
        bb.putShort(2)      // Block align
        bb.putShort(16)     // Bits per sample
        bb.put("data".toByteArray())
        bb.putInt(dataBytes)
        samples.forEach { bb.putShort(it) }

        val decoded = StarkVoiceDsp.decodeWavFile(bb.array())
        assertNotNull(decoded)
        assertEquals(24_000, decoded!!.sampleRateHz)
        assertEquals(4, decoded.samples.size)
        assertEquals(1000.toShort(), decoded.samples[0])
        assertEquals((-2000).toShort(), decoded.samples[3])
    }
}
