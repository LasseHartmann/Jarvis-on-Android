package com.jarvis.assistant

import com.jarvis.assistant.agent.GeminiLlmClient
import com.jarvis.assistant.voice.SentenceChunker
import com.jarvis.assistant.voice.StarkVoiceDsp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class StreamingPipelineTest {

    @Test
    fun streamingDspMatchesBatchOutputBitForBitAcrossArbitraryChunkSizes() {
        val sampleRate = 24_000
        val total = 4_800
        val input = ShortArray(total) { i ->
            (sin(2.0 * PI * 440.0 * i / sampleRate) * 14_000.0).toInt().toShort()
        }

        val batchOut = StarkVoiceDsp.processIntercom(input, sampleRate)

        val processor = StarkVoiceDsp.StreamingProcessor(sampleRate)
        val chunkSizes = intArrayOf(37, 128, 511, 1024, 19, 777)
        val streamed = ShortArray(total)
        var offset = 0
        var cIdx = 0
        while (offset < total) {
            val len = minOf(chunkSizes[cIdx % chunkSizes.size], total - offset)
            val slice = input.copyOfRange(offset, offset + len)
            val outSlice = processor.processChunk(slice)
            System.arraycopy(outSlice, 0, streamed, offset, outSlice.size)
            offset += len
            cIdx++
        }

        assertArrayEquals(batchOut, streamed)
    }

    @Test
    fun sentenceChunkerProtectsJarvisAndAbbreviationsWhileEmittingFast() {
        val chunker = SentenceChunker(minSentenceChars = 20)
        val emitted = mutableListOf<String>()

        val deltas = listOf(
            "J.A.R.V.I.S. ",
            "is online and operational, Sir. ",
            "Battery is at 3.5 volts, z.B. ready for ",
            "Dr. Banner! ",
            "All systems nominal."
        )
        for (d in deltas) {
            emitted.addAll(chunker.append(d))
        }
        chunker.flush()?.let { emitted.add(it) }

        assertEquals(3, emitted.size)
        assertEquals("J.A.R.V.I.S. is online and operational, Sir.", emitted[0])
        assertEquals("Battery is at 3.5 volts, z.B. ready for Dr. Banner!", emitted[1])
        assertEquals("All systems nominal.", emitted[2])
    }

    @Test
    fun geminiSseAccumulatorFiltersInternalThoughtsAndPreservesThoughtSignature() {
        val client = GeminiLlmClient(apiKey = "test-key", model = "gemini-4-hydrogen")
        val acc = GeminiLlmClient.StreamAccumulator()

        val chunk1 = """
            {"candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"Thinking silently..."}]}}]}
        """.trimIndent()
        val chunk2 = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"Good afternoon, "}]}}]}
        """.trimIndent()
        val chunk3 = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"Sir."}]}}]}
        """.trimIndent()

        assertEquals("", client.consumeSseChunk(chunk1, acc))
        assertEquals("Good afternoon, ", client.consumeSseChunk(chunk2, acc))
        assertEquals("Sir.", client.consumeSseChunk(chunk3, acc))

        val resp = acc.toResponse()
        assertEquals("Good afternoon, Sir.", resp.text)
        assertTrue(resp.toolCalls.isEmpty())

        // Also test streaming tool call with thoughtSignature
        val toolAcc = GeminiLlmClient.StreamAccumulator()
        val toolChunk = """
            {"candidates":[{"content":{"role":"model","parts":[{"thoughtSignature":"SIG_STREAM_42","functionCall":{"name":"open_app","args":{"app_name":"WhatsApp"}}}]}}]}
        """.trimIndent()
        assertEquals("", client.consumeSseChunk(toolChunk, toolAcc))
        val toolResp = toolAcc.toResponse()
        assertEquals(1, toolResp.toolCalls.size)
        assertEquals("SIG_STREAM_42", toolResp.toolCalls[0].thoughtSignature)
        assertEquals("WhatsApp", toolResp.toolCalls[0].arguments.getString("app_name"))
    }
}
