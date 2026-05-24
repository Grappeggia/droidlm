package ai.droidlm.di

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.auth.AuthRepository
import ai.droidlm.auth.AllowlistRepository
import ai.droidlm.cloud.MobilerunCloudClient
import ai.droidlm.context.DeviceContextAggregator
import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.execution.DroidLmExecutor
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.ondevice.OnDevicePlanner
import ai.droidlm.openai.OpenAiClient
import ai.droidlm.overlay.OverlayServiceDeps
import ai.droidlm.permissions.RecordingPermissionDeps
import ai.droidlm.portal.AccessibilityServiceDeps
import ai.droidlm.portal.PortalController
import ai.droidlm.prompts.PromptHistoryRepository
import ai.droidlm.relay.RelayClient
import ai.droidlm.runtime.AccessibilityRuntime
import ai.droidlm.runtime.ListeningRuntime
import ai.droidlm.runtime.OverlayRuntime
import ai.droidlm.safety.SafetyClassifier
import ai.droidlm.settings.SettingsRepository
import ai.droidlm.textedit.TextEditingController
import ai.droidlm.ui.DroidLmViewModelDeps
import ai.droidlm.ui.MainActivityDeps
import ai.droidlm.update.DebugBuildUpdater
import ai.droidlm.fileops.WorkspaceFileOperationController
import ai.droidlm.voice.CommandRecorder
import ai.droidlm.voice.ManualWakeWordEngine
import ai.droidlm.voice.OfflineSpeechRecognizer
import ai.droidlm.voice.SpeechRecognitionController
import ai.droidlm.voice.VoskOfflineSpeechRecognizer
import ai.droidlm.voice.WakeWordForegroundServiceDeps
import android.content.Context

interface AppGraph {
    val settingsRepository: SettingsRepository
    val authRepository: AuthRepository
    val allowlistRepository: AllowlistRepository
    val actionLogRepository: ActionLogRepository
    val speechDiagnosticsLogger: SpeechDiagnosticsLogger
    val debugLogStore: DebugLogStore
    val promptHistoryRepository: PromptHistoryRepository
    val relayClient: RelayClient
    val openAiClient: OpenAiClient
    val onDevicePlanner: OnDevicePlanner
    val portalController: PortalController
    val appInventoryRepository: AppInventoryRepository
    val deviceContextAggregator: DeviceContextAggregator
    val ocrEngine: OcrEngine
    val textEditingController: TextEditingController
    val workspaceFileOperationController: WorkspaceFileOperationController
    val safetyClassifier: SafetyClassifier
    val mobilerunCloudClient: MobilerunCloudClient
    val executor: DroidLmExecutor
    val commandRecorder: CommandRecorder
    val speechRecognitionController: SpeechRecognitionController
    val offlineSpeechRecognizer: OfflineSpeechRecognizer
    val voskOfflineSpeechRecognizer: VoskOfflineSpeechRecognizer
    val manualWakeWordEngine: ManualWakeWordEngine
    val debugBuildUpdater: DebugBuildUpdater
    val accessibilityRuntime: AccessibilityRuntime
    val overlayRuntime: OverlayRuntime
    val listeningRuntime: ListeningRuntime

    fun mainActivityDeps(): MainActivityDeps
    fun droidLmViewModelDeps(): DroidLmViewModelDeps
    fun wakeWordForegroundServiceDeps(): WakeWordForegroundServiceDeps
    fun overlayServiceDeps(): OverlayServiceDeps
    fun recordingPermissionDeps(): RecordingPermissionDeps
    fun accessibilityServiceDeps(): AccessibilityServiceDeps
}

interface AppGraphProvider {
    val graph: AppGraph
}

fun Context.appGraph(): AppGraph = (applicationContext as AppGraphProvider).graph
