package ai.droidlm.overlay

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
        finalTranscript.isNotBlank() -> "Heard: ${finalTranscript.take(40)}"
        lastResult.isNotBlank() -> lastResult.take(40)
        else -> "Tap circle to speak"
    }

    fun recordButton(isListening: Boolean, executionStatus: String): String = when {
        isListening -> "■"
        executionStatus !in IDLE_STATUSES -> "×"
        else -> "●"
    }

    private val IDLE_STATUSES = setOf("Idle", "Error", "Cancelled")
}
