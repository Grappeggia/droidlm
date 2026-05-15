package ai.droidlm.execution

import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.SpeechTextNormalizer
import ai.droidlm.intent.displayName
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import ai.droidlm.safety.SafetyDecision
import ai.droidlm.settings.ExecutionMode

internal class ExecutionDiagnostics(
    private val diagnostics: SpeechDiagnosticsLogger,
    private val portalController: PortalController
) {
    fun debugEvent(sessionId: String?, event: String, fields: Map<String, Any?> = emptyMap()) {
        diagnostics.record(sessionId, "executor_$event", fields)
    }

    fun recordSafetyDecision(
        sessionId: String?,
        source: String,
        safety: SafetyDecision,
        requireRiskConfirmation: Boolean
    ) {
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

    fun transcriptQualityFields(transcript: String): Map<String, Any?> {
        val normalized = SpeechTextNormalizer.normalizeForRecognition(transcript)
        val words = normalized.split(' ').filter { it.isNotBlank() }
        val quality = when {
            normalized.isBlank() -> "blank"
            isAmbiguousOpenCommand(normalized) -> "ambiguous_open"
            words.size <= 1 -> "single_word"
            normalized.length < 6 -> "short"
            else -> "normal"
        }
        return mapOf(
            "transcriptLength" to transcript.length,
            "normalizedLength" to normalized.length,
            "wordCount" to words.size,
            "uniqueWordCount" to words.toSet().size,
            "quality" to quality,
            "ambiguousOpenCommand" to isAmbiguousOpenCommand(normalized),
            "startsWithOpenPrefix" to (requestedOpenAppName(normalized) != null)
        )
    }

    fun voiceRouteDecisionFields(action: DroidLmAction, executionMode: ExecutionMode): Map<String, Any?> {
        val route = when (action) {
            is DroidLmAction.NeedLlmPlanning -> when (executionMode) {
                ExecutionMode.LOCAL_RULE_FIRST -> "plan_preview"
                ExecutionMode.LOCAL_LLM_LOOP -> "local_llm_planning"
                ExecutionMode.AGENT_LOOP -> "agent_planning"
                ExecutionMode.MOBILERUN_CLOUD_TASK -> "mobilerun_planning"
            }
            else -> "local_parser"
        }
        return mapOf(
            "route" to route,
            "action" to action.displayName(),
            "actionType" to action.javaClass.simpleName,
            "executionMode" to executionMode.name,
            "needsAdvancedPlanning" to (action is DroidLmAction.NeedLlmPlanning)
        )
    }

    fun plannerBypassReason(action: DroidLmAction): String? = when (action) {
        is DroidLmAction.OpenApp -> "local_open_app_rule"
        is DroidLmAction.OpenAppStoreListing -> "local_open_app_store_rule"
        is DroidLmAction.NoOp -> "local_noop_or_clarification"
        is DroidLmAction.NeedLlmPlanning -> null
        else -> "local_rule_matched"
    }

    fun openAppResolutionFields(
        transcript: String,
        packages: List<AppPackage>,
        action: DroidLmAction
    ): Map<String, Any?>? {
        val normalized = SpeechTextNormalizer.normalizeForRecognition(transcript)
        val requestedName = requestedOpenAppName(normalized) ?: when (action) {
            is DroidLmAction.OpenApp -> action.appName
            is DroidLmAction.OpenAppStoreListing -> action.appName
            else -> null
        } ?: return null
        val candidates = packages.asSequence()
            .mapNotNull { packageResolutionCandidate(requestedName, it) }
            .take(MAX_OPEN_APP_CANDIDATES)
            .toList()
        return mapOf(
            "requestedAppName" to requestedName,
            "normalizedTranscriptLength" to normalized.length,
            "action" to action.displayName(),
            "actionType" to action.javaClass.simpleName,
            "resolvedPackageName" to when (action) {
                is DroidLmAction.OpenApp -> action.packageName
                is DroidLmAction.OpenAppStoreListing -> action.packageName
                else -> null
            },
            "resolvedAppName" to when (action) {
                is DroidLmAction.OpenApp -> action.appName
                is DroidLmAction.OpenAppStoreListing -> action.appName
                else -> null
            },
            "installedPackageCount" to packages.size,
            "candidateCount" to candidates.size,
            "candidates" to candidates
        )
    }

    fun isAmbiguousOpenCommand(transcript: String): Boolean {
        val normalized = SpeechTextNormalizer.normalizeForRecognition(transcript)
        return normalized in setOf("open", "launch", "start")
    }

    fun portalStateFields(state: PortalState): Map<String, Any?> = mapOf(
        "packageName" to state.packageName,
        "activityName" to state.activityName,
        "screenWidth" to state.screenWidth,
        "screenHeight" to state.screenHeight,
        "nodeCount" to state.nodes.size,
        "editableNodeCount" to state.nodes.count { it.editable },
        "focusedNodeCount" to state.nodes.count { it.focused }
    )

    suspend fun collectPortalStateForActionTrace(sessionId: String?, stage: String, action: DroidLmAction): PortalState? =
        runCatching { portalController.getState() }
            .onFailure { error ->
                debugEvent(
                    sessionId,
                    "action_portal_state_failed",
                    mapOf(
                        "stage" to stage,
                        "action" to action.displayName(),
                        "actionType" to action.javaClass.simpleName,
                        "errorClass" to error::class.java.name,
                        "message" to error.message
                    )
                )
            }
            .getOrNull()

    fun actionTraceFields(
        action: DroidLmAction,
        beforeState: PortalState?,
        afterState: PortalState?,
        durationMs: Long?,
        result: ActionResult?
    ): Map<String, Any?> {
        val targetIds = targetNodeIds(action)
        return mapOf(
            "actionType" to action.javaClass.simpleName,
            "actionTarget" to actionTargetSpec(action),
            "targetNodeIds" to targetIds,
            "retryCount" to 0,
            "durationMs" to durationMs,
            "failureReason" to result?.takeIf { !it.success }?.let { it.errorCode ?: it.message },
            "beforeState" to beforeState?.let(::portalTraceState),
            "afterState" to afterState?.let(::portalTraceState),
            "beforeFocusedNode" to beforeState?.nodes?.firstOrNull { it.focused }?.let(::nodeTraceFields),
            "afterFocusedNode" to afterState?.nodes?.firstOrNull { it.focused }?.let(::nodeTraceFields),
            "beforeTargetNodes" to nodeTraceFields(beforeState, targetIds),
            "afterTargetNodes" to nodeTraceFields(afterState, targetIds)
        )
    }

    private fun requestedOpenAppName(normalized: String): String? {
        val prefix = OPEN_APP_PREFIXES.firstOrNull { normalized.startsWith(it) } ?: return null
        return normalized.removePrefix(prefix).removeSuffix(" app").trim().takeIf { it.isNotBlank() }
    }

    private fun packageResolutionCandidate(requestedName: String, appPackage: AppPackage): Map<String, Any?>? {
        val requested = requestedName.lowercase()
        val label = appPackage.label.orEmpty()
        val normalizedLabel = label.lowercase()
        val normalizedPackage = appPackage.packageName.lowercase()
        val matchType = when {
            normalizedLabel == requested -> "label_exact"
            normalizedPackage == requested -> "package_exact"
            normalizedLabel.contains(requested) -> "label_contains_request"
            requested.contains(normalizedLabel) && normalizedLabel.length > 2 -> "request_contains_label"
            normalizedPackage.contains(requested) -> "package_contains_request"
            else -> null
        } ?: return null
        return mapOf(
            "matchType" to matchType,
            "packageName" to appPackage.packageName,
            "label" to label.take(MAX_NODE_TEXT_PREVIEW_CHARS),
            "enabled" to appPackage.enabled,
            "launchable" to appPackage.launchable,
            "launchActivityConfigured" to !appPackage.launchActivity.isNullOrBlank()
        )
    }

    private fun SafetyDecision.needsConfirmationPrompt(requireRiskConfirmation: Boolean): Boolean =
        requiresConfirmation && (requireRiskConfirmation || mandatoryConfirmation)

    private fun portalTraceState(state: PortalState): Map<String, Any?> = mapOf(
        "packageName" to state.packageName,
        "activityName" to state.activityName,
        "screenWidth" to state.screenWidth,
        "screenHeight" to state.screenHeight,
        "nodeCount" to state.nodes.size,
        "focusedNodeCount" to state.nodes.count { it.focused },
        "editableNodeCount" to state.nodes.count { it.editable },
        "clickableNodeCount" to state.nodes.count { it.clickable },
        "enabledNodeCount" to state.nodes.count { it.enabled }
    )

    private fun nodeTraceFields(state: PortalState?, targetIds: List<String>): List<Map<String, Any?>> =
        state?.let { portalState ->
            targetIds.distinct().mapNotNull { id -> portalState.nodes.firstOrNull { it.nodeId == id }?.let(::nodeTraceFields) }
        }.orEmpty()

    private fun nodeTraceFields(node: UiNode): Map<String, Any?> {
        val bounds = node.bounds
        return mapOf(
            "nodeId" to node.nodeId,
            "className" to node.className,
            "packageName" to node.packageName,
            "viewIdResourceName" to node.viewIdResourceName,
            "textLength" to (node.text?.length ?: 0),
            "textPreview" to if (node.password) null else node.text?.take(MAX_NODE_TEXT_PREVIEW_CHARS),
            "contentDescriptionLength" to (node.contentDescription?.length ?: 0),
            "contentDescriptionPreview" to node.contentDescription?.take(MAX_NODE_TEXT_PREVIEW_CHARS),
            "hintLength" to (node.hintText?.length ?: 0),
            "hintPreview" to node.hintText?.take(MAX_NODE_TEXT_PREVIEW_CHARS),
            "bounds" to bounds?.let { mapOf("left" to it.left, "top" to it.top, "right" to it.right, "bottom" to it.bottom, "width" to it.width(), "height" to it.height()) },
            "clickable" to node.clickable,
            "editable" to node.editable,
            "focused" to node.focused,
            "focusable" to node.focusable,
            "enabled" to node.enabled,
            "selected" to node.selected,
            "visible" to node.visible,
            "checkable" to node.checkable,
            "checked" to node.checked,
            "scrollable" to node.scrollable,
            "longClickable" to node.longClickable,
            "password" to node.password,
            "depth" to node.depth,
            "childIndex" to node.childIndex,
            "parentId" to node.parentId,
            "actions" to node.actions.take(MAX_NODE_ACTIONS_LOGGED),
            "effectiveActions" to node.effectiveActions.map { it.name }.take(MAX_NODE_ACTIONS_LOGGED),
            "rangeInfo" to node.rangeInfo?.let { mapOf("type" to it.type, "min" to it.min, "max" to it.max, "current" to it.current) },
            "collectionItem" to node.collectionItemInfo?.let { mapOf("rowIndex" to it.rowIndex, "columnIndex" to it.columnIndex, "selected" to it.selected, "heading" to it.heading) }
        )
    }

    private fun targetNodeIds(action: DroidLmAction): List<String> = listOfNotNull(
        when (action) {
            is DroidLmAction.TapNode -> action.nodeId
            is DroidLmAction.FocusNode -> action.nodeId
            is DroidLmAction.Scroll -> action.targetNodeId
            is DroidLmAction.TapText -> action.containerNodeId
            is DroidLmAction.LongPressNode -> action.nodeId
            is DroidLmAction.WaitForUi -> action.nodeId
            is DroidLmAction.NavigateToArtifactTarget -> action.nodeId
            is DroidLmAction.SetToggle -> action.nodeId
            is DroidLmAction.ExpandCollapse -> action.nodeId
            is DroidLmAction.SetSlider -> action.nodeId
            is DroidLmAction.Refresh -> action.targetNodeId
            is DroidLmAction.FocusEditable -> action.nodeId
            is DroidLmAction.SetSelection -> action.nodeId
            is DroidLmAction.SetFullText -> action.nodeId
            else -> null
        }
    )

    private fun actionTargetSpec(action: DroidLmAction): Map<String, Any?> = when (action) {
        is DroidLmAction.OpenApp -> mapOf("packageName" to action.packageName, "appName" to action.appName)
        is DroidLmAction.OpenAppStoreListing -> mapOf("packageName" to action.packageName, "appName" to action.appName)
        is DroidLmAction.Tap -> mapOf("x" to action.x, "y" to action.y)
        is DroidLmAction.TapNode -> mapOf("nodeId" to action.nodeId)
        is DroidLmAction.FocusNode -> mapOf("nodeId" to action.nodeId)
        is DroidLmAction.LongPress -> mapOf("x" to action.x, "y" to action.y, "durationMs" to action.durationMs)
        is DroidLmAction.Swipe -> mapOf("startX" to action.startX, "startY" to action.startY, "endX" to action.endX, "endY" to action.endY, "durationMs" to action.durationMs)
        is DroidLmAction.Scroll -> mapOf("direction" to action.direction.name, "targetNodeId" to action.targetNodeId, "untilTextLength" to (action.untilText?.length ?: 0), "amount" to action.amount)
        is DroidLmAction.TapText -> mapOf("textLength" to action.text.length, "textPreview" to action.text.take(MAX_NODE_TEXT_PREVIEW_CHARS), "role" to action.role, "containerNodeId" to action.containerNodeId)
        is DroidLmAction.LongPressNode -> mapOf("nodeId" to action.nodeId, "textLength" to (action.text?.length ?: 0), "textPreview" to action.text?.take(MAX_NODE_TEXT_PREVIEW_CHARS), "durationMs" to action.durationMs)
        is DroidLmAction.WaitForUi -> mapOf("textLength" to (action.text?.length ?: 0), "textPreview" to action.text?.take(MAX_NODE_TEXT_PREVIEW_CHARS), "packageName" to action.packageName, "nodeId" to action.nodeId, "timeoutMs" to action.timeoutMs)
        is DroidLmAction.PressImeAction -> mapOf("imeAction" to action.action.name)
        is DroidLmAction.DialogAction -> mapOf("buttonTextLength" to (action.buttonText?.length ?: 0), "buttonTextPreview" to action.buttonText?.take(MAX_NODE_TEXT_PREVIEW_CHARS), "role" to action.role?.name)
        is DroidLmAction.OpenMenu -> mapOf("menu" to action.menu.name)
        is DroidLmAction.SelectTab -> mapOf("labelLength" to action.label.length, "labelPreview" to action.label.take(MAX_NODE_TEXT_PREVIEW_CHARS))
        is DroidLmAction.NavigateToArtifactTarget -> mapOf("labelLength" to action.label.length, "labelPreview" to action.label.take(MAX_NODE_TEXT_PREVIEW_CHARS), "nodeId" to action.nodeId, "kind" to action.kind)
        is DroidLmAction.SetToggle -> mapOf("labelLength" to (action.label?.length ?: 0), "labelPreview" to action.label?.take(MAX_NODE_TEXT_PREVIEW_CHARS), "nodeId" to action.nodeId, "value" to action.value)
        is DroidLmAction.ExpandCollapse -> mapOf("labelLength" to (action.label?.length ?: 0), "labelPreview" to action.label?.take(MAX_NODE_TEXT_PREVIEW_CHARS), "nodeId" to action.nodeId, "expanded" to action.expanded)
        is DroidLmAction.SetSlider -> mapOf("labelLength" to (action.label?.length ?: 0), "labelPreview" to action.label?.take(MAX_NODE_TEXT_PREVIEW_CHARS), "nodeId" to action.nodeId, "value" to action.value, "percent" to action.percent)
        is DroidLmAction.Refresh -> mapOf("targetNodeId" to action.targetNodeId)
        is DroidLmAction.FindTextOnScreen -> mapOf("textLength" to action.text.length, "textPreview" to action.text.take(MAX_NODE_TEXT_PREVIEW_CHARS), "tapOnMatch" to action.tapOnMatch)
        is DroidLmAction.SwitchApp -> mapOf("appName" to action.appName, "packageName" to action.packageName)
        is DroidLmAction.OpenUrl -> mapOf("urlLength" to action.url.length)
        is DroidLmAction.OpenDeepLink -> mapOf("uriLength" to action.uri.length)
        is DroidLmAction.PickFromChooser -> mapOf("itemTextLength" to action.itemText.length, "itemTextPreview" to action.itemText.take(MAX_NODE_TEXT_PREVIEW_CHARS))
        is DroidLmAction.PickFile -> mapOf("fileNameLength" to action.fileName.length, "fileNamePreview" to action.fileName.take(MAX_NODE_TEXT_PREVIEW_CHARS))
        is DroidLmAction.PickPhoto -> mapOf("photoLabelLength" to action.photoLabel.length, "photoLabelPreview" to action.photoLabel.take(MAX_NODE_TEXT_PREVIEW_CHARS))
        is DroidLmAction.ShareToApp -> mapOf("appName" to action.appName, "packageName" to action.packageName)
        is DroidLmAction.PermissionDecision -> mapOf("allow" to action.allow)
        is DroidLmAction.TypeText -> mapOf("textLength" to action.text.length, "clear" to action.clear)
        is DroidLmAction.FocusEditable -> mapOf("nodeId" to action.nodeId)
        is DroidLmAction.SetSelection -> mapOf("nodeId" to action.nodeId, "start" to action.start, "end" to action.end)
        is DroidLmAction.InsertText -> mapOf("textLength" to action.text.length)
        is DroidLmAction.ReplaceSelection -> mapOf("textLength" to action.text.length)
        is DroidLmAction.SetFullText -> mapOf("nodeId" to action.nodeId, "textLength" to action.text.length)
        is DroidLmAction.MoveCursor -> mapOf("targetDescriptionLength" to action.targetDescription.length, "targetDescriptionPreview" to action.targetDescription.take(MAX_NODE_TEXT_PREVIEW_CHARS))
        is DroidLmAction.TapTextAnchor -> mapOf("anchorTextLength" to action.anchorText.length, "anchorTextPreview" to action.anchorText.take(MAX_NODE_TEXT_PREVIEW_CHARS), "anchorPosition" to action.anchorPosition.name)
        is DroidLmAction.AnalyzeScreenshot -> mapOf("goalLength" to action.goal.length)
        is DroidLmAction.VerifyTextChange -> mapOf("expectedTextLength" to action.expectedText.length)
        is DroidLmAction.InsertTextAtAnchor -> mapOf("anchorTextLength" to action.anchorText.length, "anchorTextPreview" to action.anchorText.take(MAX_NODE_TEXT_PREVIEW_CHARS), "anchorPosition" to action.anchorPosition.name, "textLength" to action.text.length)
        is DroidLmAction.ReplaceTextRange -> mapOf("targetTextLength" to action.targetText.length, "replacementTextLength" to action.replacementText.length)
        is DroidLmAction.AppendText -> mapOf("textLength" to action.text.length)
        is DroidLmAction.PrependText -> mapOf("textLength" to action.text.length)
        is DroidLmAction.FormatCurrentLineAsBullet -> mapOf("fileUriConfigured" to !action.fileUri.isNullOrBlank(), "bulletPrefixLength" to action.bulletPrefix.length)
        is DroidLmAction.ReplaceDocumentText -> mapOf("fileUriConfigured" to !action.fileUri.isNullOrBlank(), "targetTextLength" to action.targetText.length, "replacementTextLength" to action.replacementText.length)
        is DroidLmAction.AppendDocumentNote -> mapOf("fileUriConfigured" to !action.fileUri.isNullOrBlank(), "noteLength" to action.note.length)
        is DroidLmAction.SetCurrentSheetCell -> mapOf("fileUriConfigured" to !action.fileUri.isNullOrBlank(), "valueLength" to action.value.length)
        is DroidLmAction.AddSpreadsheetRow -> mapOf("fileUriConfigured" to !action.fileUri.isNullOrBlank(), "valueCount" to action.values.size, "totalValueLength" to action.values.sumOf { it.length })
        is DroidLmAction.NoOp,
        is DroidLmAction.NeedLlmPlanning,
        is DroidLmAction.AskConfirmation,
        is DroidLmAction.OpenSettings,
        DroidLmAction.PressHome,
        DroidLmAction.PressBack,
        DroidLmAction.OpenNotifications,
        DroidLmAction.OpenQuickSettings,
        DroidLmAction.OpenRecents,
        DroidLmAction.TakeScreenshot,
        DroidLmAction.OcrScreen,
        DroidLmAction.SelectAll,
        DroidLmAction.DeleteSelectedText,
        DroidLmAction.Done -> emptyMap()
    }

    companion object {
        val MISSING_OR_UNLAUNCHABLE_APP_ERRORS = setOf("APP_NOT_INSTALLED", "APP_DISABLED", "APP_NOT_LAUNCHABLE", "APP_NOT_FOUND")
        private val OPEN_APP_PREFIXES = listOf("open my ", "open the ", "open ", "launch my ", "launch the ", "launch ", "start my ", "start the ", "start ")
        private const val MAX_NODE_TEXT_PREVIEW_CHARS = 80
        private const val MAX_NODE_ACTIONS_LOGGED = 12
        private const val MAX_OPEN_APP_CANDIDATES = 5
    }
}
