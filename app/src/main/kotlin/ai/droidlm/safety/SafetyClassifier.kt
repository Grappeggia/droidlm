package ai.droidlm.safety

import ai.droidlm.intent.DroidLmAction
import ai.droidlm.portal.PortalState

data class SafetyDecision(
    val requiresConfirmation: Boolean,
    val reason: String? = null,
    val category: String? = null,
    val blocked: Boolean = false
)

class SafetyClassifier {
    private val riskyKeywordCategories = listOf(
        "payment" to listOf("pay", "payment", "checkout", "purchase", "buy", "order", "subscribe", "send money", "transfer money"),
        "banking" to listOf("bank", "wire transfer", "crypto", "bitcoin", "wallet"),
        "credential" to listOf("password", "passcode", "pin", "credential", "login code", "one time code", "2fa"),
        "account deletion" to listOf("delete my account", "close my account", "deactivate my account"),
        "file deletion" to listOf("delete file", "delete folder", "erase", "remove all"),
        "private message" to listOf("send message", "send text", "send email", "reply to", "post to", "publish"),
        "security setting" to listOf("security settings", "privacy settings", "grant permission", "allow permission", "install app", "uninstall app"),
        "private data" to listOf("share my", "send my", "upload my", "screenshot this bank", "analyze this password")
    )

    private val sensitivePackageHints = listOf(
        "bank", "wallet", "pay", "paypal", "venmo", "cashapp", "crypto", "coinbase",
        "password", "authenticator", "health", "medical", "signal", "whatsapp", "telegram",
        "messag", "sms", "mail", "gmail", "outlook", "docs", "drive", "identity", "government"
    )

    fun classify(
        transcript: String,
        plannedAction: DroidLmAction? = null,
        currentState: PortalState? = null,
        sensitiveDenylist: String = ""
    ): SafetyDecision {
        val lower = transcript.lowercase()
        riskyKeywordCategories.firstOrNull { (_, words) -> words.any { lower.contains(it) } }?.let { (category, _) ->
            return SafetyDecision(
                requiresConfirmation = true,
                category = category,
                reason = "The command may involve $category and needs explicit confirmation"
            )
        }

        if (plannedAction is DroidLmAction.AnalyzeScreenshot) {
            val activePackage = currentState?.packageName.orEmpty().lowercase()
            if (isSensitivePackage(activePackage, sensitiveDenylist)) {
                return SafetyDecision(
                    requiresConfirmation = true,
                    category = "sensitive screenshot",
                    reason = "Cloud screenshot analysis may expose sensitive screen contents"
                )
            }
        }

        if (plannedAction is DroidLmAction.DeleteSelectedText) {
            return SafetyDecision(true, "Deleting selected text needs confirmation", "text deletion")
        }

        return SafetyDecision(false)
    }

    fun isSensitivePackage(packageNameOrLabel: String, denylist: String = ""): Boolean {
        val lower = packageNameOrLabel.lowercase()
        val configured = denylist.split(',', '\n', ';')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        return (configured + sensitivePackageHints).any { lower.contains(it) }
    }
}
