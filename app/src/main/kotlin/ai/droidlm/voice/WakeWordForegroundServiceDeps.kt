package ai.droidlm.voice

import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.execution.DroidLmExecutor
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.runtime.ListeningRuntime
import ai.droidlm.runtime.OverlayRuntime
import ai.droidlm.settings.SettingsRepository

data class WakeWordForegroundServiceDeps(
    val settingsRepository: SettingsRepository,
    val actionLogRepository: ActionLogRepository,
    val speechDiagnosticsLogger: SpeechDiagnosticsLogger,
    val executor: DroidLmExecutor,
    val commandRecorder: CommandRecorder,
    val speechRecognitionController: SpeechRecognitionController,
    val listeningRuntime: ListeningRuntime,
    val overlayRuntime: OverlayRuntime
)
