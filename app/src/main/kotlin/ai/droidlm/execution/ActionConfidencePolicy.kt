package ai.droidlm.execution

import ai.droidlm.agent.ToolRisk
import ai.droidlm.intent.ActionConfidence
import ai.droidlm.intent.DroidLmAction

internal data class ActionConfidencePolicyResult(
    val allowed: Boolean,
    val requiresConfirmation: Boolean = false,
    val reason: String? = null
)

internal object ActionConfidencePolicy {
    private val highRiskTools = setOf(
        ToolRisk.SENSITIVE,
        ToolRisk.EXTERNAL_SHARE,
        ToolRisk.INSTALL_OR_STORE,
        ToolRisk.PERMISSION_OR_CREDENTIAL
    )

    fun evaluate(
        confidence: ActionConfidence,
        action: DroidLmAction,
        risk: ToolRisk = riskForAction(action),
        mutating: Boolean = mutatesUi(action),
        safetyRequiresConfirmation: Boolean = false
    ): ActionConfidencePolicyResult {
        val highRisk = safetyRequiresConfirmation || risk in highRiskTools
        if (highRisk) {
            return ActionConfidencePolicyResult(
                allowed = true,
                requiresConfirmation = true,
                reason = "High-risk action requires confirmation regardless of model confidence"
            )
        }

        return when (confidence) {
            ActionConfidence.HIGH -> ActionConfidencePolicyResult(allowed = true)
            ActionConfidence.MEDIUM -> {
                if (isReversibleLowRisk(risk, action)) {
                    ActionConfidencePolicyResult(allowed = true)
                } else {
                    ActionConfidencePolicyResult(
                        allowed = false,
                        reason = "Medium-confidence ${action.displayNameForPolicy()} is not reversible low risk"
                    )
                }
            }
            ActionConfidence.LOW -> {
                if (!mutating || isObservationOrSearch(action)) {
                    ActionConfidencePolicyResult(allowed = true)
                } else {
                    ActionConfidencePolicyResult(
                        allowed = false,
                        reason = "Low-confidence ${action.displayNameForPolicy()} must gather observation, search, OCR, or ask the user before mutating"
                    )
                }
            }
        }
    }

    fun riskForAction(action: DroidLmAction): ToolRisk = when (action) {
        is DroidLmAction.NoOp,
        is DroidLmAction.NeedLlmPlanning,
        is DroidLmAction.WaitForUi,
        is DroidLmAction.FindTextOnScreen,
        is DroidLmAction.SearchAccessibilityContent,
        is DroidLmAction.FocusEditable,
        is DroidLmAction.VerifyTextChange,
        DroidLmAction.Done -> ToolRisk.READ_ONLY
        is DroidLmAction.ArtifactToolAction -> artifactToolRisk(action)

        is DroidLmAction.OpenApp,
        is DroidLmAction.OpenSettings,
        DroidLmAction.PressHome,
        DroidLmAction.PressBack,
        is DroidLmAction.Tap,
        is DroidLmAction.TapNode,
        is DroidLmAction.FocusNode,
        is DroidLmAction.LongPress,
        is DroidLmAction.Swipe,
        is DroidLmAction.Scroll,
        is DroidLmAction.TapText,
        is DroidLmAction.LongPressNode,
        is DroidLmAction.OpenMenu,
        is DroidLmAction.SelectTab,
        is DroidLmAction.NavigateToArtifactTarget,
        is DroidLmAction.ExpandCollapse,
        is DroidLmAction.Refresh,
        DroidLmAction.OpenNotifications,
        DroidLmAction.OpenQuickSettings,
        DroidLmAction.OpenRecents,
        is DroidLmAction.SwitchApp -> ToolRisk.SAFE_NAVIGATION

        is DroidLmAction.SetSlider,
        is DroidLmAction.PressImeAction,
        is DroidLmAction.TypeText,
        is DroidLmAction.SetSelection,
        is DroidLmAction.InsertText,
        is DroidLmAction.ReplaceSelection,
        is DroidLmAction.MoveCursor,
        is DroidLmAction.TapTextAnchor,
        is DroidLmAction.InsertTextAtAnchor,
        is DroidLmAction.ReplaceTextRange,
        is DroidLmAction.ApplyDocumentEdits,
        is DroidLmAction.AppendText,
        is DroidLmAction.PrependText,
        is DroidLmAction.FormatCurrentLineAsBullet,
        is DroidLmAction.AppendDocumentNote,
        is DroidLmAction.SetCurrentSheetCell,
        is DroidLmAction.AddSpreadsheetRow,
        DroidLmAction.SelectAll -> ToolRisk.USER_VISIBLE_EDIT

        is DroidLmAction.AskConfirmation,
        is DroidLmAction.SetToggle,
        is DroidLmAction.DialogAction,
        DroidLmAction.TakeScreenshot,
        is DroidLmAction.SetFullText,
        DroidLmAction.OcrScreen,
        is DroidLmAction.AnalyzeScreenshot,
        is DroidLmAction.DeleteSelectedText,
        is DroidLmAction.ReplaceDocumentText -> ToolRisk.SENSITIVE

        is DroidLmAction.OpenUrl,
        is DroidLmAction.OpenDeepLink,
        is DroidLmAction.PickFromChooser,
        is DroidLmAction.PickFile,
        is DroidLmAction.PickPhoto,
        is DroidLmAction.ShareToApp -> ToolRisk.EXTERNAL_SHARE

        is DroidLmAction.OpenAppStoreListing -> ToolRisk.INSTALL_OR_STORE
        is DroidLmAction.PermissionDecision -> ToolRisk.PERMISSION_OR_CREDENTIAL
    }

