package ai.droidlm.execution

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.cloud.MobilerunCloudClient
import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.context.DeviceContextAggregator
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.fileops.WorkspaceFileOperationController
import ai.droidlm.intent.IntentParser
import ai.droidlm.intent.SpeechTextNormalizer
import ai.droidlm.intent.displayName
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.PortalController
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.openai.OpenAiClient
import ai.droidlm.prompts.PromptHistoryRepository
import ai.droidlm.relay.PlanPreview
import ai.droidlm.relay.RelayPlanRequest
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
    val plan: PlanPreview
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

    suspend fun executeTranscript(transcript: String): ActionResult {
        cancelled = false
        val stripped = SpeechTextNormalizer.stripWakePhrase(transcript)
        _uiState.value = _uiState.value.copy(lastTranscript = stripped, status = "Parsing command")
        promptHistoryRepository.record(stripped, "manual_command")
        logs.log(ActionLogType.TRANSCRIPTION_RESULT, stripped)
        val settings = settingsRepository.settings.first()
        val packages = runCatching { appInventoryRepository.getInstalledApps() }.getOrDefault(emptyList())
        val action = parser.parse(stripped, packages)
        _uiState.value = _uiState.value.copy(parsedAction = action.displayName())
        logs.log(ActionLogType.PARSED_ACTION, action.displayName())

        val state = runCatching { portalController.getState() }.getOrNull()
        val safety = safetyClassifier.classify(stripped, action, state, settings.sensitiveAppScreenshotDenylist)
        if (safety.needsConfirmationPrompt(settings.requireRiskConfirmation)) {
            val confirmed = requestConfirmation(stripped, action, safety.reason ?: "This action is sensitive")
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
        promptHistoryRepository.record(stripped, "voice_prompt")
        _plannerKeySetupRequest.value = null
        _uiState.value = _uiState.value.copy(lastTranscript = stripped, status = "Planning with GPT-5.4 nano", lastResult = "")
        logs.log(ActionLogType.TRANSCRIPTION_RESULT, stripped)
        logs.log(ActionLogType.PLANNER_STARTED, "GPT planning started", "promptLength=${stripped.length}")
        val settings = settingsRepository.settings.first()
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
        val state = runCatching { portalController.getState() }.getOrNull()
        val deviceContext = runCatching { deviceContextAggregator.collect(stripped, state) }.getOrNull()
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
                finish(ActionResult.fail(result.message, result.errorCode))
            }
            is RelayCallResult.Success -> {
                val plan = result.value
                val pending = PendingPlan(stripped, plan)
                _pendingPlan.value = pending
                _uiState.value = _uiState.value.copy(
                    parsedAction = "PLAN ${plan.steps.size} steps via ${plan.model}",
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
        if (alwaysAcceptSafePlans && pending.plan.isSafe) {
            settingsRepository.updateAutoAcceptSafePlans(true)
        }
        logs.log(ActionLogType.CONFIRMATION_ACCEPTED, "User accepted GPT plan")
        return executePlan(pending)
    }

    fun rejectPendingPlan() {
        _pendingPlan.value = null
        _uiState.value = _uiState.value.copy(status = "Plan rejected", lastResult = "Plan rejected")
        logs.log(ActionLogType.CONFIRMATION_REJECTED, "User rejected GPT plan")
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
        confirmationDeferred?.complete(false)
        _pendingConfirmation.value = null
        _pendingPlan.value = null
        _plannerKeySetupRequest.value = null
        _uiState.value = _uiState.value.copy(status = "Cancelled")
        logs.log(ActionLogType.CANCELLED, "Active automation loop cancelled")
    }

    private suspend fun executePlan(pending: PendingPlan): ActionResult {
        val settings = settingsRepository.settings.first()
        _pendingPlan.value = null
        if (pending.plan.steps.isEmpty()) {
            return finish(ActionResult.fail("Planner returned no steps", "EMPTY_PLAN"))
        }
        _uiState.value = _uiState.value.copy(status = "Executing plan", parsedAction = "PLAN ${pending.plan.steps.size} steps")
        var last = ActionResult.ok("Started plan")
        for (step in pending.plan.steps.take(settings.maxAutonomousSteps)) {
            ensureNotCancelled()?.let { return finish(it) }
            val state = runCatching { portalController.getState() }.getOrNull()
            val safety = safetyClassifier.classify(pending.transcript, step.action, state, settings.sensitiveAppScreenshotDenylist)
            if ((step.requiresConfirmation && settings.requireRiskConfirmation) || safety.needsConfirmationPrompt(settings.requireRiskConfirmation)) {
                val confirmed = requestConfirmation(
                    pending.transcript,
                    step.action,
                    safety.reason ?: step.reason.ifBlank { "Planner marked this step as sensitive" }
                )
                if (!confirmed) return finish(ActionResult.fail("Plan cancelled because confirmation was not accepted", "CONFIRMATION_REJECTED"))
            }
            logs.log(ActionLogType.ACTION_STARTED, "Plan step ${step.index}: ${step.actionLabel}", step.reason)
            last = executeAction(step.action, pending.transcript, finishState = false)
            if (!last.success) return finish(last)
        }
        return finish(ActionResult.ok("Plan executed: ${pending.plan.summary}"))
    }

    fun respondToConfirmation(accepted: Boolean) {
        if (accepted) logs.log(ActionLogType.CONFIRMATION_ACCEPTED, "User accepted confirmation")
        else logs.log(ActionLogType.CONFIRMATION_REJECTED, "User rejected confirmation")
        confirmationDeferred?.complete(accepted)
        confirmationDeferred = null
        _pendingConfirmation.value = null
    }

    private suspend fun handlePlanning(goal: String): ActionResult {
        val settings = settingsRepository.settings.first()
        return when (settings.executionMode) {
            ExecutionMode.LOCAL_RULE_FIRST -> finish(
                ActionResult.fail(
                    "This command needs advanced planning. Enable Local LLM Loop or Mobilerun Cloud mode.",
                    "PLANNING_DISABLED"
                )
            )
            ExecutionMode.LOCAL_LLM_LOOP -> runLocalLlmLoop(goal, settings.maxAutonomousSteps)
            ExecutionMode.MOBILERUN_CLOUD_TASK -> runMobilerunTask(goal)
        }
    }

    private suspend fun runLocalLlmLoop(goal: String, maxSteps: Int): ActionResult {
        val settings = settingsRepository.settings.first()
        val history = mutableListOf<String>()
        var lastResult = ActionResult.ok("Started local LLM loop")
        for (step in 1..maxSteps.coerceAtLeast(1)) {
            ensureNotCancelled()?.let { return finish(it) }
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
                is RelayCallResult.Failure -> return finish(ActionResult.fail(planned.message, planned.errorCode))
                is RelayCallResult.Success -> {
                    val action = planned.value
                    logs.log(ActionLogType.PARSED_ACTION, action.displayName())
                    if (action == DroidLmAction.Done) return finish(ActionResult.ok("Task complete"))
                    val safety = safetyClassifier.classify(goal, action, state, settings.sensitiveAppScreenshotDenylist)
                    if (safety.needsConfirmationPrompt(settings.requireRiskConfirmation)) {
                        val confirmed = requestConfirmation(goal, action, safety.reason ?: "This action is sensitive")
                        if (!confirmed) return finish(ActionResult.fail("Planner action cancelled", "CONFIRMATION_REJECTED"))
                    }
                    lastResult = executeAction(action, goal, finishState = false)
                    history += "${action.displayName()} -> ${lastResult.success}: ${lastResult.message}"
                    if (!lastResult.success || action is DroidLmAction.NoOp) return finish(lastResult)
                }
            }
        }
        return finish(ActionResult.fail("Reached max autonomous step limit ($maxSteps)", "MAX_STEPS_REACHED"))
    }

    private suspend fun runMobilerunTask(goal: String): ActionResult {
        _uiState.value = _uiState.value.copy(status = "Running Mobilerun Cloud task")
        val result = mobilerunCloudClient.runTaskNonStreaming(goal)
        return finish(ActionResult(result.success, result.message, if (result.success) null else "MOBILERUN_FAILED"))
    }

    private suspend fun executeAction(action: DroidLmAction, transcript: String, finishState: Boolean = true): ActionResult {
        ensureNotCancelled()?.let { return finish(it) }
        logs.log(ActionLogType.ACTION_STARTED, action.displayName())
        _uiState.value = _uiState.value.copy(status = "Executing ${action.displayName()}")
        val result = when (action) {
            is DroidLmAction.NoOp -> ActionResult.fail(action.message, "NO_OP")
            is DroidLmAction.NeedLlmPlanning -> handlePlanning(transcript)
            is DroidLmAction.AskConfirmation -> {
                val confirmed = requestConfirmation(transcript, action, action.reason)
                if (confirmed) ActionResult.ok("Confirmation accepted") else ActionResult.fail("Confirmation rejected", "CONFIRMATION_REJECTED")
            }
            is DroidLmAction.OpenApp -> portalController.openApp(action.packageName)
            is DroidLmAction.OpenSettings -> portalController.openSettings()
            DroidLmAction.PressHome -> portalController.pressHome()
            DroidLmAction.PressBack -> portalController.pressBack()
            is DroidLmAction.Tap -> portalController.tap(action.x, action.y)
            is DroidLmAction.TapNode -> portalController.tapNode(action.nodeId)
            is DroidLmAction.FocusNode -> portalController.focusNode(action.nodeId)
            is DroidLmAction.LongPress -> portalController.longPress(action.x, action.y, action.durationMs)
            is DroidLmAction.Swipe -> portalController.swipe(action.startX, action.startY, action.endX, action.endY, action.durationMs)
            is DroidLmAction.TypeText -> textEditingController.insertTextAtSelection(action.text)
            DroidLmAction.TakeScreenshot -> {
                val screenshot = portalController.takeScreenshot()
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
            DroidLmAction.OcrScreen -> runOcrScreen()
            is DroidLmAction.AnalyzeScreenshot -> runOcrScreen()
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
        logs.log(if (result.success) ActionLogType.ACTION_RESULT else ActionLogType.ERROR, result.message, result.errorCode)
        return if (finishState) finish(result) else result
    }

    private suspend fun runOcrScreen(): ActionResult {
        val screenshot = portalController.takeScreenshot()
        if (!screenshot.success || screenshot.bitmap == null) return ActionResult.fail(screenshot.message, screenshot.errorCode)
        debugLogStore?.retainScreenshot(screenshot.bitmap, "ocr-screen")
        logs.log(ActionLogType.SCREENSHOT_CAPTURED, "Screenshot captured for OCR")
        logs.log(ActionLogType.OCR_STARTED, "Running on-device OCR")
        val deviceContext = runCatching { deviceContextAggregator.collect("Analyze screenshot", portalController.getState()) }.getOrNull()
        return runCatching { ocrEngine.recognize(screenshot.bitmap, deviceContext) }
            .fold(
                onSuccess = {
                    logs.log(ActionLogType.OCR_RESULT, "OCR detected ${it.lines.size} lines")
                    ActionResult.ok("OCR detected ${it.lines.size} lines")
                },
                onFailure = { ActionResult.fail("OCR failed: ${it.message}", "OCR_FAILED") }
            )
    }

    private suspend fun selectAllText(): ActionResult {
        val target = textEditingController.getFocusedEditable() ?: return ActionResult.fail("No editable field found", "NO_EDITABLE")
        val text = textEditingController.readEditableText(target).text
        return textEditingController.setSelection(target, 0, text.length)
    }

    private suspend fun requestConfirmation(transcript: String, action: DroidLmAction, reason: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmationDeferred = deferred
        val pending = PendingConfirmation(
            id = UUID.randomUUID().toString(),
            transcript = transcript,
            actionLabel = action.displayName(),
            reason = reason,
            prompt = if (action is DroidLmAction.AskConfirmation) action.confirmationPrompt else "Confirm this DroidLM action?"
        )
        _pendingConfirmation.value = pending
        logs.log(ActionLogType.CONFIRMATION_REQUIRED, reason)
        _uiState.value = _uiState.value.copy(status = "Waiting for confirmation")
        return try {
            withTimeout(30_000) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            logs.log(ActionLogType.CONFIRMATION_REJECTED, "Confirmation timed out")
            false
        } finally {
            confirmationDeferred = null
            _pendingConfirmation.value = null
        }
    }

    private fun ensureNotCancelled(): ActionResult? =
        if (cancelled) ActionResult.fail("Task was cancelled", "CANCELLED") else null

    private fun SafetyDecision.needsConfirmationPrompt(requireRiskConfirmation: Boolean): Boolean =
        requiresConfirmation && (requireRiskConfirmation || mandatoryConfirmation)

    private fun finish(result: ActionResult): ActionResult {
        _uiState.value = _uiState.value.copy(
            status = if (result.success) "Idle" else "Error",
            lastResult = result.message
        )
        return result
    }
}
