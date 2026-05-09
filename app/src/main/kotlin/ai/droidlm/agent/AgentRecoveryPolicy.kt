package ai.droidlm.agent

import ai.droidlm.intent.DroidLmAction

data class AgentRecoveryCandidate(
    val action: DroidLmAction,
    val key: String,
    val reason: String
)

class AgentRecoveryPolicy {
    fun recoverValidationFailure(call: AgentToolCall, errorMessage: String?): AgentRecoveryCandidate? {
        val message = errorMessage.orEmpty().lowercase()
        if (call.name != "OPEN_APP") return null
        if (!message.contains("not installed") && !message.contains("not launchable") && !message.contains("disabled")) return null
        val packageName = call.args.optString("packageName").takeIf { it.isNotBlank() } ?: return null
        val appName = call.args.optString("appName").takeIf { it.isNotBlank() }
        return AgentRecoveryCandidate(
            action = DroidLmAction.OpenAppStoreListing(
                appName = appName,
                packageName = packageName,
                reason = "Requested app is not installed or launchable; open app store listing"
            ),
            key = "validation:open_store:$packageName",
            reason = "Recover missing app by opening its app store listing"
        )
    }

    fun recoverVerificationFailure(
        action: DroidLmAction,
        verification: AgentVerificationResult
    ): AgentRecoveryCandidate? {
        if (!verification.failed) return null
        return when (action) {
            is DroidLmAction.OpenApp -> AgentRecoveryCandidate(
                action = DroidLmAction.WaitForUi(
                    text = null,
                    packageName = action.packageName,
                    nodeId = null,
                    timeoutMs = 4_000,
                    reason = "Wait for launched app to become active"
                ),
                key = "verify:wait_open:${action.packageName}",
                reason = "Recover app-launch verification by waiting for the target package"
            )
            is DroidLmAction.WaitForUi -> action.text?.takeIf { it.isNotBlank() }?.let { text ->
                AgentRecoveryCandidate(
                    action = DroidLmAction.FindTextOnScreen(text = text, tapOnMatch = false, reason = "Find text after wait target was not visible"),
                    key = "verify:find_text:${text.lowercase()}",
                    reason = "Recover wait verification by searching visible text"
                )
            }
            is DroidLmAction.VerifyTextChange -> action.expectedText.takeIf { it.isNotBlank() }?.let { text ->
                AgentRecoveryCandidate(
                    action = DroidLmAction.FindTextOnScreen(text = text, tapOnMatch = false, reason = "Find expected edited text"),
                    key = "verify:find_expected_text:${text.lowercase()}",
                    reason = "Recover text verification by searching for expected text"
                )
            }
            else -> null
        }
    }
}

