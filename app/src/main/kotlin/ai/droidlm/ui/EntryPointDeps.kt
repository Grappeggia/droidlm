package ai.droidlm.ui

import ai.droidlm.auth.AuthRepository
import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.execution.DroidLmExecutor
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.portal.PortalController
import ai.droidlm.relay.RelayClient
import ai.droidlm.runtime.ListeningRuntime
import ai.droidlm.runtime.OverlayRuntime
import ai.droidlm.settings.SettingsRepository
import ai.droidlm.update.DebugBuildUpdater
import ai.droidlm.voice.SpeechRecognitionController

data class MainActivityDeps(
    val speechDiagnosticsLogger: SpeechDiagnosticsLogger,
    val viewModelDeps: DroidLmViewModelDeps
)

data class DroidLmViewModelDeps(
    val settingsRepository: SettingsRepository,
    val authRepository: AuthRepository,
    val actionLogRepository: ActionLogRepository,
    val speechDiagnosticsLogger: SpeechDiagnosticsLogger,
    val debugLogStore: DebugLogStore,
    val relayClient: RelayClient,
    val portalController: PortalController,
    val ocrEngine: OcrEngine,
    val executor: DroidLmExecutor,
    val speechRecognitionController: SpeechRecognitionController,
    val debugBuildUpdater: DebugBuildUpdater,
    val overlayRuntime: OverlayRuntime,
    val listeningRuntime: ListeningRuntime
)
