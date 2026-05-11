package ai.droidlm.overlay

import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.execution.DroidLmExecutor
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.portal.PortalController
import ai.droidlm.runtime.OverlayRuntime
import ai.droidlm.settings.SettingsRepository
import ai.droidlm.voice.SpeechRecognitionController

data class OverlayServiceDeps(
    val settingsRepository: SettingsRepository,
    val actionLogRepository: ActionLogRepository,
    val speechDiagnosticsLogger: SpeechDiagnosticsLogger,
    val portalController: PortalController,
    val executor: DroidLmExecutor,
    val speechRecognitionController: SpeechRecognitionController,
    val overlayRuntime: OverlayRuntime
)
