package ai.droidlm.overlay

object OverlayStatusFormatter {
    fun label(
        isListening: Boolean,
        partialTranscript: String,
        finalTranscript: String,
        executionStatus: String,
        lastResult: String
    ): String = when {
        isListening && partialTranscript.isNotBlank() -> "Heard: ${partialTranscript.take(40)}"
        isListening -> "Listening..."
        executionStatus !in setOf("Idle", "Error") -> executionStatus.take(40)
        finalTranscript.isNotBlank() -> "Heard: ${finalTranscript.take(40)}"
        lastResult.isNotBlank() -> lastResult.take(40)
        else -> "Tap circle to speak"
    }

    fun recordButton(isListening: Boolean, executionStatus: String): String = when {
        isListening -> "■"
        executionStatus !in setOf("Idle", "Error") -> "×"
        else -> "●"
    }
}
