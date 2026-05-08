package ai.droidlm.overlay

import ai.droidlm.intent.ActionUiFormatter
import ai.droidlm.relay.PlanPreview

object OverlayStatusFormatter {
    fun label(
        isStarting: Boolean,
        isListening: Boolean,
        partialTranscript: String,
        finalTranscript: String,
        executionStatus: String,
        lastResult: String,
        isStopping: Boolean = false
    ): String = when {
        isStarting -> microphoneStartingLabel()
        isListening -> "Listening..."
        isStopping -> "Processing speech..."
        executionStatus !in IDLE_STATUSES -> executionStatus.take(40)
        executionStatus == "Error" && lastResult.isNotBlank() -> compactResult(lastResult)
        finalTranscript.isNotBlank() -> "Heard: ${finalTranscript.take(40)}"
        lastResult.isNotBlank() -> compactResult(lastResult)
        else -> "Tap circle to speak"
    }

    fun recordButton(isActive: Boolean, executionStatus: String): String = when {
        isActive -> "■"
        executionStatus !in IDLE_STATUSES -> "×"
        else -> "●"
    }

    fun accessibilitySetupLabel(
        serviceName: String = "DroidLM Device Control",
        settingsOpened: Boolean = false
    ): String = "Enable Accessibility settings to unblock actions"

    fun microphonePermissionLabel(): String = "Enable microphone permission to record"

    fun microphoneReadyLabel(): String = "Mic enabled. Tap record to speak"
    fun microphoneStartingLabel(): String = "Starting microphone..."

    fun compactPlan(plan: PlanPreview, maxChars: Int = 96): String {
        val prefix = if (plan.riskLevel.equals("LOW", ignoreCase = true)) "Plan: " else "${plan.riskLevel.lowercase().replaceFirstChar { it.titlecase() }} risk: "
        val visibleSteps = plan.steps.take(3).joinToString(" > ") { step ->
            ActionUiFormatter.compact(step.action, step.actionLabel, step.reason)
        }
        val suffix = if (plan.steps.size > 3) " +${plan.steps.size - 3}" else ""
        return (prefix + visibleSteps + suffix).take(maxChars)
    }

    private fun compactResult(result: String): String = when {
        result.contains("OpenAI API key", ignoreCase = true) -> "OpenAI key needed"
        else -> result.take(40)
    }


    private val IDLE_STATUSES = setOf("Idle", "Error", "Cancelled")
}
