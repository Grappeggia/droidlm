package ai.droidlm.overlay

import ai.droidlm.relay.PlanPreview

object OverlayStatusFormatter {
    fun label(
        isListening: Boolean,
        partialTranscript: String,
        finalTranscript: String,
        executionStatus: String,
        lastResult: String
    ): String = when {
        isListening -> "Listening..."
        executionStatus !in IDLE_STATUSES -> executionStatus.take(40)
        executionStatus == "Error" && lastResult.isNotBlank() -> compactResult(lastResult)
        finalTranscript.isNotBlank() -> "Heard: ${finalTranscript.take(40)}"
        lastResult.isNotBlank() -> compactResult(lastResult)
        else -> "Tap circle to speak"
    }

    fun recordButton(isListening: Boolean, executionStatus: String): String = when {
        isListening -> "■"
        executionStatus !in IDLE_STATUSES -> "×"
        else -> "●"
    }

    fun accessibilitySetupLabel(
        serviceName: String = "DroidLM Device Control",
        settingsOpened: Boolean = false
    ): String = "Enable Accessibility settings to unblock actions"

    fun microphonePermissionLabel(): String = "Enable microphone permission to record"

    fun compactPlan(plan: PlanPreview, maxChars: Int = 96): String {
        val prefix = if (plan.riskLevel.equals("LOW", ignoreCase = true)) "P:" else "P[${plan.riskLevel.uppercase()}]:"
        val visibleSteps = plan.steps.take(4).joinToString(">") { compactStep(it.actionLabel) }
        val suffix = if (plan.steps.size > 4) "+${plan.steps.size - 4}" else ""
        return (prefix + visibleSteps + suffix).take(maxChars)
    }

    private fun compactStep(label: String): String {
        return label
            .replace("Google ", "G", ignoreCase = true)
            .replace("Open ", "O:", ignoreCase = true)
            .replace("Tap ", "T:", ignoreCase = true)
            .replace("Type ", "Ty:", ignoreCase = true)
            .replace(" ", "")
            .take(18)
    }

    private fun compactResult(result: String): String = when {
        result.contains("OpenAI API key", ignoreCase = true) -> "OpenAI key needed"
        else -> result.take(40)
    }


    private val IDLE_STATUSES = setOf("Idle", "Error", "Cancelled")
}
