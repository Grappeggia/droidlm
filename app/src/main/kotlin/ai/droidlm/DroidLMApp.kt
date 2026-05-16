package ai.droidlm

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.cloud.MobilerunCloudClient
import ai.droidlm.context.DeviceContextAggregator
import ai.droidlm.di.AppGraph
import ai.droidlm.di.AppGraphProvider
import ai.droidlm.di.RealAppGraph
import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.execution.DroidLmExecutor
import ai.droidlm.fileops.WorkspaceFileOperationController
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.openai.OpenAiClient
import ai.droidlm.portal.PortalController
import ai.droidlm.prompts.PromptHistoryRepository
import ai.droidlm.relay.RelayClient
import ai.droidlm.runtime.AccessibilityRuntime
import ai.droidlm.runtime.ListeningRuntime
import ai.droidlm.runtime.OverlayRuntime
import ai.droidlm.safety.SafetyClassifier
import ai.droidlm.settings.SettingsRepository
import ai.droidlm.textedit.TextEditingController
import ai.droidlm.update.DebugBuildUpdater
import ai.droidlm.voice.CommandRecorder
import ai.droidlm.voice.ManualWakeWordEngine
import ai.droidlm.voice.OfflineSpeechRecognizer
import ai.droidlm.voice.SpeechRecognitionController
import ai.droidlm.voice.VoskOfflineSpeechRecognizer
import android.app.Application

class DroidLMApp : Application(), AppGraphProvider {

    override lateinit var graph: AppGraph
        private set

    val settingsRepository: SettingsRepository
        get() = graph.settingsRepository
    val actionLogRepository: ActionLogRepository
        get() = graph.actionLogRepository
    val speechDiagnosticsLogger: SpeechDiagnosticsLogger
        get() = graph.speechDiagnosticsLogger
    val debugLogStore: DebugLogStore
        get() = graph.debugLogStore
    val promptHistoryRepository: PromptHistoryRepository
        get() = graph.promptHistoryRepository
    val relayClient: RelayClient
        get() = graph.relayClient
    val openAiClient: OpenAiClient
        get() = graph.openAiClient
    val portalController: PortalController
        get() = graph.portalController
    val appInventoryRepository: AppInventoryRepository
        get() = graph.appInventoryRepository
    val deviceContextAggregator: DeviceContextAggregator
        get() = graph.deviceContextAggregator
    val ocrEngine: OcrEngine
        get() = graph.ocrEngine
    val textEditingController: TextEditingController
        get() = graph.textEditingController
    val workspaceFileOperationController: WorkspaceFileOperationController
        get() = graph.workspaceFileOperationController
    val safetyClassifier: SafetyClassifier
        get() = graph.safetyClassifier
    val mobilerunCloudClient: MobilerunCloudClient
        get() = graph.mobilerunCloudClient
    val executor: DroidLmExecutor
        get() = graph.executor
    val commandRecorder: CommandRecorder
        get() = graph.commandRecorder
    val speechRecognitionController: SpeechRecognitionController
        get() = graph.speechRecognitionController
    val offlineSpeechRecognizer: OfflineSpeechRecognizer
        get() = graph.offlineSpeechRecognizer
    val voskOfflineSpeechRecognizer: VoskOfflineSpeechRecognizer
        get() = graph.voskOfflineSpeechRecognizer
    val manualWakeWordEngine: ManualWakeWordEngine
        get() = graph.manualWakeWordEngine
    val debugBuildUpdater: DebugBuildUpdater
        get() = graph.debugBuildUpdater
    val accessibilityRuntime: AccessibilityRuntime
        get() = graph.accessibilityRuntime
    val overlayRuntime: OverlayRuntime
        get() = graph.overlayRuntime
    val listeningRuntime: ListeningRuntime
        get() = graph.listeningRuntime

    override fun onCreate() {
        super.onCreate()
        rebuildGraphForTesting()
        speechDiagnosticsLogger.record(null, "app_created", mapOf("packageName" to packageName))
    }

    fun rebuildGraphForTesting(forceVoskOfflineSpeech: Boolean = false) {
        graph = RealAppGraph(this, forceVoskOfflineSpeech = forceVoskOfflineSpeech)
    }

    override fun onTerminate() {
        super.onTerminate()
    }
}
