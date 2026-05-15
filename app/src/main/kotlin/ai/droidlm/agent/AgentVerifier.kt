package ai.droidlm.agent

import ai.droidlm.context.ArtifactContextBuilder
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.PortalState
import org.json.JSONObject

enum class AgentVerificationStatus {
    VERIFIED,
    NOT_APPLICABLE,
    FAILED
}

data class AgentVerificationResult(
    val status: AgentVerificationStatus,
    val message: String,
    val expected: String? = null,
    val actual: String? = null
) {
    val failed: Boolean get() = status == AgentVerificationStatus.FAILED

    fun toJson(): JSONObject = JSONObject()
        .put("status", status.name)
        .put("message", message)
        .put("expected", expected ?: JSONObject.NULL)
        .put("actual", actual ?: JSONObject.NULL)

    companion object {
        fun verified(message: String, expected: String? = null, actual: String? = null) =
            AgentVerificationResult(AgentVerificationStatus.VERIFIED, message, expected, actual)

        fun notApplicable(message: String) =
            AgentVerificationResult(AgentVerificationStatus.NOT_APPLICABLE, message)

        fun failed(message: String, expected: String? = null, actual: String? = null) =
            AgentVerificationResult(AgentVerificationStatus.FAILED, message, expected, actual)
    }
}

class AgentVerifier {
    fun needsFreshState(action: DroidLmAction): Boolean = when (action) {
        is DroidLmAction.OpenApp,
        is DroidLmAction.NavigateToArtifactTarget,
        is DroidLmAction.WaitForUi,
        is DroidLmAction.FindTextOnScreen,
        is DroidLmAction.VerifyTextChange -> true
        else -> false
    }

    fun verify(
        action: DroidLmAction,
        actionResult: ActionResult,
        beforeState: PortalState?,
        afterState: PortalState?,
        goal: String? = null,
        deviceContextExtras: JSONObject? = null
    ): AgentVerificationResult {
        if (!actionResult.success) {
            return AgentVerificationResult.failed(
                message = "Tool execution failed: ${actionResult.message}",
                expected = action.displayNameForVerification(),
                actual = actionResult.errorCode ?: actionResult.message
            )
        }
        return when (action) {
            is DroidLmAction.OpenApp -> verifyOpenApp(action, beforeState, afterState, goal, deviceContextExtras)
            is DroidLmAction.NavigateToArtifactTarget -> verifyArtifactTargetVisible(action, beforeState, afterState)
            is DroidLmAction.WaitForUi -> verifyWaitForUi(action, afterState)
            is DroidLmAction.FindTextOnScreen -> verifyTextVisible(action.text, afterState, "Expected text to be visible")
            is DroidLmAction.VerifyTextChange -> verifyTextVisible(action.expectedText, afterState, "Expected edited text to be visible")
            is DroidLmAction.Done -> AgentVerificationResult.verified("Agent reported task complete")
            else -> verifyFreshStateAvailable(action, beforeState, afterState)
        }
    }

    private fun verifyOpenApp(
        action: DroidLmAction.OpenApp,
        beforeState: PortalState?,
        afterState: PortalState?,
        goal: String?,
        deviceContextExtras: JSONObject?
    ): AgentVerificationResult {
        val query = goal?.let(ArtifactContextBuilder::extractNavigationRequest)
        val artifactContext = deviceContextExtras?.optJSONObject("artifactContext")
        val matchingTargetExists = query != null &&
            ArtifactContextBuilder.supportsArtifactPackage(beforeState?.packageName) &&
            ArtifactContextBuilder.hasMatchingTarget(artifactContext, query)
        if (matchingTargetExists && beforeState?.packageName != null && beforeState.packageName != action.packageName) {
            return AgentVerificationResult.failed(
                "Opened another app even though the current artifact already contains \"$query\"",
                beforeState.packageName,
                afterState?.packageName ?: "unknown"
            )
        }
        return verifyActivePackage(action.packageName, afterState)
    }

    private fun verifyActivePackage(packageName: String, afterState: PortalState?): AgentVerificationResult {
        val actualPackage = afterState?.packageName
        return if (actualPackage == packageName) {
            AgentVerificationResult.verified("Active package matches launched app", packageName, actualPackage)
        } else {
            AgentVerificationResult.failed("Active package did not match launched app", packageName, actualPackage ?: "unknown")
        }
    }

    private fun verifyArtifactTargetVisible(
        action: DroidLmAction.NavigateToArtifactTarget,
        beforeState: PortalState?,
        afterState: PortalState?
    ): AgentVerificationResult {
        if (afterState == null) {
            return AgentVerificationResult.failed("No fresh UI state available for artifact navigation", action.label, "unknown")
        }
        if (afterState.hasVisibleText(action.label)) {
            return AgentVerificationResult.verified("Artifact target is visible", action.label, visibleText(afterState))
        }
        return verifyFreshStateAvailable(action, beforeState, afterState)
    }

