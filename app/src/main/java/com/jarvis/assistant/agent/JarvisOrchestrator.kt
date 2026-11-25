package com.jarvis.assistant.agent

import com.jarvis.assistant.data.JarvisStore
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.UiNodeSnapshot
import com.jarvis.assistant.tools.ToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class OrchestratorOutcome(
    val reply: String,
    val traces: List<ToolExecutionTrace>,
    val aborted: Boolean = false
)

class JarvisOrchestrator(
    private val toolExecutor: ToolExecutor,
    private val store: JarvisStore,
    private val settingsProvider: () -> JarvisSettings,
    private val telemetrySummaryProvider: () -> String,
    private val llmClientFactory: (JarvisSettings) -> LlmClient?,
    private val foregroundPackageProvider: () -> String = { JarvisAccessibilityService.currentForegroundPackage },
    private val lastScreenNodesProvider: () -> List<UiNodeSnapshot> = { JarvisAccessibilityService.lastSnapshot?.nodes.orEmpty() },
    private val onStateFeedback: (JarvisState) -> Unit = {}
) {

    private val _state = MutableStateFlow(JarvisState.IDLE)
    val state: StateFlow<JarvisState> = _state.asStateFlow()

    private val _statusDetail = MutableStateFlow("All systems standing by")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _liveTraces = MutableStateFlow<List<ToolExecutionTrace>>(emptyList())
    val liveTraces: StateFlow<List<ToolExecutionTrace>> = _liveTraces.asStateFlow()

    @Volatile
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null
    private val abortFlag = AtomicBoolean(false)

    fun setStateExternal(newState: JarvisState, detail: String = newState.label) {
        _state.value = newState
        _statusDetail.value = detail
    }

    fun resolveConfirmation(approved: Boolean) {
        val pending = confirmationDeferred
        confirmationDeferred = null
        _pendingConfirmation.value = null
        pending?.complete(approved)
    }

    fun abortCurrentTask() {
        abortFlag.set(true)
        resolveConfirmation(false)
        transition(JarvisState.IDLE, "Operation aborted by user")
    }

    suspend fun processUserInput(
        rawInput: String,
        recentHistory: List<ChatMessage>
    ): OrchestratorOutcome {
        abortFlag.set(false)
        _liveTraces.value = emptyList()
        val settings = settingsProvider()
        val traces = mutableListOf<ToolExecutionTrace>()

        // Expand custom/built-in protocols when triggered by name or trigger phrase
        val effectiveInput = expandProtocolIfMatched(rawInput, settings)

        try {
            // 1. Try fast offline Local Reflex Router when enabled or when no cloud LLM is configured
            val localPlan = if (settings.localReflexFirst || !settings.hasActiveCloudKey()) {
                LocalCommandRouter.match(rawInput) ?: LocalCommandRouter.match(effectiveInput)
            } else null

            if (localPlan != null) {
                transition(JarvisState.EXECUTING, "Reflex Core executing...")
                val results = mutableListOf<ToolResult>()
                for (call in localPlan.toolCalls) {
                    if (abortFlag.get()) {
                        return OrchestratorOutcome("Operation aborted, ${settings.userTitle}.", traces, aborted = true)
                    }
                    val (result, trace) = runGuardedToolCall(call, settings)
                    traces.add(trace)
                    _liveTraces.value = traces.toList()
                    results.add(result)
                }
                val reply = localPlan.composeReply(results, settings.userTitle)
                transition(JarvisState.IDLE, "Reflex task complete")
                return OrchestratorOutcome(reply = reply, traces = traces)
            }

            // 2. Escalate to Cloud / Local LLM Agentic Loop
            val llmClient = llmClientFactory(settings)
            if (llmClient == null) {
                transition(JarvisState.IDLE, "Offline Reflex Core active")
                return OrchestratorOutcome(
                    reply = "My cloud neural core is not connected yet, ${settings.userTitle}. " +
                        "Instant offline commands (flashlight, volume, brightness, open apps, timers, alarms, media, diagnostics, and Night/Focus/Stealth protocols) work right now. " +
                        "Add a Google Gemini key or local Ollama endpoint in the Core Settings tab to unlock full conversational intelligence and autonomous multi-step screen control.",
                    traces = traces
                )
            }

            val systemPrompt = JarvisPersona.buildSystemPrompt(
                settings = settings,
                telemetryContext = telemetrySummaryProvider(),
                memories = store.getMemoriesSnapshot()
            )
            val messages = mutableListOf<LlmMessage>()
            recentHistory.takeLast(12).forEach { m ->
                when (m.role) {
                    MessageRole.USER -> messages.add(LlmMessage.User(m.content))
                    MessageRole.ASSISTANT -> messages.add(LlmMessage.Assistant(m.content))
                    MessageRole.SYSTEM -> Unit
                }
            }
            messages.add(LlmMessage.User(effectiveInput))

            val toolSpecs = toolExecutor.allSpecs()
            val maxSteps = settings.maxAgentSteps.coerceIn(2, 20)

            for (step in 1..maxSteps) {
                if (abortFlag.get()) {
                    transition(JarvisState.IDLE, "Aborted")
                    return OrchestratorOutcome("Task aborted on your mark, ${settings.userTitle}.", traces, aborted = true)
                }

                transition(JarvisState.THINKING, "Neural cycle $step/$maxSteps...")
                val response = llmClient.complete(systemPrompt, messages, toolSpecs)

                if (abortFlag.get()) {
                    transition(JarvisState.IDLE, "Aborted")
                    return OrchestratorOutcome("Task aborted, ${settings.userTitle}.", traces, aborted = true)
                }

                if (response.toolCalls.isEmpty()) {
                    val finalReply = response.text.ifBlank { "Done, ${settings.userTitle}." }
                    transition(JarvisState.IDLE, "Standby")
                    return OrchestratorOutcome(reply = finalReply, traces = traces)
                }

                messages.add(LlmMessage.Assistant(response.text, response.toolCalls, response.rawGeminiParts))

                for (call in response.toolCalls) {
                    if (abortFlag.get()) {
                        transition(JarvisState.IDLE, "Aborted")
                        return OrchestratorOutcome("Operation halted, ${settings.userTitle}.", traces, aborted = true)
                    }
                    transition(JarvisState.EXECUTING, "Step $step • ${call.name}")
                    val (result, trace) = runGuardedToolCall(call, settings)
                    traces.add(trace)
                    _liveTraces.value = traces.toList()
                    messages.add(
                        LlmMessage.ToolOutput(
                            toolCallId = call.id,
                            toolName = call.name,
                            output = result.output
                        )
                    )
                }
            }

            transition(JarvisState.IDLE, "Max pilot steps reached")
            return OrchestratorOutcome(
                reply = "I reached the maximum autonomous step limit ($maxSteps) for safety, ${settings.userTitle}. Let me know if you'd like me to continue.",
                traces = traces
            )
        } catch (ce: CancellationException) {
            transition(JarvisState.IDLE, "Cancelled")
            throw ce
        } catch (err: Throwable) {
            transition(JarvisState.ERROR, err.message ?: "Execution error")
            return OrchestratorOutcome(
                reply = "I encountered a systems fault, ${settings.userTitle}: ${err.message ?: "Unknown error"}",
                traces = traces
            )
        }
    }

    private suspend fun runGuardedToolCall(
        call: ToolCall,
        settings: JarvisSettings
    ): Pair<ToolResult, ToolExecutionTrace> {
        val spec = toolExecutor.findSpec(call.name)
        val assessment = SafetyGuard.assess(
            call = call,
            spec = spec,
            safetyMode = settings.safetyMode,
            foregroundPackage = foregroundPackageProvider(),
            lastNodes = lastScreenNodesProvider()
        )

        val argsStr = call.arguments.toString()
        val startMs = System.currentTimeMillis()

        if (assessment.requiresConfirmation) {
            val deferred = CompletableDeferred<Boolean>()
            confirmationDeferred = deferred
            _pendingConfirmation.value = PendingConfirmation(
                id = UUID.randomUUID().toString(),
                toolCall = call,
                riskLevel = assessment.riskLevel,
                title = assessment.title,
                detail = assessment.detail,
                reason = assessment.reason
            )
            transition(JarvisState.AWAITING_AUTH, assessment.title)
            val approved = deferred.await()
            if (!approved) {
                val deniedMsg = "Action '${call.name}' was denied by the user."
                store.logAudit(call.name, argsStr, assessment.riskLevel, approved = false, success = false, summary = deniedMsg)
                val res = ToolResult(call.id, call.name, false, deniedMsg)
                val trace = ToolExecutionTrace(call.name, argsStr.take(120), false, deniedMsg, assessment.riskLevel, System.currentTimeMillis() - startMs)
                return res to trace
            }
            transition(JarvisState.EXECUTING, "Authorized • ${call.name}")
        }

        val result = toolExecutor.execute(call)
        val elapsed = System.currentTimeMillis() - startMs
        store.logAudit(
            toolName = call.name,
            argumentsJson = argsStr,
            riskLevel = assessment.riskLevel,
            approved = true,
            success = result.success,
            summary = result.output.take(300)
        )
        val trace = ToolExecutionTrace(
            toolName = call.name,
            argumentsSummary = argsStr.take(120),
            success = result.success,
            output = result.output.take(300),
            riskLevel = assessment.riskLevel,
            durationMs = elapsed
        )
        return result to trace
    }

    private fun expandProtocolIfMatched(rawInput: String, settings: JarvisSettings): String {
        val normalized = rawInput.trim().lowercase()
            .removePrefix("hey jarvis")
            .removePrefix("jarvis")
            .removePrefix("run ")
            .removePrefix("initiate ")
            .removePrefix("activate ")
            .trim(',', '.', '!', ' ')

        val matched = store.getProtocolsSnapshot().firstOrNull { p ->
            p.triggerPhrase.equals(normalized, ignoreCase = true) ||
                p.name.equals(normalized, ignoreCase = true)
        }
        return if (matched != null && settings.hasActiveCloudKey()) matched.commandPrompt else rawInput
    }

    private fun transition(newState: JarvisState, detail: String) {
        _state.value = newState
        _statusDetail.value = detail
        onStateFeedback(newState)
    }
}
