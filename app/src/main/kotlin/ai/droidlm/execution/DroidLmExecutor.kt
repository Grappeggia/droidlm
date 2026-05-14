package ai.droidlm.execution


import ai.droidlm.agent.AgentRecoveryPolicy

import ai.droidlm.agent.AgentToolRegistry
import ai.droidlm.agent.AgentVerifier

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

import ai.droidlm.intent.displayName
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.ocr.CloudScreenshotAnalyzer
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalController


import ai.droidlm.relay.RelayCallResult
import ai.droidlm.openai.OpenAiClient
import ai.droidlm.prompts.PromptHistoryRepository
import ai.droidlm.relay.PlanPreview

import ai.droidlm.safety.SafetyDecision
import ai.droidlm.safety.SafetyClassifier
import ai.droidlm.settings.ExecutionMode
import ai.droidlm.settings.SettingsRepository

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
    private val cloudScreenshotAnalyzer: CloudScreenshotAnalyzer? = null,
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
    private val executionDiagnostics = ExecutionDiagnostics(diagnostics, portalController)
    private val actionRunner = ExecutionActionRunner(
        settingsRepository = settingsRepository,
        portalController = portalController,
        textEditingController = textEditingController,
        workspaceFileOperationController = workspaceFileOperationController,
        ocrEngine = ocrEngine,
        deviceContextAggregator = deviceContextAggregator,
        logs = logs,
        diagnostics = diagnostics,
        debugLogStore = debugLogStore,
        cloudScreenshotAnalyzer = cloudScreenshotAnalyzer,
        uiState = _uiState,
        executionDiagnostics = executionDiagnostics,
        cancellationResult = ::ensureNotCancelled,
        finish = ::finish,
        requestConfirmation = { transcript, action, reason, sessionId, promptOverride ->
            requestConfirmation(transcript, action, reason, sessionId, promptOverride)
        },
        handlePlanning = ::handlePlanning
    )
    private val agentLoopRunner = ExecutionAgentLoopRunner(
        settingsRepository = settingsRepository,
        openAiClient = openAiClient,
        portalController = portalController,
        appInventoryRepository = appInventoryRepository,
        deviceContextAggregator = deviceContextAggregator,
        safetyClassifier = safetyClassifier,
        agentToolRegistry = agentToolRegistry,
        agentVerifier = agentVerifier,
        agentRecoveryPolicy = agentRecoveryPolicy,
        uiState = _uiState,
        plannerKeySetupRequest = _plannerKeySetupRequest,
        executionDiagnostics = executionDiagnostics,
        cancellationResult = ::ensureNotCancelled,
        finish = ::finish,
        executeAction = ::executeAction,
        requestConfirmation = { transcript, action, reason, sessionId, promptOverride ->
            requestConfirmation(transcript, action, reason, sessionId, promptOverride)
        },
        userFacingPlannerMessage = ::userFacingPlannerMessage
    )
    private val planningCoordinator = ExecutionPlanningCoordinator(
        settingsRepository = settingsRepository,
        openAiClient = openAiClient,
        portalController = portalController,
        appInventoryRepository = appInventoryRepository,
        deviceContextAggregator = deviceContextAggregator,
        logs = logs,
        diagnostics = diagnostics,
        safetyClassifier = safetyClassifier,
        promptHistoryRepository = promptHistoryRepository,
        uiState = _uiState,
        pendingPlan = _pendingPlan,
        plannerKeySetupRequest = _plannerKeySetupRequest,
        executionDiagnostics = executionDiagnostics,
        cancellationResult = ::ensureNotCancelled,
        finish = ::finish,
        executeAction = ::executeAction,
        requestConfirmation = { transcript, action, reason, sessionId, promptOverride ->
            requestConfirmation(transcript, action, reason, sessionId, promptOverride)
        },
        runAgentLoop = ::runAgentLoop,
        runMobilerunTask = ::runMobilerunTask,
        userFacingPlannerMessage = ::userFacingPlannerMessage
    )


    suspend fun executeTranscript(transcript: String, diagnosticSessionId: String? = null): ActionResult {
        cancelled = false
        val stripped = SpeechTextNormalizer.stripWakePhrase(transcript)
        debugEvent(diagnosticSessionId, "manual_execute_started", mapOf("transcriptLength" to stripped.length, "hasSessionId" to (diagnosticSessionId != null)))
        _uiState.value = _uiState.value.copy(lastTranscript = stripped, status = "Parsing command")
        promptHistoryRepository.record(stripped, "manual_command")
        logs.log(ActionLogType.TRANSCRIPTION_RESULT, stripped)
        val settings = settingsRepository.settings.first()
        val packages = runCatching { appInventoryRepository.getInstalledApps() }.getOrDefault(emptyList())
        val action = parser.parse(stripped, packages)
        debugEvent(diagnosticSessionId, "manual_parse_result", mapOf("action" to action.displayName(), "packageCount" to packages.size))
        debugEvent(diagnosticSessionId, "transcript_quality", transcriptQualityFields(stripped))
        openAppResolutionFields(stripped, packages, action)?.let { fields ->
            debugEvent(diagnosticSessionId, "open_app_resolution", fields)
        }
        debugEvent(diagnosticSessionId, "voice_route_decision", voiceRouteDecisionFields(action, settings.executionMode))
        plannerBypassReason(action)?.let { reason ->
            debugEvent(diagnosticSessionId, "planner_bypass_reason", mapOf("reason" to reason, "action" to action.displayName()))
        }
        _uiState.value = _uiState.value.copy(parsedAction = ActionUiFormatter.full(action))
        logs.log(ActionLogType.PARSED_ACTION, action.displayName())

        val state = runCatching { portalController.getState() }.getOrNull()
        val safety = safetyClassifier.classify(stripped, action, state, settings.sensitiveAppScreenshotDenylist)
        recordSafetyDecision(diagnosticSessionId, "manual_command", safety, settings.requireRiskConfirmation)
        if (safety.needsConfirmationPrompt(settings.requireRiskConfirmation)) {
            val confirmed = requestConfirmation(stripped, action, safety.reason ?: "This action is sensitive", diagnosticSessionId)
            if (!confirmed) return finish(ActionResult.fail("Command cancelled because confirmation was not accepted", "CONFIRMATION_REJECTED"))
        }

        return when (action) {
            is DroidLmAction.NeedLlmPlanning -> handlePlanning(stripped, diagnosticSessionId)
            else -> executeAction(action, stripped, diagnosticSessionId = diagnosticSessionId)
        }
    }

    suspend fun planTranscript(transcript: String, diagnosticSessionId: String? = null, recordPrompt: Boolean = true): ActionResult {
        cancelled = false
        return planningCoordinator.planTranscript(transcript, diagnosticSessionId, recordPrompt)
    }

    suspend fun acceptPendingPlan(alwaysAcceptSafePlans: Boolean): ActionResult =
        planningCoordinator.acceptPendingPlan(alwaysAcceptSafePlans)

    fun rejectPendingPlan() {
        planningCoordinator.rejectPendingPlan()
    }

    fun clearPlannerKeySetupRequest() {
        planningCoordinator.clearPlannerKeySetupRequest()
    }

    suspend fun retryPlannerKeySetupRequest(): ActionResult =
        planningCoordinator.retryPlannerKeySetupRequest()

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



    fun respondToConfirmation(accepted: Boolean) {
        if (accepted) logs.log(ActionLogType.CONFIRMATION_ACCEPTED, "User accepted confirmation")
        else logs.log(ActionLogType.CONFIRMATION_REJECTED, "User rejected confirmation")
        debugEvent(null, "confirmation_user_response", mapOf("accepted" to accepted, "hadPending" to (_pendingConfirmation.value != null)))
        confirmationDeferred?.complete(accepted)
        confirmationDeferred = null
        _pendingConfirmation.value = null
    }

    private suspend fun handlePlanning(goal: String, diagnosticSessionId: String? = null): ActionResult =
        planningCoordinator.handlePlanning(goal, diagnosticSessionId)

    private suspend fun runAgentLoop(goal: String, diagnosticSessionId: String?): ActionResult =
        agentLoopRunner.run(goal, diagnosticSessionId)


    private suspend fun runMobilerunTask(goal: String): ActionResult {
        _uiState.value = _uiState.value.copy(status = "Running Mobilerun Cloud task")
        debugEvent(null, "mobilerun_task_started", mapOf("goalLength" to goal.length))
        val result = mobilerunCloudClient.runTaskNonStreaming(goal)
        debugEvent(null, "mobilerun_task_result", mapOf("success" to result.success, "message" to result.message))
        return finish(ActionResult(result.success, result.message, if (result.success) null else "MOBILERUN_FAILED"))
    }

    private suspend fun executeAction(action: DroidLmAction, transcript: String, finishState: Boolean = true, diagnosticSessionId: String? = null): ActionResult =
        actionRunner.execute(action, transcript, finishState, diagnosticSessionId)
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




    private fun ensureNotCancelled(): ActionResult? =
        if (cancelled) ActionResult.fail("Task was cancelled", "CANCELLED") else null

    private fun transcriptQualityFields(transcript: String): Map<String, Any?> =
        executionDiagnostics.transcriptQualityFields(transcript)

    private fun voiceRouteDecisionFields(action: DroidLmAction, executionMode: ExecutionMode): Map<String, Any?> =
        executionDiagnostics.voiceRouteDecisionFields(action, executionMode)

    private fun plannerBypassReason(action: DroidLmAction): String? =
        executionDiagnostics.plannerBypassReason(action)

    private fun openAppResolutionFields(transcript: String, packages: List<AppPackage>, action: DroidLmAction): Map<String, Any?>? =
        executionDiagnostics.openAppResolutionFields(transcript, packages, action)


    private fun SafetyDecision.needsConfirmationPrompt(requireRiskConfirmation: Boolean): Boolean =
        requiresConfirmation && (requireRiskConfirmation || mandatoryConfirmation)

    private fun debugEvent(sessionId: String?, event: String, fields: Map<String, Any?> = emptyMap()) {
        executionDiagnostics.debugEvent(sessionId, event, fields)
    }

    private fun recordSafetyDecision(sessionId: String?, source: String, safety: SafetyDecision, requireRiskConfirmation: Boolean) {
        executionDiagnostics.recordSafetyDecision(sessionId, source, safety, requireRiskConfirmation)
    }





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


}
