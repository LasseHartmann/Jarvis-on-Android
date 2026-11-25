package com.jarvis.assistant

import com.jarvis.assistant.agent.AiProvider
import com.jarvis.assistant.agent.AuditLogEntry
import com.jarvis.assistant.agent.JarvisMemory
import com.jarvis.assistant.agent.JarvisOrchestrator
import com.jarvis.assistant.agent.JarvisProtocol
import com.jarvis.assistant.agent.JarvisSettings
import com.jarvis.assistant.agent.LlmClient
import com.jarvis.assistant.agent.LlmMessage
import com.jarvis.assistant.agent.LlmResponse
import com.jarvis.assistant.agent.RiskLevel
import com.jarvis.assistant.agent.SafetyMode
import com.jarvis.assistant.agent.ToolCall
import com.jarvis.assistant.agent.ToolResult
import com.jarvis.assistant.data.JarvisStore
import com.jarvis.assistant.tools.ToolExecutor
import com.jarvis.assistant.tools.ToolSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisOrchestratorTest {

    private class FakeStore : JarvisStore {
        val audits = mutableListOf<AuditLogEntry>()
        override fun getMemoriesSnapshot(): List<JarvisMemory> = emptyList()
        override fun upsertMemory(key: String, content: String, category: String) = Unit
        override fun deleteMemoryByKey(key: String): Boolean = false
        override fun getProtocolsSnapshot(): List<JarvisProtocol> = emptyList()
        override fun logAudit(
            toolName: String,
            argumentsJson: String,
            riskLevel: RiskLevel,
            approved: Boolean,
            success: Boolean,
            summary: String
        ) {
            audits.add(AuditLogEntry(0L, 0L, toolName, argumentsJson, riskLevel, approved, success, summary))
        }
    }

    private class FakeToolExecutor(
        private val specs: Map<String, ToolSpec>,
        private val onExec: (ToolCall) -> ToolResult
    ) : ToolExecutor {
        val executed = mutableListOf<String>()
        override fun allSpecs(): List<ToolSpec> = specs.values.toList()
        override fun findSpec(name: String): ToolSpec? = specs[name]
        override suspend fun execute(call: ToolCall): ToolResult {
            executed.add(call.name)
            return onExec(call)
        }
    }

    @Test
    fun multiStepAgentLoopExecutesToolAndReturnsFinalAnswer() = runTest {
        val store = FakeStore()
        val executor = FakeToolExecutor(
            specs = mapOf("open_app" to ToolSpec("open_app", "Open app", defaultRisk = RiskLevel.SAFE))
        ) { call -> ToolResult(call.id, call.name, true, "Launched Spotify") }

        var turns = 0
        val fakeLlm = object : LlmClient {
            override suspend fun complete(systemPrompt: String, messages: List<LlmMessage>, tools: List<ToolSpec>): LlmResponse {
                turns++
                return if (turns == 1) {
                    LlmResponse("", listOf(ToolCall("t1", "open_app", JSONObject().put("app_name", "Spotify"))))
                } else {
                    LlmResponse("Spotify is open and ready, Sir.")
                }
            }
        }

        val orchestrator = JarvisOrchestrator(
            toolExecutor = executor,
            store = store,
            settingsProvider = { JarvisSettings(provider = AiProvider.GEMINI, geminiApiKey = "key", localReflexFirst = false) },
            telemetrySummaryProvider = { "Xiaomi 15T Pro | Battery 88%" },
            llmClientFactory = { fakeLlm },
            foregroundPackageProvider = { "com.jarvis.assistant" },
            lastScreenNodesProvider = { emptyList() }
        )

        val outcome = orchestrator.processUserInput("Open Spotify and play my jazz mix", emptyList())
        assertEquals("Spotify is open and ready, Sir.", outcome.reply)
        assertEquals(listOf("open_app"), executor.executed)
        assertEquals(1, store.audits.size)
        assertTrue(store.audits.first().approved)
    }

    @Test
    fun sensitiveActionPausesForConfirmationAndRespectsDenial() = runTest {
        val store = FakeStore()
        val executor = FakeToolExecutor(
            specs = mapOf("send_sms" to ToolSpec("send_sms", "Send SMS", defaultRisk = RiskLevel.SENSITIVE))
        ) { call -> ToolResult(call.id, call.name, true, "SMS sent") }

        var turns = 0
        val fakeLlm = object : LlmClient {
            override suspend fun complete(systemPrompt: String, messages: List<LlmMessage>, tools: List<ToolSpec>): LlmResponse {
                turns++
                return if (turns == 1) {
                    LlmResponse("", listOf(ToolCall("s1", "send_sms", JSONObject().put("phone_number", "+49111").put("message", "Test"))))
                } else {
                    LlmResponse("Understood, Sir. I cancelled the SMS.")
                }
            }
        }

        val orchestrator = JarvisOrchestrator(
            toolExecutor = executor,
            store = store,
            settingsProvider = { JarvisSettings(provider = AiProvider.GEMINI, geminiApiKey = "key", safetyMode = SafetyMode.BALANCED, localReflexFirst = false) },
            telemetrySummaryProvider = { "Xiaomi 15T Pro" },
            llmClientFactory = { fakeLlm },
            foregroundPackageProvider = { "com.jarvis.assistant" },
            lastScreenNodesProvider = { emptyList() }
        )

        val deferredOutcome = async {
            orchestrator.processUserInput("Text +49111 saying Test", emptyList())
        }

        // Wait briefly for orchestrator to suspend on confirmation gate
        var waits = 0
        while (orchestrator.pendingConfirmation.value == null && waits < 50) {
            delay(10)
            waits++
        }
        assertNotNull(orchestrator.pendingConfirmation.value)
        orchestrator.resolveConfirmation(approved = false)

        val outcome = deferredOutcome.await()
        assertTrue(executor.executed.isEmpty())
        assertEquals(1, store.audits.size)
        assertFalse(store.audits.first().approved)
        assertTrue(outcome.reply.contains("cancelled", ignoreCase = true))
    }
}
