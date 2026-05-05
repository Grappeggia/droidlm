package ai.droidlm.logs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ActionLogType {
    WAKE_DETECTED,
    RECORDING_STARTED,
    RECORDING_STOPPED,
    TRANSCRIPTION_REQUEST,
    TRANSCRIPTION_RESULT,
    PARSED_ACTION,
    PLANNER_STARTED,
    PLANNER_RESULT,
    ACTION_STARTED,
    ACTION_RESULT,
    OCR_STARTED,
    OCR_RESULT,
    SCREENSHOT_CAPTURED,
    TEXT_EDIT_STARTED,
    TEXT_EDIT_RESULT,
    ERROR,
    CANCELLED,
    CONFIRMATION_REQUIRED,
    CONFIRMATION_ACCEPTED,
    CONFIRMATION_REJECTED
}

data class ActionLogEntry(
    val type: ActionLogType,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val details: String? = null
)

class ActionLogRepository(
    private val maxEntries: Int = 250
) {
    private val _logs = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    val logs: StateFlow<List<ActionLogEntry>> = _logs.asStateFlow()

    fun log(type: ActionLogType, message: String, details: String? = null) {
        _logs.update { current ->
            (listOf(ActionLogEntry(type, message, details = details)) + current).take(maxEntries)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
