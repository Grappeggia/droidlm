package ai.droidlm.execution

import ai.droidlm.cloud.MobilerunCloudClient
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.IntentParser
import ai.droidlm.intent.SpeechTextNormalizer
import ai.droidlm.intent.displayName
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.PortalController
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.relay.RelayClient
import ai.droidlm.relay.RelayPlanRequest
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

class DroidLmExecutor(
    private val settingsRepository: SettingsRepository,
    private val relayClient: RelayClient,
    private val portalController: PortalController,
    private val textEditingController: TextEditingController,
    @Suppress("unused") private val ocrEngine: OcrEngine,
    private val logs: ActionLogRepository,
    private val safetyClassifier: SafetyClassifier,
    private val mobilerunCloudClient: MobilerunCloudClient,
    private val parser: IntentParser = IntentParser()
) {
    private val _uiState = MutableStateFlow(ExecutionUiState())
    val uiState: StateFlow<ExecutionUiState> = _uiState.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    @Volatile private var cancelled = false
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null

    suspend fun executeTranscript(transcript: String): ActionResult {
        cancelled = false
        val stripped = SpeechTextNormalizer.stripWakePhrase(transcript)
        _uiState.value = _uiState.value.copy(lastTranscript = stripped, status = "Parsing command")
        logs.log(ActionLogType.TRANSCRIPTION_RESULT, stripped)
        val settings = settingsRepository.settings.first()
        val packages = runCatching { portalController.listPackages() }.getOrDefault(emptyList())
        val action = parser.parse(stripped, packages)
        _uiState.value = _uiState.value.copy(parsedAction = action.displayName())
        logs.log(ActionLogType.PARSED_ACTION, action.displayName())

        val state = runCatching { portalController.getState() }.getOrNull()
        val safety = safetyClassifier.classify(stripped, action, state, settings.sensitiveAppScreenshotDenylist)
        if (safety.requiresConfirmation && settings.requireRiskConfirmation) {
            val confirmed = requestConfirmation(stripped, action, safety.reason ?: "This action is sensitive")
            if (!confirmed) return finish(ActionResult.fail("Command cancelled because confirmation was not accepted", "CONFIRMATION_REJECTED"))
        }

        return when (action) {
            is DroidLmAction.NeedLlmPlanning -> handlePlanning(stripped)
            else -> executeAction(action, stripped)
        }
    }

    fun cancelActive() {
        cancelled = true
        confirmationDeferred?.complete(false)
        _pendingConfirmation.value = null
        _uiState.value = _uiState.value.copy(status = "Cancelled")
        logs.log(ActionLogType.CANCELLED, "Active automation loop cancelled")
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
            val packages = portalController.listPackages().take(120)
            when (val planned = relayClient.planAction(settings.relayBaseUrl, RelayPlanRequest(goal, state, packages, history, maxSteps))) {
                is RelayCallResult.Failure -> return finish(ActionResult.fail(planned.message, planned.errorCode))
                is RelayCallResult.Success -> {
                    val action = planned.value
                    logs.log(ActionLogType.PARSED_ACTION, action.displayName())
                    if (action == DroidLmAction.Done) return finish(ActionResult.ok("Task complete"))
                    val safety = safetyClassifier.classify(goal, action, state, settings.sensitiveAppScreenshotDenylist)
                    if (safety.requiresConfirmation && settings.requireRiskConfirmation) {
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
            is DroidLmAction.LongPress -> portalController.longPress(action.x, action.y, action.durationMs)
            is DroidLmAction.Swipe -> portalController.swipe(action.startX, action.startY, action.endX, action.endY, action.durationMs)
            is DroidLmAction.TypeText -> textEditingController.insertTextAtSelection(action.text)
            DroidLmAction.TakeScreenshot -> {
                val screenshot = portalController.takeScreenshot()
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
            DroidLmAction.Done -> ActionResult.ok("Done")
        }
        logs.log(if (result.success) ActionLogType.ACTION_RESULT else ActionLogType.ERROR, result.message, result.errorCode)
        return if (finishState) finish(result) else result
    }

    private suspend fun runOcrScreen(): ActionResult {
        val screenshot = portalController.takeScreenshot()
        if (!screenshot.success || screenshot.bitmap == null) return ActionResult.fail(screenshot.message, screenshot.errorCode)
        logs.log(ActionLogType.SCREENSHOT_CAPTURED, "Screenshot captured for OCR")
        logs.log(ActionLogType.OCR_STARTED, "Running on-device OCR")
        return runCatching { ocrEngine.recognize(screenshot.bitmap) }
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

    private fun finish(result: ActionResult): ActionResult {
        _uiState.value = _uiState.value.copy(
            status = if (result.success) "Idle" else "Error",
            lastResult = result.message
        )
        return result
    }
}
