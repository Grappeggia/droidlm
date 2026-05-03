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

    private fun compactResult(result: String): String = when {
        result.contains("OpenAI API key", ignoreCase = true) -> "OpenAI key needed"
        else -> result.take(40)
    }


    private val IDLE_STATUSES = setOf("Idle", "Error", "Cancelled")
}
