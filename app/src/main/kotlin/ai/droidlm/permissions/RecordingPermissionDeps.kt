package ai.droidlm.permissions

import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.logs.ActionLogRepository

data class RecordingPermissionDeps(
    val actionLogRepository: ActionLogRepository,
    val speechDiagnosticsLogger: SpeechDiagnosticsLogger
)
