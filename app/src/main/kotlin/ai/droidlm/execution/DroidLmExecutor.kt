package ai.droidlm.execution

import ai.droidlm.agent.AgentBudgets
import ai.droidlm.agent.AgentDecisionStatus
import ai.droidlm.agent.AgentRecoveryCandidate
import ai.droidlm.agent.AgentRecoveryPolicy
import ai.droidlm.agent.AgentToolResult
import ai.droidlm.agent.AgentToolRegistry
import ai.droidlm.agent.AgentTurnRequest
import ai.droidlm.agent.AgentVerifier
import ai.droidlm.agent.ToolRisk
import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.cloud.MobilerunCloudClient
import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.context.DeviceContextAggregator
import ai.droidlm.intent.ActionUiFormatter
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.fileops.WorkspaceFileOperationController
import ai.droidlm.intent.IntentParser
import ai.droidlm.intent.SpeechTextNormalizer
import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.displayName
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalState
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.openai.OpenAiClient
import ai.droidlm.prompts.PromptHistoryRepository
import ai.droidlm.relay.PlanPreview
import ai.droidlm.relay.RelayPlanRequest
import ai.droidlm.safety.SafetyDecision
import ai.droidlm.safety.SafetyClassifier
import ai.droidlm.settings.ExecutionMode
import ai.droidlm.settings.SettingsRepository
import ai.droidlm.settings.DroidLmSettings
import ai.droidlm.textedit.TextEditingController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class ExecutionUiState(
    val lastTranscript: String = "",
    val parsedAction: String = "",
    val status: String = "Idle",
    val lastResult: String = ""
)

data class PendingConfirmation(
    val id: String,
    val transcript: String,
    val actionLabel: String,
    val reason: String,
    val prompt: String
)

data class PendingPlan(
    val transcript: String,
    val plan: PlanPreview,
    val diagnosticSessionId: String? = null
)

data class PlannerKeySetupRequest(
    val message: String,
    val retryTranscript: String
)