    private fun verifyWaitForUi(action: DroidLmAction.WaitForUi, afterState: PortalState?): AgentVerificationResult {
        if (afterState == null) {
            return AgentVerificationResult.failed("No fresh UI state available for wait verification", expectedDescription(action), "unknown")
        }
        action.packageName?.let { packageName ->
            if (afterState.packageName != packageName) {
                return AgentVerificationResult.failed("Wait target package is not active", packageName, afterState.packageName ?: "unknown")
            }
        }
        action.nodeId?.let { nodeId ->
            if (afterState.nodes.none { it.nodeId == nodeId }) {
                return AgentVerificationResult.failed("Wait target node is not visible", nodeId, visibleNodeIds(afterState))
            }
        }
        action.text?.let { text ->
            if (!afterState.hasVisibleText(text)) {
                return AgentVerificationResult.failed("Wait target text is not visible", text, visibleText(afterState))
            }
        }
        return AgentVerificationResult.verified("Wait target is visible", expectedDescription(action), afterState.packageName)
    }

    private fun verifyTextVisible(text: String, afterState: PortalState?, message: String): AgentVerificationResult {
        if (text.isBlank()) return AgentVerificationResult.notApplicable("No text expectation was provided")
        if (afterState == null) return AgentVerificationResult.failed("No fresh UI state available for text verification", text, "unknown")
        return if (afterState.hasVisibleText(text)) {
            AgentVerificationResult.verified(message, text, visibleText(afterState))
        } else {
            AgentVerificationResult.failed(message, text, visibleText(afterState))
        }
    }

    private fun verifyFreshStateAvailable(
        action: DroidLmAction,
        beforeState: PortalState?,
        afterState: PortalState?
    ): AgentVerificationResult {
        if (!requiresChangedUi(action)) return AgentVerificationResult.notApplicable("No deterministic verifier for ${action.displayNameForVerification()}")
        if (afterState == null) return AgentVerificationResult.failed("No fresh UI state available after ${action.displayNameForVerification()}")
        val beforeSignature = beforeState?.signature()
        val afterSignature = afterState.signature()
        return if (beforeSignature == null || beforeSignature != afterSignature) {
            AgentVerificationResult.verified("Fresh UI observation captured after ${action.displayNameForVerification()}")
        } else {
            AgentVerificationResult.notApplicable("UI signature did not change after ${action.displayNameForVerification()}, but no strict verifier is available")
        }
    }

    private fun requiresChangedUi(action: DroidLmAction): Boolean = when (action) {
        is DroidLmAction.Tap,
        is DroidLmAction.TapNode,
        is DroidLmAction.TapText,
        is DroidLmAction.Scroll,
        is DroidLmAction.Swipe,
        is DroidLmAction.PressBack,
        is DroidLmAction.OpenSettings,
        is DroidLmAction.SwitchApp,
        is DroidLmAction.NavigateToArtifactTarget,
        is DroidLmAction.OpenAppStoreListing -> true
        else -> false
    }

    private fun PortalState.hasVisibleText(expected: String): Boolean {
        val normalizedExpected = expected.trim().lowercase()
        if (normalizedExpected.isBlank()) return false
        return nodes.any { node ->
            listOfNotNull(node.text, node.contentDescription, node.hintText, node.stateDescription)
                .any { it.lowercase().contains(normalizedExpected) }
        }
    }

    private fun visibleText(state: PortalState): String = state.nodes
        .flatMap { node -> listOfNotNull(node.text, node.contentDescription, node.hintText, node.stateDescription) }
        .filter { it.isNotBlank() }
        .take(12)
        .joinToString(" | ")

    private fun visibleNodeIds(state: PortalState): String = state.nodes
        .mapNotNull { it.nodeId }
        .take(12)
        .joinToString(",")

    private fun expectedDescription(action: DroidLmAction.WaitForUi): String = listOfNotNull(
        action.packageName?.let { "package=$it" },
        action.nodeId?.let { "node=$it" },
        action.text?.let { "text=$it" }
    ).joinToString(" ").ifBlank { "any UI update" }

    private fun PortalState.signature(): String = buildString {
        append(packageName).append('|').append(activityName).append('|').append(nodes.size).append('|')
        nodes.take(20).forEach { node ->
            append(node.nodeId).append(':').append(node.text).append(':').append(node.contentDescription).append(';')
        }
    }

    private fun DroidLmAction.displayNameForVerification(): String = javaClass.simpleName.ifBlank { toString() }
}