    fun mutatesUi(action: DroidLmAction): Boolean = when (action) {
        is DroidLmAction.NoOp,
        is DroidLmAction.NeedLlmPlanning,
        is DroidLmAction.WaitForUi,
        is DroidLmAction.FindTextOnScreen,
        is DroidLmAction.SearchAccessibilityContent,
        DroidLmAction.TakeScreenshot,
        is DroidLmAction.FocusEditable,
        DroidLmAction.OcrScreen,
        is DroidLmAction.AnalyzeScreenshot,
        is DroidLmAction.VerifyTextChange,
        is DroidLmAction.ArtifactToolAction.GetStructure,
        is DroidLmAction.ArtifactToolAction.ResolveTarget,
        is DroidLmAction.ArtifactToolAction.GetContentWindow,
        is DroidLmAction.ArtifactToolAction.GetSelectionState,
        is DroidLmAction.ArtifactToolAction.VerifyEndState,
        is DroidLmAction.ArtifactToolAction.DocGetTargetMetadata,
        is DroidLmAction.ArtifactToolAction.DocExtractActionItems,
        is DroidLmAction.ArtifactToolAction.SheetResolveRange,
        is DroidLmAction.ArtifactToolAction.SheetValidateTableState,
        is DroidLmAction.ArtifactToolAction.NotionResolveBlockOrPage,
        DroidLmAction.Done -> false
        else -> true
    }

    private fun isObservationOrSearch(action: DroidLmAction): Boolean = when (action) {
        is DroidLmAction.WaitForUi,
        is DroidLmAction.FindTextOnScreen,
        is DroidLmAction.SearchAccessibilityContent,
        DroidLmAction.TakeScreenshot,
        DroidLmAction.OcrScreen,
        is DroidLmAction.AnalyzeScreenshot,
        is DroidLmAction.VerifyTextChange -> true
        is DroidLmAction.ArtifactToolAction.GetStructure,
        is DroidLmAction.ArtifactToolAction.ResolveTarget,
        is DroidLmAction.ArtifactToolAction.GetContentWindow,
        is DroidLmAction.ArtifactToolAction.GetSelectionState,
        is DroidLmAction.ArtifactToolAction.VerifyEndState,
        is DroidLmAction.ArtifactToolAction.DocGetTargetMetadata,
        is DroidLmAction.ArtifactToolAction.DocExtractActionItems,
        is DroidLmAction.ArtifactToolAction.SheetResolveRange,
        is DroidLmAction.ArtifactToolAction.SheetValidateTableState,
        is DroidLmAction.ArtifactToolAction.NotionResolveBlockOrPage -> true
        else -> false
    }

    private fun artifactToolRisk(action: DroidLmAction.ArtifactToolAction): ToolRisk = when (action) {
        is DroidLmAction.ArtifactToolAction.GetStructure,
        is DroidLmAction.ArtifactToolAction.ResolveTarget,
        is DroidLmAction.ArtifactToolAction.GetContentWindow,
        is DroidLmAction.ArtifactToolAction.GetSelectionState,
        is DroidLmAction.ArtifactToolAction.VerifyEndState,
        is DroidLmAction.ArtifactToolAction.DocGetTargetMetadata,
        is DroidLmAction.ArtifactToolAction.DocExtractActionItems,
        is DroidLmAction.ArtifactToolAction.SheetResolveRange,
        is DroidLmAction.ArtifactToolAction.SheetValidateTableState,
        is DroidLmAction.ArtifactToolAction.NotionResolveBlockOrPage -> ToolRisk.READ_ONLY

        is DroidLmAction.ArtifactToolAction.NavigateToTarget,
        is DroidLmAction.ArtifactToolAction.ScrollToMatch -> ToolRisk.SAFE_NAVIGATION

        is DroidLmAction.ArtifactToolAction.DocDeleteTargetText,
        is DroidLmAction.ArtifactToolAction.SheetInsertDeleteRowsColumns -> ToolRisk.SENSITIVE

        else -> ToolRisk.USER_VISIBLE_EDIT
    }


    private fun isReversibleLowRisk(risk: ToolRisk, action: DroidLmAction): Boolean {
        if (risk !in setOf(ToolRisk.READ_ONLY, ToolRisk.SAFE_NAVIGATION, ToolRisk.USER_VISIBLE_EDIT)) return false
        return action !is DroidLmAction.DeleteSelectedText && action !is DroidLmAction.SetFullText
    }

    private fun DroidLmAction.displayNameForPolicy(): String = javaClass.simpleName.ifBlank { toString() }
}
