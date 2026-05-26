package ai.droidlm.execution

import ai.droidlm.context.AccessibilityContentExtractor
import ai.droidlm.context.AccessibilityContentSearchQuery
import ai.droidlm.context.DeviceContextAggregator
import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.fileops.WorkspaceFileOperationController
import ai.droidlm.intent.ActionUiFormatter
import ai.droidlm.intent.DialogButtonRole
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.ScrollDirection
import ai.droidlm.intent.displayName
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.ocr.CloudScreenshotAnalyzer
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalState
import ai.droidlm.settings.SettingsRepository
import ai.droidlm.textedit.TextEditingController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal class ExecutionActionRunner(
    private val settingsRepository: SettingsRepository,
    private val portalController: PortalController,
    private val textEditingController: TextEditingController,
    private val workspaceFileOperationController: WorkspaceFileOperationController,
    private val ocrEngine: OcrEngine,
    private val deviceContextAggregator: DeviceContextAggregator,
    private val logs: ActionLogRepository,
    private val diagnostics: SpeechDiagnosticsLogger,
    private val debugLogStore: DebugLogStore?,
    private val cloudScreenshotAnalyzer: CloudScreenshotAnalyzer?,
    private val uiState: MutableStateFlow<ExecutionUiState>,
    private val executionDiagnostics: ExecutionDiagnostics,
    private val cancellationResult: () -> ActionResult?,
    private val finish: (ActionResult) -> ActionResult,
    private val requestConfirmation: suspend (String, DroidLmAction, String, String?, String?) -> Boolean,
    private val handlePlanning: suspend (String, String?) -> ActionResult
) {
    private val artifactToolExecutor = ArtifactToolExecutor(
        portalController = portalController,
        textEditingController = textEditingController,
        deviceContextAggregator = deviceContextAggregator
    )

    suspend fun execute(
        action: DroidLmAction,
        transcript: String,
        finishState: Boolean = true,
        diagnosticSessionId: String? = null
    ): ActionResult {
        cancellationResult()?.let { return finish(it) }
        logs.log(ActionLogType.ACTION_STARTED, action.displayName())
        val traceEnabled = diagnostics.isEnabledNow()
        val actionStartedAt = System.currentTimeMillis()
        val beforeState = if (traceEnabled) {
            executionDiagnostics.collectPortalStateForActionTrace(diagnosticSessionId, "before", action)
        } else {
            null
        }
        debugEvent(
            diagnosticSessionId,
            "action_started",
            mapOf("action" to action.displayName(), "finishState" to finishState, "transcriptLength" to transcript.length) +
                executionDiagnostics.actionTraceFields(action, beforeState, afterState = null, durationMs = null, result = null)
        )
        uiState.value = uiState.value.copy(status = "Executing ${ActionUiFormatter.compact(action)}")
        val result = dispatch(action, transcript, diagnosticSessionId)
        val afterState = if (traceEnabled) {
            executionDiagnostics.collectPortalStateForActionTrace(diagnosticSessionId, "after", action)
        } else {
            null
        }
        val durationMs = System.currentTimeMillis() - actionStartedAt
        debugEvent(
            diagnosticSessionId,
            "action_result",
            mapOf("action" to action.displayName(), "success" to result.success, "message" to result.message, "errorCode" to result.errorCode) +
                executionDiagnostics.actionTraceFields(action, beforeState, afterState, durationMs, result)
        )
        logs.log(if (result.success) ActionLogType.ACTION_RESULT else ActionLogType.ERROR, result.message, result.errorCode)
        return if (finishState) finish(result) else result
    }

    private suspend fun dispatch(action: DroidLmAction, transcript: String, diagnosticSessionId: String?): ActionResult = when (action) {
        is DroidLmAction.NoOp -> ActionResult.fail(action.message, "NO_OP")
        is DroidLmAction.NeedLlmPlanning -> handlePlanning(transcript, diagnosticSessionId)
        is DroidLmAction.AskConfirmation -> {
            val confirmed = requestConfirmation(transcript, action, action.reason, diagnosticSessionId, null)
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
        is DroidLmAction.NavigateToArtifactTarget -> navigateToArtifactTarget(action)
        is DroidLmAction.SetToggle -> portalController.setToggle(action.label, action.nodeId, action.value)
        is DroidLmAction.ExpandCollapse -> portalController.expandCollapse(action.label, action.nodeId, action.expanded)
        is DroidLmAction.SetSlider -> portalController.setSlider(action.label, action.nodeId, action.value, action.percent)
        is DroidLmAction.Refresh -> portalController.refresh(action.targetNodeId)
        is DroidLmAction.FindTextOnScreen -> portalController.findTextOnScreen(action.text, action.tapOnMatch)
        is DroidLmAction.SearchAccessibilityContent -> searchAccessibilityContent(action, diagnosticSessionId)
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
        is DroidLmAction.TypeText -> portalController.typeText(action.text, clear = action.clear)
        DroidLmAction.TakeScreenshot -> takeScreenshot(diagnosticSessionId)
        is DroidLmAction.FocusEditable -> focusEditable(action)
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
        is DroidLmAction.AnalyzeScreenshot -> runAnalyzeScreenshot(action.goal, diagnosticSessionId)
        is DroidLmAction.VerifyTextChange -> verifyTextChange(action.expectedText)
        is DroidLmAction.InsertTextAtAnchor -> textEditingController.insertTextAtAnchor(
            anchorText = action.anchorText,
            anchorPosition = action.anchorPosition,
            textToInsert = action.text,
            sectionLabel = action.sectionLabel,
            occurrenceIndex = action.occurrenceIndex
        )
        is DroidLmAction.ReplaceTextRange -> textEditingController.replaceText(
            targetText = action.targetText,
            replacementText = action.replacementText,
            sectionLabel = action.sectionLabel,
            occurrenceIndex = action.occurrenceIndex
        )
        is DroidLmAction.ApplyDocumentEdits -> textEditingController.applyDocumentEdits(action.sectionLabel, action.edits)
        is DroidLmAction.AppendText -> textEditingController.appendText(action.text)
        is DroidLmAction.PrependText -> textEditingController.prependText(action.text)
        DroidLmAction.SelectAll -> selectAllText()
        DroidLmAction.DeleteSelectedText -> textEditingController.replaceSelection("")
        is DroidLmAction.FormatCurrentLineAsBullet -> workspaceFileOperationController.formatCurrentLineAsBullet(transcript, action)
        is DroidLmAction.ReplaceDocumentText -> workspaceFileOperationController.replaceDocumentText(transcript, action)
        is DroidLmAction.AppendDocumentNote -> workspaceFileOperationController.appendDocumentNote(transcript, action)
        is DroidLmAction.SetCurrentSheetCell -> workspaceFileOperationController.setCurrentSheetCell(transcript, action)
        is DroidLmAction.AddSpreadsheetRow -> workspaceFileOperationController.addSpreadsheetRow(transcript, action)
        is DroidLmAction.ArtifactToolAction -> artifactToolExecutor.execute(action, transcript, diagnosticSessionId)
        DroidLmAction.Done -> ActionResult.ok("Done")
    }

    private suspend fun searchAccessibilityContent(action: DroidLmAction.SearchAccessibilityContent, diagnosticSessionId: String?): ActionResult {
        if (action.query.isNullOrBlank() && action.sectionLabel.isNullOrBlank() && action.exclude.isNullOrBlank()) {
            return ActionResult.fail("SEARCH_ACCESSIBILITY_CONTENT requires query, sectionLabel, or exclude", "INVALID_CONTENT_SEARCH")
        }
        val state = portalController.getState()
        val result = AccessibilityContentExtractor.search(
            state,
            AccessibilityContentSearchQuery(
                query = action.query,
                sectionLabel = action.sectionLabel,
                exclude = action.exclude,
                ordinal = action.ordinal,
                maxMatches = action.maxMatches
            )
        )
        val matchCount = result.optInt("matchCount", 0)
        debugEvent(
            diagnosticSessionId,
            "accessibility_content_search_result",
            mapOf(
                "queryLength" to (action.query?.length ?: 0),
                "sectionLength" to (action.sectionLabel?.length ?: 0),
                "excludeLength" to (action.exclude?.length ?: 0),
                "matchCount" to matchCount,
                "truncated" to result.optJSONObject("provenance")?.optBoolean("truncated")
            )
        )
        return if (matchCount > 0) {
            ActionResult.ok("Accessibility content search result: ${result.toString()}")
        } else {
            ActionResult.fail("No accessibility content matches: ${result.toString()}", "CONTENT_NOT_FOUND")
        }
    }

    private suspend fun openAppWithRecovery(
        action: DroidLmAction.OpenApp,
        transcript: String,
        diagnosticSessionId: String?
    ): ActionResult {
        val launchResult = portalController.openApp(action.packageName)
        val launchErrorCode = launchResult.errorCode
        if (launchResult.success || launchErrorCode == null || launchErrorCode !in ExecutionDiagnostics.MISSING_OR_UNLAUNCHABLE_APP_ERRORS) return launchResult

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
            transcript,
            storeAction,
            "$appName is not installed or cannot be launched on this device.",
            diagnosticSessionId,
            "$appName is not installed or cannot be launched. Open its Play Store listing?"
        )
        if (!accepted) return ActionResult.fail("App store listing was not opened because confirmation was not accepted", "CONFIRMATION_REJECTED")
        return portalController.openAppStoreListing(action.packageName, appName)
    }

    private suspend fun takeScreenshot(diagnosticSessionId: String?): ActionResult {
        val screenshot = portalController.takeScreenshot()
        debugEvent(
            diagnosticSessionId,
            "screenshot_capture_result",
            mapOf(
                "success" to screenshot.success,
                "hasBitmap" to (screenshot.bitmap != null),
                "errorCode" to screenshot.errorCode,
                "message" to screenshot.message
            )
        )
        if (screenshot.success && screenshot.bitmap != null) {
            debugLogStore?.retainScreenshot(screenshot.bitmap, "take-screenshot")
        }
        return if (screenshot.success) ActionResult.ok("Screenshot captured") else ActionResult.fail(screenshot.message, screenshot.errorCode)
    }

    private suspend fun focusEditable(action: DroidLmAction.FocusEditable): ActionResult {
        val requestedNodeId = action.nodeId?.trim()?.takeIf { it.isNotBlank() }
        if (requestedNodeId != null) {
            val target = portalController.findEditableNodes().firstOrNull { it.nodeId == requestedNodeId }
                ?: return ActionResult.fail("Requested editable target was not found: $requestedNodeId", "NO_EDITABLE")
            val nodeId = target.nodeId ?: return ActionResult.fail("Editable target has no node id", "NO_NODE_ID")
            return portalController.focusNode(nodeId)
        }
        val focused = textEditingController.getFocusedEditable()
            ?: return ActionResult.fail("No editable target found", "NO_EDITABLE")
        val nodeId = focused.nodeId ?: return ActionResult.fail("Editable target has no node id", "NO_NODE_ID")
        return if (focused.isFocused) ActionResult.ok("Editable target available") else portalController.focusNode(nodeId)
    }

    private suspend fun verifyTextChange(expectedText: String): ActionResult {
        val normalizedExpected = expectedText.trim()
        if (normalizedExpected.isBlank()) return ActionResult.fail("Expected text is blank", "EXPECTED_TEXT_BLANK")
        val focused = textEditingController.getFocusedEditable()
        if (focused != null) {
            val snapshot = textEditingController.readEditableText(focused)
            if (snapshot.text.contains(normalizedExpected, ignoreCase = true)) {
                return ActionResult.ok("Verified text change: $normalizedExpected")
            }
        }
        val state = portalController.getState()
        return if (state.hasVisibleText(normalizedExpected)) {
            ActionResult.ok("Verified text change: $normalizedExpected")
        } else {
            ActionResult.fail("Expected text was not visible: $normalizedExpected", "EXPECTED_TEXT_NOT_VISIBLE")
        }
    }

    private suspend fun navigateToArtifactTarget(action: DroidLmAction.NavigateToArtifactTarget): ActionResult {
        val label = action.label.trim()
        if (label.isBlank()) return ActionResult.fail("Artifact target label is blank", "ARTIFACT_TARGET_BLANK")
        val beforeState = portalController.getState()
        if (beforeState.hasVisibleText(label)) {
            return activateArtifactTarget(action, beforeState)
        }
        val scrollDown = portalController.scroll(ScrollDirection.DOWN, untilText = label)
        if (scrollDown.success) {
            return activateArtifactTarget(action, portalController.getState())
        }
        val scrollUp = portalController.scroll(ScrollDirection.UP, untilText = label)
        if (scrollUp.success) {
            return activateArtifactTarget(action, portalController.getState())
        }
        return scrollUp
    }

    private suspend fun activateArtifactTarget(action: DroidLmAction.NavigateToArtifactTarget, state: PortalState): ActionResult {
        return when (action.kind?.lowercase()) {
            "file", "folder", "control", "cell", "document", "visible_document", "collection_item" -> {
                val nodeId = action.nodeId?.takeIf { targetId -> state.nodes.any { node -> node.nodeId == targetId } }
                nodeId?.let { portalController.tapNode(it) } ?: portalController.tapText(action.label)
            }
            "tab", "sheet_tab" -> portalController.selectTab(action.label)
            else -> ActionResult.ok("Artifact target is visible: ${action.label}")
        }
    }

    private suspend fun runAnalyzeScreenshot(goal: String, diagnosticSessionId: String? = null): ActionResult {
        val settings = settingsRepository.settings.first()
        val analyzer = cloudScreenshotAnalyzer
        if (settings.privacyModeEnabled || !settings.cloudScreenshotAnalysisEnabled || analyzer == null || !analyzer.isConfigured()) {
            return runOcrScreen(diagnosticSessionId)
        }

        val startedAt = System.currentTimeMillis()
        debugEvent(diagnosticSessionId, "cloud_screenshot_analysis_started", mapOf("goalLength" to goal.length))
        val screenshotStartedAt = System.currentTimeMillis()
        val screenshot = portalController.takeScreenshot()
        debugEvent(
            diagnosticSessionId,
            "cloud_screenshot_capture_result",
            mapOf(
                "success" to screenshot.success,
                "hasBitmap" to (screenshot.bitmap != null),
                "width" to screenshot.bitmap?.width,
                "height" to screenshot.bitmap?.height,
                "durationMs" to (System.currentTimeMillis() - screenshotStartedAt),
                "errorCode" to screenshot.errorCode,
                "message" to screenshot.message
            )
        )
        if (!screenshot.success || screenshot.bitmap == null) {
            return ActionResult.fail(screenshot.message, screenshot.errorCode)
        }

        debugLogStore?.retainScreenshot(screenshot.bitmap, "cloud-screenshot-analysis")
        logs.log(ActionLogType.SCREENSHOT_CAPTURED, "Screenshot captured for cloud analysis")

        val deviceContextStartedAt = System.currentTimeMillis()
        val deviceContextResult = runCatching {
            deviceContextAggregator.collect(goal, portalController.getState(), diagnosticSessionId = diagnosticSessionId)
        }
        deviceContextResult
            .onSuccess { context ->
                debugEvent(
                    diagnosticSessionId,
                    "cloud_screenshot_device_context_collected",
                    mapOf(
                        "packageCount" to context.packages.size,
                        "activePackage" to context.activeApp?.packageName,
                        "extraKeyCount" to context.extras.length(),
                        "durationMs" to (System.currentTimeMillis() - deviceContextStartedAt)
                    )
                )
            }
            .onFailure { error ->
                debugEvent(
                    diagnosticSessionId,
                    "cloud_screenshot_device_context_failed",
                    mapOf(
                        "message" to error.message,
                        "errorClass" to error::class.java.name,
                        "durationMs" to (System.currentTimeMillis() - deviceContextStartedAt)
                    )
                )
            }
        val deviceContext = deviceContextResult.getOrNull()
        val analyzeStartedAt = System.currentTimeMillis()
        return runCatching { analyzer.analyze(screenshot.bitmap, goal, deviceContext) }
            .fold(
                onSuccess = {
                    logs.log(ActionLogType.OCR_RESULT, "Cloud screenshot analysis detected ${it.lines.size} lines")
                    debugEvent(
                        diagnosticSessionId,
                        "cloud_screenshot_analysis_result",
                        mapOf(
                            "lineCount" to it.lines.size,
                            "elementCount" to it.elements.size,
                            "fullTextLength" to it.fullText.length,
                            "recognizeDurationMs" to (System.currentTimeMillis() - analyzeStartedAt),
                            "totalDurationMs" to (System.currentTimeMillis() - startedAt)
                        )
                    )
                    ActionResult.ok("Cloud screenshot analysis detected ${it.lines.size} lines")
                },
                onFailure = {
                    debugEvent(
                        diagnosticSessionId,
                        "cloud_screenshot_analysis_failed",
                        mapOf(
                            "message" to it.message,
                            "errorClass" to it::class.java.name,
                            "recognizeDurationMs" to (System.currentTimeMillis() - analyzeStartedAt),
                            "totalDurationMs" to (System.currentTimeMillis() - startedAt)
                        )
                    )
                    ActionResult.fail("Cloud screenshot analysis failed: ${it.message}", "CLOUD_SCREENSHOT_ANALYSIS_FAILED")
                }
            )
    }

    private suspend fun runOcrScreen(diagnosticSessionId: String? = null): ActionResult {
        val startedAt = System.currentTimeMillis()
        debugEvent(diagnosticSessionId, "ocr_screen_started")
        val screenshotStartedAt = System.currentTimeMillis()
        val screenshot = portalController.takeScreenshot()
        debugEvent(
            diagnosticSessionId,
            "ocr_screenshot_result",
            mapOf(
                "success" to screenshot.success,
                "hasBitmap" to (screenshot.bitmap != null),
                "width" to screenshot.bitmap?.width,
                "height" to screenshot.bitmap?.height,
                "durationMs" to (System.currentTimeMillis() - screenshotStartedAt),
                "errorCode" to screenshot.errorCode,
                "message" to screenshot.message
            )
        )
        if (!screenshot.success || screenshot.bitmap == null) return ActionResult.fail(screenshot.message, screenshot.errorCode)
        debugLogStore?.retainScreenshot(screenshot.bitmap, "ocr-screen")
        logs.log(ActionLogType.SCREENSHOT_CAPTURED, "Screenshot captured for OCR")
        logs.log(ActionLogType.OCR_STARTED, "Running on-device OCR")
        val deviceContextStartedAt = System.currentTimeMillis()
        val deviceContextResult = runCatching { deviceContextAggregator.collect("Analyze screenshot", portalController.getState(), diagnosticSessionId = diagnosticSessionId) }
        deviceContextResult
            .onSuccess { context -> debugEvent(diagnosticSessionId, "ocr_device_context_collected", mapOf("packageCount" to context.packages.size, "activePackage" to context.activeApp?.packageName, "extraKeyCount" to context.extras.length(), "durationMs" to (System.currentTimeMillis() - deviceContextStartedAt))) }
            .onFailure { error -> debugEvent(diagnosticSessionId, "ocr_device_context_failed", mapOf("message" to error.message, "errorClass" to error::class.java.name, "durationMs" to (System.currentTimeMillis() - deviceContextStartedAt))) }
        val deviceContext = deviceContextResult.getOrNull()
        val recognizeStartedAt = System.currentTimeMillis()
        return runCatching { ocrEngine.recognize(screenshot.bitmap, deviceContext) }
            .fold(
                onSuccess = {
                    logs.log(ActionLogType.OCR_RESULT, "OCR detected ${it.lines.size} lines")
                    debugEvent(diagnosticSessionId, "ocr_result", mapOf("lineCount" to it.lines.size, "elementCount" to it.elements.size, "blockCount" to it.blocks.size, "fullTextLength" to it.fullText.length, "source" to it.source.name, "recognizeDurationMs" to (System.currentTimeMillis() - recognizeStartedAt), "totalDurationMs" to (System.currentTimeMillis() - startedAt)))
                    ActionResult.ok("OCR detected ${it.lines.size} lines")
                },
                onFailure = {
                    debugEvent(diagnosticSessionId, "ocr_failed", mapOf("message" to it.message, "errorClass" to it::class.java.name, "recognizeDurationMs" to (System.currentTimeMillis() - recognizeStartedAt), "totalDurationMs" to (System.currentTimeMillis() - startedAt)))
                    ActionResult.fail("OCR failed: ${it.message}", "OCR_FAILED")
                }
            )
    }

    private fun PortalState.hasVisibleText(expected: String): Boolean {
        val normalizedExpected = expected.trim().lowercase()
        if (normalizedExpected.isBlank()) return false
        return nodes.any { node ->
            listOfNotNull(node.text, node.contentDescription, node.hintText, node.stateDescription)
                .any { it.lowercase().contains(normalizedExpected) }
        }
    }

    private suspend fun selectAllText(): ActionResult {
        val target = textEditingController.getFocusedEditable() ?: return ActionResult.fail("No editable field found", "NO_EDITABLE")
        val text = textEditingController.readEditableText(target).text
        return textEditingController.setSelection(target, 0, text.length)
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

    private fun debugEvent(sessionId: String?, event: String, fields: Map<String, Any?> = emptyMap()) {
        executionDiagnostics.debugEvent(sessionId, event, fields)
    }
}