class DroidLmExecutor(
    private val settingsRepository: SettingsRepository,
    private val openAiClient: OpenAiClient,
    private val portalController: PortalController,
    private val textEditingController: TextEditingController,
    private val workspaceFileOperationController: WorkspaceFileOperationController,
    @Suppress("unused") private val ocrEngine: OcrEngine,
    private val appInventoryRepository: AppInventoryRepository,
    private val deviceContextAggregator: DeviceContextAggregator,
    private val logs: ActionLogRepository,
    private val safetyClassifier: SafetyClassifier,
    private val promptHistoryRepository: PromptHistoryRepository,
    private val diagnostics: SpeechDiagnosticsLogger,
    private val debugLogStore: DebugLogStore? = null,
    private val mobilerunCloudClient: MobilerunCloudClient,
    private val parser: IntentParser = IntentParser()
) {
    private val _uiState = MutableStateFlow(ExecutionUiState())
    val uiState: StateFlow<ExecutionUiState> = _uiState.asStateFlow()

    private val _pendingPlan = MutableStateFlow<PendingPlan?>(null)
    val pendingPlan: StateFlow<PendingPlan?> = _pendingPlan.asStateFlow()

    private val _plannerKeySetupRequest = MutableStateFlow<PlannerKeySetupRequest?>(null)
    val plannerKeySetupRequest: StateFlow<PlannerKeySetupRequest?> = _plannerKeySetupRequest.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    @Volatile private var cancelled = false
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null
    private val agentToolRegistry = AgentToolRegistry()
    private val agentVerifier = AgentVerifier()
    private val agentRecoveryPolicy = AgentRecoveryPolicy()

    suspend fun executeTranscript(transcript: String): ActionResult {
        cancelled = false
        val stripped = SpeechTextNormalizer.stripWakePhrase(transcript)
        debugEvent(null, "manual_execute_started", mapOf("transcriptLength" to stripped.length))
        _uiState.value = _uiState.value.copy(lastTranscript = stripped, status = "Parsing command")
        promptHistoryRepository.record(stripped, "manual_command")
        logs.log(ActionLogType.TRANSCRIPTION_RESULT, stripped)
        val settings = settingsRepository.settings.first()
        val packages = runCatching { appInventoryRepository.getInstalledApps() }.getOrDefault(emptyList())
        val action = parser.parse(stripped, packages)
        debugEvent(null, "manual_parse_result", mapOf("action" to action.displayName(), "packageCount" to packages.size))
        _uiState.value = _uiState.value.copy(parsedAction = ActionUiFormatter.full(action))
        logs.log(ActionLogType.PARSED_ACTION, action.displayName())

        val state = runCatching { portalController.getState() }.getOrNull()
        val safety = safetyClassifier.classify(stripped, action, state, settings.sensitiveAppScreenshotDenylist)
        recordSafetyDecision(null, "manual_command", safety, settings.requireRiskConfirmation)
        if (safety.needsConfirmationPrompt(settings.requireRiskConfirmation)) {
            val confirmed = requestConfirmation(stripped, action, safety.reason ?: "This action is sensitive", null)
            if (!confirmed) return finish(ActionResult.fail("Command cancelled because confirmation was not accepted", "CONFIRMATION_REJECTED"))
        }

        return when (action) {
            is DroidLmAction.NeedLlmPlanning -> handlePlanning(stripped)
            else -> executeAction(action, stripped)
        }
    }

    suspend fun planTranscript(transcript: String, diagnosticSessionId: String? = null): ActionResult {
        cancelled = false
        val stripped = SpeechTextNormalizer.stripWakePhrase(transcript)
        if (isAmbiguousOpenCommand(stripped)) {
            debugEvent(diagnosticSessionId, "ambiguous_open_command", mapOf("transcript" to stripped))
            return finish(ActionResult.fail("I only heard '${stripped}'. Please say which app to open, like 'open Google Sheets'.", "AMBIGUOUS_OPEN_APP"))
        }
        debugEvent(diagnosticSessionId, "voice_plan_started", mapOf("transcriptLength" to stripped.length, "hasSessionId" to (diagnosticSessionId != null)))
        promptHistoryRepository.record(stripped, "voice_prompt")
        _plannerKeySetupRequest.value = null
        _uiState.value = _uiState.value.copy(lastTranscript = stripped, status = "Planning with GPT-5.4 nano", lastResult = "")
        logs.log(ActionLogType.TRANSCRIPTION_RESULT, stripped)
        logs.log(ActionLogType.PLANNER_STARTED, "GPT planning started", "promptLength=${stripped.length}")
        val settings = settingsRepository.settings.first()
        if (settings.executionMode == ExecutionMode.AGENT_LOOP) {
            return runAgentLoop(stripped, diagnosticSessionId)
        }
        val apiKey = settingsRepository.getOpenAiApiKey().orEmpty()
        diagnostics.record(
            diagnosticSessionId,
            "planner_started",
            mapOf(
                "promptLength" to stripped.length,
                "openAiKeyConfigured" to apiKey.isNotBlank(),
                "model" to settings.openAiModel,
                "maxAutonomousSteps" to settings.maxAutonomousSteps,
                "autoAcceptSafePlans" to settings.autoAcceptSafePlans
            )
        )
        if (apiKey.isBlank()) {
            _plannerKeySetupRequest.value = PlannerKeySetupRequest(
                message = "OpenAI key is missing or could not be read. Re-enter the key on this device to use GPT planning.",
                retryTranscript = stripped
            )
            diagnostics.record(diagnosticSessionId, "planner_key_missing")
            logs.log(ActionLogType.ERROR, "Planner OpenAI key is missing or unreadable", "OPENAI_API_KEY_MISSING")
            return finish(ActionResult.fail("OpenAI API key is required for GPT planning", "OPENAI_API_KEY_MISSING"))
        }
        val stateResult = runCatching { portalController.getState() }
        stateResult
            .onSuccess { state -> debugEvent(diagnosticSessionId, "portal_state_collected", portalStateFields(state)) }
            .onFailure { error -> debugEvent(diagnosticSessionId, "portal_state_failed", mapOf("message" to error.message, "errorClass" to error::class.java.name)) }
        val state = stateResult.getOrNull()
        val deviceContextResult = runCatching { deviceContextAggregator.collect(stripped, state) }
        deviceContextResult
            .onSuccess { context ->
                debugEvent(
                    diagnosticSessionId,
                    "device_context_collected",
                    mapOf(
                        "packageCount" to context.packages.size,
                        "activePackage" to context.activeApp?.packageName,
                        "extraKeyCount" to context.extras.length()
                    )
                )
            }
            .onFailure { error -> debugEvent(diagnosticSessionId, "device_context_failed", mapOf("message" to error.message, "errorClass" to error::class.java.name)) }
        val deviceContext = deviceContextResult.getOrNull()
        val packages = deviceContext?.packages ?: runCatching { appInventoryRepository.getInstalledApps() }.getOrDefault(emptyList())
        val activeApp = deviceContext?.activeApp ?: runCatching { appInventoryRepository.activeAppFor(state) }.getOrNull()
        val request = RelayPlanRequest(stripped, state, packages, emptyList(), settings.maxAutonomousSteps, activeApp, deviceContext)
        diagnostics.record(
            diagnosticSessionId,
            "openai_plan_request_started",
            mapOf("model" to settings.openAiModel, "packageCount" to packages.size, "activePackage" to activeApp?.packageName)
        )
        return when (val result = openAiClient.planPreview(apiKey, settings.openAiModel, request)) {
            is RelayCallResult.Failure -> {
                diagnostics.record(diagnosticSessionId, "openai_plan_failed", mapOf("errorCode" to result.errorCode, "message" to result.message.take(240)))
                logs.log(ActionLogType.ERROR, "GPT planning failed: ${result.message}", result.errorCode)
                finish(ActionResult.fail(userFacingPlannerMessage(result), result.errorCode))
            }
            is RelayCallResult.Success -> {
                val plan = result.value
                val pending = PendingPlan(stripped, plan, diagnosticSessionId)
                _pendingPlan.value = pending
                _uiState.value = _uiState.value.copy(
                    parsedAction = "Plan: ${plan.steps.size} steps via ${plan.model}",
                    status = "Plan ready",
                    lastResult = plan.summary
                )
                diagnostics.record(
                    diagnosticSessionId,
                    "openai_plan_succeeded",
                    mapOf("model" to plan.model, "riskLevel" to plan.riskLevel, "stepCount" to plan.steps.size, "requiresConfirmation" to plan.requiresConfirmation)
                )
                logs.log(ActionLogType.PLANNER_RESULT, "GPT plan ready: ${plan.summary}", "model=${plan.model}; risk=${plan.riskLevel}; steps=${plan.steps.size}")
                if (settings.autoAcceptSafePlans && plan.isSafe) {
                    logs.log(ActionLogType.CONFIRMATION_ACCEPTED, "Auto-accepted safe plan")
                    diagnostics.record(diagnosticSessionId, "safe_plan_auto_accepted", mapOf("stepCount" to plan.steps.size))
                    executePlan(pending)
                } else {
                    ActionResult.ok("Plan ready for review")
                }
            }
        }
    }

    suspend fun acceptPendingPlan(alwaysAcceptSafePlans: Boolean): ActionResult {
        val pending = _pendingPlan.value ?: return ActionResult.fail("No pending plan", "NO_PENDING_PLAN")
        debugEvent(pending.diagnosticSessionId, "pending_plan_accepted", mapOf("alwaysAcceptSafePlans" to alwaysAcceptSafePlans, "stepCount" to pending.plan.steps.size))
        if (alwaysAcceptSafePlans && pending.plan.isSafe) {
            settingsRepository.updateAutoAcceptSafePlans(true)
        }
        logs.log(ActionLogType.CONFIRMATION_ACCEPTED, "User accepted GPT plan")
        return executePlan(pending)
    }

    fun rejectPendingPlan() {
        val pending = _pendingPlan.value
        _pendingPlan.value = null
        _uiState.value = _uiState.value.copy(status = "Plan rejected", lastResult = "Plan rejected")
        logs.log(ActionLogType.CONFIRMATION_REJECTED, "User rejected GPT plan")
        debugEvent(pending?.diagnosticSessionId, "pending_plan_rejected", mapOf("stepCount" to (pending?.plan?.steps?.size ?: 0)))
    }

    fun clearPlannerKeySetupRequest() {
        _plannerKeySetupRequest.value = null
    }

    suspend fun retryPlannerKeySetupRequest(): ActionResult {
        val retry = _plannerKeySetupRequest.value?.retryTranscript
            ?: return ActionResult.fail("No planner request to retry", "NO_PLANNER_RETRY")
        _plannerKeySetupRequest.value = null
        return planTranscript(retry)
    }

    fun cancelActive() {
        cancelled = true
        debugEvent(null, "cancel_requested", mapOf("hadPendingConfirmation" to (_pendingConfirmation.value != null), "hadPendingPlan" to (_pendingPlan.value != null)))
        confirmationDeferred?.complete(false)
        _pendingConfirmation.value = null
        _pendingPlan.value = null
        _plannerKeySetupRequest.value = null
        _uiState.value = _uiState.value.copy(status = "Cancelled")
        logs.log(ActionLogType.CANCELLED, "Active automation loop cancelled")
    }

    fun prepareForNewRecording() {
        val current = _uiState.value
        if (current.status == "Idle" && current.lastResult.isBlank()) return
        _uiState.value = current.copy(status = "Idle", lastResult = "", parsedAction = "")
    }

    private suspend fun executePlan(pending: PendingPlan): ActionResult {
        val settings = settingsRepository.settings.first()
        val sessionId = pending.diagnosticSessionId
        _pendingPlan.value = null
        if (pending.plan.steps.isEmpty()) {
            debugEvent(sessionId, "plan_execute_failed", mapOf("reason" to "empty_plan"))
            return finish(ActionResult.fail("Planner returned no steps", "EMPTY_PLAN"))
        }
        debugEvent(sessionId, "plan_execute_started", mapOf("stepCount" to pending.plan.steps.size, "maxSteps" to settings.maxAutonomousSteps, "summaryLength" to pending.plan.summary.length))
        _uiState.value = _uiState.value.copy(status = "Executing plan", parsedAction = "PLAN ${pending.plan.steps.size} steps")
        var last = ActionResult.ok("Started plan")
        for (step in pending.plan.steps.take(settings.maxAutonomousSteps)) {
            ensureNotCancelled()?.let { return finish(it) }
            val state = runCatching { portalController.getState() }.getOrNull()
            val safety = safetyClassifier.classify(pending.transcript, step.action, state, settings.sensitiveAppScreenshotDenylist)
            debugEvent(sessionId, "plan_step_ready", mapOf("index" to step.index, "action" to step.actionLabel, "requiresConfirmation" to step.requiresConfirmation))
            recordSafetyDecision(sessionId, "plan_step_${step.index}", safety, settings.requireRiskConfirmation)
            if ((step.requiresConfirmation && settings.requireRiskConfirmation) || safety.needsConfirmationPrompt(settings.requireRiskConfirmation)) {
                val confirmed = requestConfirmation(
                    pending.transcript,
                    step.action,
                    safety.reason ?: step.reason.ifBlank { "Planner marked this step as sensitive" },
                    sessionId
                )
                if (!confirmed) return finish(ActionResult.fail("Plan cancelled because confirmation was not accepted", "CONFIRMATION_REJECTED"))
            }
            logs.log(ActionLogType.ACTION_STARTED, "Plan step ${step.index}: ${step.actionLabel}", step.reason)
            last = executeAction(step.action, pending.transcript, finishState = false, diagnosticSessionId = sessionId)
            debugEvent(sessionId, "plan_step_result", mapOf("index" to step.index, "action" to step.actionLabel, "success" to last.success, "message" to last.message, "errorCode" to last.errorCode))
            if (!last.success) return finish(last)
        }
        debugEvent(sessionId, "plan_execute_succeeded", mapOf("stepCount" to pending.plan.steps.take(settings.maxAutonomousSteps).size, "lastMessage" to last.message))
        return finish(ActionResult.ok("Plan executed: ${pending.plan.summary}"))
    }

    fun respondToConfirmation(accepted: Boolean) {
        if (accepted) logs.log(ActionLogType.CONFIRMATION_ACCEPTED, "User accepted confirmation")
        else logs.log(ActionLogType.CONFIRMATION_REJECTED, "User rejected confirmation")
        debugEvent(null, "confirmation_user_response", mapOf("accepted" to accepted, "hadPending" to (_pendingConfirmation.value != null)))
        confirmationDeferred?.complete(accepted)
        confirmationDeferred = null
        _pendingConfirmation.value = null
    }

    private suspend fun handlePlanning(goal: String): ActionResult {
        val settings = settingsRepository.settings.first()
        return when (settings.executionMode) {
            ExecutionMode.LOCAL_RULE_FIRST -> finish(
                ActionResult.fail(
                    "This command needs advanced planning. Enable Agent Loop, Local LLM Loop, or Mobilerun Cloud mode.",
                    "PLANNING_DISABLED"
                )
            )
            ExecutionMode.LOCAL_LLM_LOOP -> runLocalLlmLoop(goal, settings.maxAutonomousSteps)
            ExecutionMode.AGENT_LOOP -> runAgentLoop(goal, null)
            ExecutionMode.MOBILERUN_CLOUD_TASK -> runMobilerunTask(goal)
        }
    }

    private suspend fun runLocalLlmLoop(goal: String, maxSteps: Int): ActionResult {
        val settings = settingsRepository.settings.first()
        val history = mutableListOf<String>()
        var lastResult = ActionResult.ok("Started local LLM loop")
        for (step in 1..maxSteps.coerceAtLeast(1)) {
            ensureNotCancelled()?.let { return finish(it) }
            debugEvent(null, "local_loop_step_started", mapOf("step" to step, "maxSteps" to maxSteps, "historySize" to history.size))
            _uiState.value = _uiState.value.copy(status = "Planning step $step/$maxSteps")
            val state = portalController.getState()
            val deviceContext = deviceContextAggregator.collect(goal, state, history)
            val packages = deviceContext.packages
            val activeApp = deviceContext.activeApp
            val apiKey = settingsRepository.getOpenAiApiKey().orEmpty()
            if (apiKey.isBlank()) {
                _plannerKeySetupRequest.value = PlannerKeySetupRequest(
                    message = "GPT planning requires an OpenAI API key saved on this device.",
                    retryTranscript = goal
                )
                return finish(ActionResult.fail("OpenAI API key is required for GPT planning", "OPENAI_API_KEY_MISSING"))
            }
            when (val planned = openAiClient.planAction(apiKey, settings.openAiModel, RelayPlanRequest(goal, state, packages, history, maxSteps, activeApp, deviceContext))) {
                is RelayCallResult.Failure -> return finish(ActionResult.fail(userFacingPlannerMessage(planned), planned.errorCode))
                is RelayCallResult.Success -> {
                    val action = planned.value
                    logs.log(ActionLogType.PARSED_ACTION, action.displayName())
                    if (action == DroidLmAction.Done) return finish(ActionResult.ok("Task complete"))
                    val safety = safetyClassifier.classify(goal, action, state, settings.sensitiveAppScreenshotDenylist)
                    recordSafetyDecision(null, "local_loop_step_$step", safety, settings.requireRiskConfirmation)
                    if (safety.needsConfirmationPrompt(settings.requireRiskConfirmation)) {
                        val confirmed = requestConfirmation(goal, action, safety.reason ?: "This action is sensitive", null)
                        if (!confirmed) return finish(ActionResult.fail("Planner action cancelled", "CONFIRMATION_REJECTED"))
                    }
                    lastResult = executeAction(action, goal, finishState = false)
                    debugEvent(null, "local_loop_step_result", mapOf("step" to step, "action" to action.displayName(), "success" to lastResult.success, "errorCode" to lastResult.errorCode))
                    history += "${action.displayName()} -> ${lastResult.success}: ${lastResult.message}"
                    if (!lastResult.success || action is DroidLmAction.NoOp) return finish(lastResult)
                }
            }
        }
        return finish(ActionResult.fail("Reached max autonomous step limit ($maxSteps)", "MAX_STEPS_REACHED"))
    }

    private suspend fun runAgentLoop(goal: String, diagnosticSessionId: String?): ActionResult {
        val settings = settingsRepository.settings.first()
        val budgets = AgentBudgets(
            maxTurns = settings.maxAgentTurns,
            maxToolCallsTotal = settings.maxAgentToolCalls
        ).normalized()
        val apiKey = settingsRepository.getOpenAiApiKey().orEmpty()
        if (apiKey.isBlank()) {
            _plannerKeySetupRequest.value = PlannerKeySetupRequest(
                message = "Agent mode requires an OpenAI API key saved on this device.",
                retryTranscript = goal
            )
            return finish(ActionResult.fail("OpenAI API key is required for agent mode", "OPENAI_API_KEY_MISSING"))
        }

        val startedAt = System.currentTimeMillis()
        val history = mutableListOf<String>()
        val toolResults = mutableListOf<AgentToolResult>()
        val callsByTool = mutableMapOf<String, Int>()
        val recoveryAttempts = mutableMapOf<String, Int>()
        var totalToolCalls = 0
        var consecutiveFailures = 0
        var lastResult = ActionResult.ok("Started agent loop")
        debugEvent(
            diagnosticSessionId,
            "agent_loop_started",
            mapOf(
                "maxTurns" to budgets.maxTurns,
                "maxToolCallsTotal" to budgets.maxToolCallsTotal,
                "maxToolCallsPerTurn" to budgets.maxToolCallsPerTurn,
                "maxMutatingToolCallsPerTurn" to budgets.maxMutatingToolCallsPerTurn,
                "maxRuntimeMs" to budgets.maxRuntimeMs
            )
        )

        for (turn in 1..budgets.maxTurns) {
            ensureNotCancelled()?.let { return finish(it) }
            if (totalToolCalls >= budgets.maxToolCallsTotal) {
                return finish(ActionResult.fail("Agent reached total tool-call limit ${budgets.maxToolCallsTotal}", "AGENT_TOTAL_TOOL_LIMIT"))
            }
            if (System.currentTimeMillis() - startedAt > budgets.maxRuntimeMs) {
                return finish(ActionResult.fail("Agent stopped after ${budgets.maxRuntimeMs / 1000}s runtime limit", "AGENT_RUNTIME_LIMIT"))
            }
            _uiState.value = _uiState.value.copy(status = "Agent turn $turn/${budgets.maxTurns} (${totalToolCalls}/${budgets.maxToolCallsTotal} tools)")
            val state = runCatching { portalController.getState() }.getOrNull()
            val deviceContext = runCatching { deviceContextAggregator.collect(goal, state, history) }.getOrNull()
            val packages = deviceContext?.packages ?: runCatching { appInventoryRepository.getInstalledApps() }.getOrDefault(emptyList())
            val activeApp = deviceContext?.activeApp ?: runCatching { appInventoryRepository.activeAppFor(state) }.getOrNull()
            debugEvent(
                diagnosticSessionId,
                "agent_turn_started",
                mapOf("turn" to turn, "toolCalls" to totalToolCalls, "historySize" to history.size, "packageCount" to packages.size, "activePackage" to activeApp?.packageName)
            )
            val request = AgentTurnRequest(
                goal = goal,
                turnIndex = turn,
                budgets = budgets,
                remainingToolCalls = budgets.maxToolCallsTotal - totalToolCalls,
                uiState = state,
                packages = packages,
                history = history.takeLast(12),
                activeApp = activeApp,
                deviceContext = deviceContext,
                lastResults = toolResults.takeLast(5)
            )
            val decision = when (val agentResult = openAiClient.nextAgentTurn(apiKey, settings.openAiModel, request)) {
                is RelayCallResult.Failure -> return finish(ActionResult.fail(userFacingPlannerMessage(agentResult), agentResult.errorCode))
                is RelayCallResult.Success -> agentResult.value
            }
            debugEvent(
                diagnosticSessionId,
                "agent_turn_decision",
                mapOf("turn" to turn, "status" to decision.status.name, "messageLength" to decision.message.length, "toolCallCount" to decision.toolCalls.size)
            )
            when (decision.status) {
                AgentDecisionStatus.DONE -> return finish(ActionResult.ok(decision.message.ifBlank { "Task complete" }))
                AgentDecisionStatus.ASK_USER -> return finish(ActionResult.fail(decision.message.ifBlank { "Please clarify the request" }, "AGENT_ASK_USER"))
                AgentDecisionStatus.NO_OP -> return finish(ActionResult.fail(decision.message.ifBlank { "Agent found no safe action" }, "AGENT_NO_OP"))
                AgentDecisionStatus.CALL_TOOLS -> Unit
            }
            if (decision.toolCalls.isEmpty()) {
                return finish(ActionResult.fail("Agent requested no tools", "AGENT_EMPTY_TOOL_CALLS"))
            }
            if (decision.toolCalls.size > budgets.maxToolCallsPerTurn) {
                return finish(ActionResult.fail("Agent requested ${decision.toolCalls.size} tools, over per-turn limit ${budgets.maxToolCallsPerTurn}", "AGENT_TURN_TOOL_LIMIT"))
            }
            if (totalToolCalls + decision.toolCalls.size > budgets.maxToolCallsTotal) {
                return finish(ActionResult.fail("Agent exceeded total tool-call limit ${budgets.maxToolCallsTotal}", "AGENT_TOTAL_TOOL_LIMIT"))
            }

            var mutatingCallsThisTurn = 0
            var shouldObserveAgain = false
            for (call in decision.toolCalls) {
                val executionResult = agentToolRegistry.toExecution(call, state, packages, callsByTool)
                if (executionResult.isFailure) {
                    val error = executionResult.exceptionOrNull()
                    debugEvent(diagnosticSessionId, "agent_tool_validation_failed", mapOf("turn" to turn, "tool" to call.name, "message" to error?.message))
                    history += "${call.name}[${call.id}] validation failed: ${error?.message}"
                    val recovery = agentRecoveryPolicy.recoverValidationFailure(call, error?.message)
                    if (recovery != null && canAttemptRecovery(recovery, recoveryAttempts) && totalToolCalls < budgets.maxToolCallsTotal) {
                        val recoveryResult = executeAgentRecovery(goal, settings, state, recovery, diagnosticSessionId, turn, call.id)
                        totalToolCalls += 1
                        recoveryAttempts[recovery.key] = (recoveryAttempts[recovery.key] ?: 0) + 1
                        toolResults += recoveryResult
                        history += recoveryResult.summary()
                        lastResult = recoveryResult.result
                        debugEvent(diagnosticSessionId, "agent_validation_recovery_result", mapOf("turn" to turn, "tool" to call.name, "recoveryKey" to recovery.key, "success" to recoveryResult.result.success, "errorCode" to recoveryResult.result.errorCode))
                        if (lastResult.success) {
                            consecutiveFailures = 0
                        } else {
                            consecutiveFailures += 1
                            if (consecutiveFailures >= budgets.maxConsecutiveFailures) {
                                return finish(ActionResult.fail("Agent recovery failed after validation failure: ${lastResult.message}", "AGENT_RECOVERY_FAILED"))
                            }
                        }
                    } else {
                        consecutiveFailures += 1
                        if (consecutiveFailures >= budgets.maxConsecutiveFailures) {
                            return finish(ActionResult.fail("Invalid agent tool ${call.name}: ${error?.message}", "AGENT_TOOL_VALIDATION_FAILED"))
                        }
                    }
                    shouldObserveAgain = true
                    break
                }
                val execution = executionResult.getOrThrow()
                if (execution.spec.mutating) {
                    mutatingCallsThisTurn += 1
                    if (mutatingCallsThisTurn > budgets.maxMutatingToolCallsPerTurn) {
                        return finish(ActionResult.fail("Agent exceeded mutating tool-call limit ${budgets.maxMutatingToolCallsPerTurn} for one turn", "AGENT_MUTATING_TOOL_LIMIT"))
                    }
                }

                val safety = safetyClassifier.classify(goal, execution.action, state, settings.sensitiveAppScreenshotDenylist)
                recordSafetyDecision(diagnosticSessionId, "agent_turn_${turn}_${call.id}", safety, settings.requireRiskConfirmation)
                if (safety.blocked) {
                    return finish(ActionResult.fail(safety.reason ?: "Agent action blocked by safety policy", "AGENT_SAFETY_BLOCKED"))
                }
                val needsConfirmation = safety.needsConfirmationPrompt(settings.requireRiskConfirmation) || agentToolNeedsConfirmation(execution.spec.risk, settings.requireRiskConfirmation)
                if (needsConfirmation) {
                    val confirmed = requestConfirmation(
                        goal,
                        execution.action,
                        safety.reason ?: "Agent requested ${execution.spec.risk.name.lowercase()} tool ${execution.spec.name}",
                        diagnosticSessionId
                    )
                    if (!confirmed) return finish(ActionResult.fail("Agent action cancelled because confirmation was not accepted", "CONFIRMATION_REJECTED"))
                }

                val rawResult = executeAction(execution.action, goal, finishState = false, diagnosticSessionId = diagnosticSessionId)
                totalToolCalls += 1
                callsByTool[execution.spec.name] = (callsByTool[execution.spec.name] ?: 0) + 1
                val requiresFreshObservation = agentToolRegistry.isFreshObservationRequired(execution.action, execution.spec)
                val afterState = if (requiresFreshObservation || agentVerifier.needsFreshState(execution.action)) {
                    runCatching { portalController.getState() }.getOrNull()
                } else {
                    null
                }
                val verification = agentVerifier.verify(execution.action, rawResult, state, afterState)
                lastResult = if (rawResult.success && verification.failed) {
                    ActionResult.fail("Agent verification failed: ${verification.message}", "AGENT_VERIFICATION_FAILED")
                } else {
                    rawResult
                }
                val toolResult = AgentToolResult(call.id, execution.spec.name, lastResult, execution.spec.mutating, requiresFreshObservation, verification)
                toolResults += toolResult
                history += toolResult.summary()
                debugEvent(
                    diagnosticSessionId,
                    "agent_tool_result",
                    mapOf(
                        "turn" to turn,
                        "tool" to execution.spec.name,
                        "callId" to call.id,
                        "success" to lastResult.success,
                        "errorCode" to lastResult.errorCode,
                        "freshObservation" to requiresFreshObservation,
                        "verificationStatus" to verification.status.name,
                        "verificationMessage" to verification.message
                    )
                )
                if (!lastResult.success) {
                    val recovery = agentRecoveryPolicy.recoverVerificationFailure(execution.action, verification)
                    if (recovery != null && canAttemptRecovery(recovery, recoveryAttempts) && totalToolCalls < budgets.maxToolCallsTotal) {
                        val recoveryResult = executeAgentRecovery(goal, settings, afterState ?: state, recovery, diagnosticSessionId, turn, call.id)
                        totalToolCalls += 1
                        recoveryAttempts[recovery.key] = (recoveryAttempts[recovery.key] ?: 0) + 1
                        toolResults += recoveryResult
                        history += recoveryResult.summary()
                        lastResult = recoveryResult.result
                        debugEvent(diagnosticSessionId, "agent_verification_recovery_result", mapOf("turn" to turn, "tool" to execution.spec.name, "recoveryKey" to recovery.key, "success" to recoveryResult.result.success, "errorCode" to recoveryResult.result.errorCode))
                    }
                    if (lastResult.success) {
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures += 1
                        if (consecutiveFailures >= budgets.maxConsecutiveFailures) {
                            return finish(ActionResult.fail("Agent stopped after ${budgets.maxConsecutiveFailures} consecutive failures: ${lastResult.message}", "AGENT_REPEATED_FAILURE"))
                        }
                    }
                    shouldObserveAgain = true
                    break
                }
                consecutiveFailures = 0
                if (execution.action == DroidLmAction.Done) return finish(ActionResult.ok(lastResult.message))
                if (requiresFreshObservation) {
                    if (decision.toolCalls.last() != call) {
                        debugEvent(diagnosticSessionId, "agent_turn_truncated_for_observation", mapOf("turn" to turn, "afterTool" to execution.spec.name))
                    }
                    shouldObserveAgain = true
                    break
                }
            }
            if (!shouldObserveAgain && lastResult.success && totalToolCalls >= budgets.maxToolCallsTotal) {
                return finish(ActionResult.fail("Agent reached total tool-call limit ${budgets.maxToolCallsTotal}", "AGENT_TOTAL_TOOL_LIMIT"))
            }
        }
        return finish(ActionResult.fail("Agent reached turn limit (${budgets.maxTurns})", "AGENT_MAX_TURNS"))
    }


    private fun canAttemptRecovery(recovery: AgentRecoveryCandidate, attempts: Map<String, Int>): Boolean =
        (attempts[recovery.key] ?: 0) < 1

    private suspend fun executeAgentRecovery(
        goal: String,
        settings: DroidLmSettings,
        beforeState: PortalState?,
        recovery: AgentRecoveryCandidate,
        diagnosticSessionId: String?,
        turn: Int,
        failedCallId: String
    ): AgentToolResult {
        debugEvent(
            diagnosticSessionId,
            "agent_recovery_started",
            mapOf("turn" to turn, "failedCallId" to failedCallId, "recoveryKey" to recovery.key, "action" to recovery.action.displayName(), "reason" to recovery.reason)
        )
        val safety = safetyClassifier.classify(goal, recovery.action, beforeState, settings.sensitiveAppScreenshotDenylist)
        recordSafetyDecision(diagnosticSessionId, "agent_recovery_${turn}_$failedCallId", safety, settings.requireRiskConfirmation)
        if (safety.blocked) {
            val result = ActionResult.fail(safety.reason ?: "Recovery action blocked by safety policy", "AGENT_RECOVERY_SAFETY_BLOCKED")
            return AgentToolResult("recovery_$failedCallId", "RECOVERY", result, mutating = true, requiresFreshObservationAfter = true)
        }
        val recoveryRisk = when (recovery.action) {
            is DroidLmAction.OpenAppStoreListing -> ToolRisk.INSTALL_OR_STORE
            is DroidLmAction.OpenUrl,
            is DroidLmAction.OpenDeepLink,
            is DroidLmAction.ShareToApp -> ToolRisk.EXTERNAL_SHARE
            else -> ToolRisk.SAFE_NAVIGATION
        }
        val needsConfirmation = safety.needsConfirmationPrompt(settings.requireRiskConfirmation) || agentToolNeedsConfirmation(recoveryRisk, settings.requireRiskConfirmation)
        if (needsConfirmation) {
            val confirmed = requestConfirmation(
                goal,
                recovery.action,
                safety.reason ?: recovery.reason,
                diagnosticSessionId
            )
            if (!confirmed) {
                val result = ActionResult.fail("Recovery action cancelled because confirmation was not accepted", "CONFIRMATION_REJECTED")
                return AgentToolResult("recovery_$failedCallId", "RECOVERY", result, mutating = true, requiresFreshObservationAfter = true)
            }
        }
        val rawResult = executeAction(recovery.action, goal, finishState = false, diagnosticSessionId = diagnosticSessionId)
        val afterState = runCatching { portalController.getState() }.getOrNull()
        val verification = agentVerifier.verify(recovery.action, rawResult, beforeState, afterState)
        val result = if (rawResult.success && verification.failed) {
            ActionResult.fail("Recovery verification failed: ${verification.message}", "AGENT_RECOVERY_VERIFICATION_FAILED")
        } else {
            rawResult
        }
        debugEvent(
            diagnosticSessionId,
            "agent_recovery_result",
            mapOf("turn" to turn, "failedCallId" to failedCallId, "recoveryKey" to recovery.key, "success" to result.success, "errorCode" to result.errorCode, "verificationStatus" to verification.status.name)
        )
        return AgentToolResult("recovery_$failedCallId", "RECOVERY", result, mutating = true, requiresFreshObservationAfter = true, verification = verification)
    }


    private suspend fun runMobilerunTask(goal: String): ActionResult {
        _uiState.value = _uiState.value.copy(status = "Running Mobilerun Cloud task")
        debugEvent(null, "mobilerun_task_started", mapOf("goalLength" to goal.length))
        val result = mobilerunCloudClient.runTaskNonStreaming(goal)
        debugEvent(null, "mobilerun_task_result", mapOf("success" to result.success, "message" to result.message))
        return finish(ActionResult(result.success, result.message, if (result.success) null else "MOBILERUN_FAILED"))
    }

    private suspend fun executeAction(action: DroidLmAction, transcript: String, finishState: Boolean = true, diagnosticSessionId: String? = null): ActionResult {
        ensureNotCancelled()?.let { return finish(it) }
        logs.log(ActionLogType.ACTION_STARTED, action.displayName())
        debugEvent(diagnosticSessionId, "action_started", mapOf("action" to action.displayName(), "finishState" to finishState, "transcriptLength" to transcript.length))
        _uiState.value = _uiState.value.copy(status = "Executing ${ActionUiFormatter.compact(action)}")
        val result = when (action) {
            is DroidLmAction.NoOp -> ActionResult.fail(action.message, "NO_OP")
            is DroidLmAction.NeedLlmPlanning -> handlePlanning(transcript)
            is DroidLmAction.AskConfirmation -> {
                val confirmed = requestConfirmation(transcript, action, action.reason, diagnosticSessionId)
                if (confirmed) ActionResult.ok("Confirmation accepted") else ActionResult.fail("Confirmation rejected", "CONFIRMATION_REJECTED")
            }
            is DroidLmAction.OpenApp -> openAppWithRecovery(action, transcript, diagnosticSessionId)
            is DroidLmAction.OpenAppStoreListing -> portalController.openAppStoreListing(action.packageName, action.appName)
            is DroidLmAction.OpenSettings -> portalController.openSettings()
            DroidLmAction.PressHome -> portalController.pressHome()
            DroidLmAction.PressBack -> portalController.pressBack()
            is DroidLmAction.Tap -> portalController.tap(action.x, action.y)
            is DroidLmAction.TapNode -> portalController.tapNode(action.nodeId)
            is DroidLmAction.FocusNode -> portalController.focusNode(action.nodeId)
            is DroidLmAction.LongPress -> portalController.longPress(action.x, action.y, action.durationMs)
            is DroidLmAction.Swipe -> portalController.swipe(action.startX, action.startY, action.endX, action.endY, action.durationMs)
            is DroidLmAction.Scroll -> portalController.scroll(action.direction, action.targetNodeId, action.untilText)
            is DroidLmAction.TapText -> portalController.tapText(action.text, action.role, action.containerNodeId)
            is DroidLmAction.LongPressNode -> portalController.longPressNode(action.nodeId, action.text, action.durationMs)
            is DroidLmAction.WaitForUi -> portalController.waitForUi(action.text, action.packageName, action.nodeId, action.timeoutMs)
            is DroidLmAction.PressImeAction -> portalController.pressImeAction(action.action)
            is DroidLmAction.DialogAction -> portalController.dialogAction(action.buttonText, action.role)
            is DroidLmAction.OpenMenu -> portalController.openMenu(action.menu)
            is DroidLmAction.SelectTab -> portalController.selectTab(action.label)
            is DroidLmAction.SetToggle -> portalController.setToggle(action.label, action.nodeId, action.value)
            is DroidLmAction.ExpandCollapse -> portalController.expandCollapse(action.label, action.nodeId, action.expanded)
            is DroidLmAction.SetSlider -> portalController.setSlider(action.label, action.nodeId, action.value, action.percent)
            is DroidLmAction.Refresh -> portalController.refresh(action.targetNodeId)
            is DroidLmAction.FindTextOnScreen -> portalController.findTextOnScreen(action.text, action.tapOnMatch)
            DroidLmAction.OpenNotifications -> portalController.openNotifications()
            DroidLmAction.OpenQuickSettings -> portalController.openQuickSettings()
            DroidLmAction.OpenRecents -> portalController.openRecents()
            is DroidLmAction.SwitchApp -> switchApp(action)
            is DroidLmAction.OpenUrl -> portalController.openUrl(action.url)
            is DroidLmAction.OpenDeepLink -> portalController.openDeepLink(action.uri)
            is DroidLmAction.PickFromChooser -> portalController.tapText(action.itemText, role = "item")
            is DroidLmAction.PickFile -> portalController.tapText(action.fileName, role = "item")
            is DroidLmAction.PickPhoto -> portalController.tapText(action.photoLabel, role = "item")
            is DroidLmAction.ShareToApp -> shareToApp(action)
            is DroidLmAction.PermissionDecision -> portalController.dialogAction(role = if (action.allow) DialogButtonRole.POSITIVE else DialogButtonRole.NEGATIVE)
            is DroidLmAction.TypeText -> textEditingController.insertTextAtSelection(action.text)
            DroidLmAction.TakeScreenshot -> {
                val screenshot = portalController.takeScreenshot()
                debugEvent(diagnosticSessionId, "screenshot_capture_result", mapOf("success" to screenshot.success, "hasBitmap" to (screenshot.bitmap != null), "errorCode" to screenshot.errorCode, "message" to screenshot.message))
                if (screenshot.success && screenshot.bitmap != null) {
                    debugLogStore?.retainScreenshot(screenshot.bitmap, "take-screenshot")
                }
                if (screenshot.success) ActionResult.ok("Screenshot captured") else ActionResult.fail(screenshot.message, screenshot.errorCode)
            }
            is DroidLmAction.FocusEditable -> {
                val target = textEditingController.getFocusedEditable()
                if (target != null) ActionResult.ok("Editable target available") else ActionResult.fail("No editable target found", "NO_EDITABLE")
            }
            is DroidLmAction.SetSelection -> {
                val target = textEditingController.getFocusedEditable()
                if (target == null) ActionResult.fail("No editable target found", "NO_EDITABLE")
                else textEditingController.setSelection(target.copy(nodeId = action.nodeId ?: target.nodeId), action.start, action.end)
            }
            is DroidLmAction.InsertText -> textEditingController.insertTextAtSelection(action.text)
            is DroidLmAction.ReplaceSelection -> textEditingController.replaceSelection(action.text)
            is DroidLmAction.SetFullText -> {
                val target = textEditingController.getFocusedEditable()
                if (target == null) ActionResult.fail("No editable target found", "NO_EDITABLE")
                else textEditingController.setFullText(target.copy(nodeId = action.nodeId ?: target.nodeId), action.text)
            }
            is DroidLmAction.MoveCursor -> textEditingController.moveCursorBySemanticTarget(action.targetDescription)
            is DroidLmAction.TapTextAnchor -> textEditingController.insertTextAtAnchor(action.anchorText, action.anchorPosition, "")
            DroidLmAction.OcrScreen -> runOcrScreen(diagnosticSessionId)
            is DroidLmAction.AnalyzeScreenshot -> runOcrScreen(diagnosticSessionId)
            is DroidLmAction.VerifyTextChange -> ActionResult.ok("Verification requested: ${action.expectedText}")
            is DroidLmAction.InsertTextAtAnchor -> textEditingController.insertTextAtAnchor(action.anchorText, action.anchorPosition, action.text)
            is DroidLmAction.ReplaceTextRange -> textEditingController.replaceText(action.targetText, action.replacementText)
            is DroidLmAction.AppendText -> textEditingController.appendText(action.text)
            is DroidLmAction.PrependText -> textEditingController.prependText(action.text)
            DroidLmAction.SelectAll -> selectAllText()
            DroidLmAction.DeleteSelectedText -> textEditingController.replaceSelection("")
            is DroidLmAction.FormatCurrentLineAsBullet -> workspaceFileOperationController.formatCurrentLineAsBullet(transcript, action)
            is DroidLmAction.ReplaceDocumentText -> workspaceFileOperationController.replaceDocumentText(transcript, action)
            is DroidLmAction.AppendDocumentNote -> workspaceFileOperationController.appendDocumentNote(transcript, action)
            is DroidLmAction.SetCurrentSheetCell -> workspaceFileOperationController.setCurrentSheetCell(transcript, action)
            is DroidLmAction.AddSpreadsheetRow -> workspaceFileOperationController.addSpreadsheetRow(transcript, action)
            DroidLmAction.Done -> ActionResult.ok("Done")
        }
        debugEvent(diagnosticSessionId, "action_result", mapOf("action" to action.displayName(), "success" to result.success, "message" to result.message, "errorCode" to result.errorCode))
        logs.log(if (result.success) ActionLogType.ACTION_RESULT else ActionLogType.ERROR, result.message, result.errorCode)
        return if (finishState) finish(result) else result
    }

    private suspend fun openAppWithRecovery(
        action: DroidLmAction.OpenApp,
        transcript: String,
        diagnosticSessionId: String?
    ): ActionResult {
        val launchResult = portalController.openApp(action.packageName)
        val launchErrorCode = launchResult.errorCode
        if (launchResult.success || launchErrorCode == null || launchErrorCode !in MISSING_OR_UNLAUNCHABLE_APP_ERRORS) return launchResult

        debugEvent(
            diagnosticSessionId,
            "open_app_recovery_available",
            mapOf(
                "appName" to action.appName,
                "packageName" to action.packageName,
                "launchErrorCode" to launchResult.errorCode,
                "launchMessage" to launchResult.message
            )
        )
        val appName = action.appName?.takeIf { it.isNotBlank() } ?: action.packageName
        val storeAction = DroidLmAction.OpenAppStoreListing(
            appName = appName,
            packageName = action.packageName,
            reason = "$appName is not installed or launchable; open its app store listing"
        )
        val accepted = requestConfirmation(
            transcript = transcript,
            action = storeAction,
            reason = "$appName is not installed or cannot be launched on this device.",
            diagnosticSessionId = diagnosticSessionId,
            promptOverride = "$appName is not installed or cannot be launched. Open its Play Store listing?"
        )
        if (!accepted) return ActionResult.fail("App store listing was not opened because confirmation was not accepted", "CONFIRMATION_REJECTED")
        return portalController.openAppStoreListing(action.packageName, appName)
    }

    private suspend fun runOcrScreen(diagnosticSessionId: String? = null): ActionResult {
        debugEvent(diagnosticSessionId, "ocr_screen_started")
        val screenshot = portalController.takeScreenshot()
        debugEvent(diagnosticSessionId, "ocr_screenshot_result", mapOf("success" to screenshot.success, "hasBitmap" to (screenshot.bitmap != null), "errorCode" to screenshot.errorCode, "message" to screenshot.message))
        if (!screenshot.success || screenshot.bitmap == null) return ActionResult.fail(screenshot.message, screenshot.errorCode)
        debugLogStore?.retainScreenshot(screenshot.bitmap, "ocr-screen")
        logs.log(ActionLogType.SCREENSHOT_CAPTURED, "Screenshot captured for OCR")
        logs.log(ActionLogType.OCR_STARTED, "Running on-device OCR")
        val deviceContextResult = runCatching { deviceContextAggregator.collect("Analyze screenshot", portalController.getState()) }
        deviceContextResult
            .onSuccess { context -> debugEvent(diagnosticSessionId, "ocr_device_context_collected", mapOf("packageCount" to context.packages.size, "activePackage" to context.activeApp?.packageName)) }
            .onFailure { error -> debugEvent(diagnosticSessionId, "ocr_device_context_failed", mapOf("message" to error.message, "errorClass" to error::class.java.name)) }
        val deviceContext = deviceContextResult.getOrNull()
        return runCatching { ocrEngine.recognize(screenshot.bitmap, deviceContext) }
            .fold(
                onSuccess = {
                    logs.log(ActionLogType.OCR_RESULT, "OCR detected ${it.lines.size} lines")
                    debugEvent(diagnosticSessionId, "ocr_result", mapOf("lineCount" to it.lines.size, "elementCount" to it.elements.size, "source" to it.source.name))
                    ActionResult.ok("OCR detected ${it.lines.size} lines")
                },
                onFailure = {
                    debugEvent(diagnosticSessionId, "ocr_failed", mapOf("message" to it.message, "errorClass" to it::class.java.name))
                    ActionResult.fail("OCR failed: ${it.message}", "OCR_FAILED")
                }
            )
    }

    private suspend fun selectAllText(): ActionResult {
        val target = textEditingController.getFocusedEditable() ?: return ActionResult.fail("No editable field found", "NO_EDITABLE")
        val text = textEditingController.readEditableText(target).text
        return textEditingController.setSelection(target, 0, text.length)
    }

    private suspend fun requestConfirmation(transcript: String, action: DroidLmAction, reason: String, diagnosticSessionId: String? = null, promptOverride: String? = null): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmationDeferred = deferred
        val pending = PendingConfirmation(
            id = UUID.randomUUID().toString(),
            transcript = transcript,
            actionLabel = ActionUiFormatter.full(action),
            reason = reason,
            prompt = promptOverride ?: if (action is DroidLmAction.AskConfirmation) action.confirmationPrompt else "Confirm this DroidLM action?"
        )
        _pendingConfirmation.value = pending
        logs.log(ActionLogType.CONFIRMATION_REQUIRED, reason)
        debugEvent(
            diagnosticSessionId,
            "confirmation_requested",
            mapOf("id" to pending.id, "action" to pending.actionLabel, "reasonLength" to reason.length, "transcriptLength" to transcript.length)
        )
        _uiState.value = _uiState.value.copy(status = "Waiting for confirmation")
        return try {
            withTimeout(30_000) { deferred.await() }.also { accepted ->
                debugEvent(diagnosticSessionId, "confirmation_result", mapOf("id" to pending.id, "accepted" to accepted, "timedOut" to false))
            }
        } catch (_: TimeoutCancellationException) {
            logs.log(ActionLogType.CONFIRMATION_REJECTED, "Confirmation timed out")
            debugEvent(diagnosticSessionId, "confirmation_result", mapOf("id" to pending.id, "accepted" to false, "timedOut" to true))
            false
        } finally {
            confirmationDeferred = null
            _pendingConfirmation.value = null
        }
    }

    private fun isAmbiguousOpenCommand(transcript: String): Boolean {
        val normalized = SpeechTextNormalizer.normalizeForRecognition(transcript)
        return normalized in setOf("open", "launch", "start")
    }

    private fun agentToolNeedsConfirmation(risk: ToolRisk, requireRiskConfirmation: Boolean): Boolean {
        if (!requireRiskConfirmation) return risk in setOf(ToolRisk.EXTERNAL_SHARE, ToolRisk.INSTALL_OR_STORE, ToolRisk.PERMISSION_OR_CREDENTIAL)
        return risk in setOf(ToolRisk.SENSITIVE, ToolRisk.EXTERNAL_SHARE, ToolRisk.INSTALL_OR_STORE, ToolRisk.PERMISSION_OR_CREDENTIAL)
    }


    private fun ensureNotCancelled(): ActionResult? =
        if (cancelled) ActionResult.fail("Task was cancelled", "CANCELLED") else null

    private companion object {
        val MISSING_OR_UNLAUNCHABLE_APP_ERRORS = setOf("APP_NOT_INSTALLED", "APP_DISABLED", "APP_NOT_LAUNCHABLE", "APP_NOT_FOUND")
    }


    private fun SafetyDecision.needsConfirmationPrompt(requireRiskConfirmation: Boolean): Boolean =
        requiresConfirmation && (requireRiskConfirmation || mandatoryConfirmation)

    private fun debugEvent(sessionId: String?, event: String, fields: Map<String, Any?> = emptyMap()) {
        diagnostics.record(sessionId, "executor_$event", fields)
    }

    private fun recordSafetyDecision(sessionId: String?, source: String, safety: SafetyDecision, requireRiskConfirmation: Boolean) {
        debugEvent(
            sessionId,
            "safety_decision",
            mapOf(
                "source" to source,
                "requiresConfirmation" to safety.requiresConfirmation,
                "mandatoryConfirmation" to safety.mandatoryConfirmation,
                "confirmationPromptNeeded" to safety.needsConfirmationPrompt(requireRiskConfirmation),
                "category" to safety.category,
                "blocked" to safety.blocked,
                "reasonLength" to (safety.reason?.length ?: 0)
            )
        )
    }

    private fun portalStateFields(state: ai.droidlm.portal.PortalState): Map<String, Any?> = mapOf(
        "packageName" to state.packageName,
        "activityName" to state.activityName,
        "screenWidth" to state.screenWidth,
        "screenHeight" to state.screenHeight,
        "nodeCount" to state.nodes.size,
        "editableNodeCount" to state.nodes.count { it.editable },
        "focusedNodeCount" to state.nodes.count { it.focused }
    )

    private fun finish(result: ActionResult): ActionResult {
        _uiState.value = _uiState.value.copy(
            status = if (result.success) "Idle" else "Error",
            lastResult = result.message
        )
        return result
    }

    private fun userFacingPlannerMessage(result: RelayCallResult.Failure): String =
        if (result.errorCode == "INVALID_JSON") {
            "I couldn't turn that into a valid Android action. Please try again."
        } else {
            result.message
        }

    private suspend fun switchApp(action: DroidLmAction.SwitchApp): ActionResult {
        action.packageName?.takeIf { it.isNotBlank() }?.let { return portalController.openApp(it) }
        action.appName?.takeIf { it.isNotBlank() }?.let {
            val visiblePick = portalController.tapText(it, role = "item")
            if (visiblePick.success) return visiblePick
        }
        return portalController.openRecents()
    }

    private suspend fun shareToApp(action: DroidLmAction.ShareToApp): ActionResult {
        action.appName?.takeIf { it.isNotBlank() }?.let {
            val visiblePick = portalController.tapText(it, role = "item")
            if (visiblePick.success) return visiblePick
        }
        action.packageName?.takeIf { it.isNotBlank() }?.let { return portalController.openApp(it) }
        return ActionResult.fail("Share target is not visible on screen", "SHARE_TARGET_NOT_FOUND")
    }
}
