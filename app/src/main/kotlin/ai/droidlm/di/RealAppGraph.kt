package ai.droidlm.di

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.cloud.MobilerunCloudClient
import ai.droidlm.context.DeviceContextAggregator
import ai.droidlm.context.GoogleDocsContextProvider
import ai.droidlm.context.GoogleDriveContextProvider
import ai.droidlm.context.GoogleSheetsContextProvider
import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.diagnostics.NetworkDiagnostics
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.execution.DroidLmExecutor
import ai.droidlm.fileops.WorkspaceFileOperationController
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.ocr.CloudScreenshotAnalysisEndpoint
import ai.droidlm.ocr.MlKitOcrEngine
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.ocr.RelayCloudScreenshotAnalyzer
import ai.droidlm.openai.OpenAiClient
import ai.droidlm.overlay.OverlayServiceDeps
import ai.droidlm.permissions.RecordingPermissionDeps
import ai.droidlm.portal.AccessibilityPortalController
import ai.droidlm.portal.AccessibilityServiceDeps
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalRuntimeOverrides
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
import ai.droidlm.voice.CommandRecorder
import ai.droidlm.voice.ManualWakeWordEngine
import ai.droidlm.voice.SpeechRecognitionController
import ai.droidlm.voice.VoskOfflineSpeechRecognizer
import ai.droidlm.voice.WakeWordForegroundServiceDeps
import android.app.Application

class RealAppGraph(
    private val application: Application
) : AppGraph {
    override val accessibilityRuntime = AccessibilityRuntime()
    override val overlayRuntime = OverlayRuntime()
    override val listeningRuntime = ListeningRuntime()

    override val settingsRepository = SettingsRepository(application)
    override val actionLogRepository = ActionLogRepository()
    override val promptHistoryRepository = PromptHistoryRepository(application)
    override val speechDiagnosticsLogger = SpeechDiagnosticsLogger(application, settingsRepository, actionLogRepository)
    override val debugLogStore = DebugLogStore(application, settingsRepository, actionLogRepository, speechDiagnosticsLogger)
    override val relayClient = RelayClient(diagnostics = speechDiagnosticsLogger)
    override val openAiClient = OpenAiClient(debugLogStore = debugLogStore, networkDiagnostics = NetworkDiagnostics(application))
    override val portalController: PortalController = PortalRuntimeOverrides.controller
        ?: AccessibilityPortalController(application, actionLogRepository, accessibilityRuntime)
    override val appInventoryRepository = AppInventoryRepository(application)
    override val ocrEngine: OcrEngine = MlKitOcrEngine()
    override val textEditingController = TextEditingController(portalController, ocrEngine, relayClient, actionLogRepository, debugLogStore)
    private val cloudScreenshotAnalyzer = RelayCloudScreenshotAnalyzer(
        context = application,
        relayClient = relayClient,
        endpointProvider = { CloudScreenshotAnalysisEndpoint.url() },
        debugLogStore = debugLogStore
    )
    override val deviceContextAggregator = DeviceContextAggregator(
        appInventoryRepository = appInventoryRepository,
        providers = listOf(
            GoogleDocsContextProvider(),
            GoogleSheetsContextProvider(),
            GoogleDriveContextProvider()
        ),
        diagnostics = speechDiagnosticsLogger
    )
    override val workspaceFileOperationController = WorkspaceFileOperationController(application, textEditingController, actionLogRepository)
    override val safetyClassifier = SafetyClassifier()
    override val mobilerunCloudClient = MobilerunCloudClient(settingsRepository, actionLogRepository, speechDiagnosticsLogger)
    override val debugBuildUpdater = DebugBuildUpdater(application)
    override val executor = DroidLmExecutor(
        settingsRepository = settingsRepository,
        openAiClient = openAiClient,
        portalController = portalController,
        textEditingController = textEditingController,
        workspaceFileOperationController = workspaceFileOperationController,
        ocrEngine = ocrEngine,
        appInventoryRepository = appInventoryRepository,
        deviceContextAggregator = deviceContextAggregator,
        logs = actionLogRepository,
        safetyClassifier = safetyClassifier,
        promptHistoryRepository = promptHistoryRepository,
        diagnostics = speechDiagnosticsLogger,
        debugLogStore = debugLogStore,
        cloudScreenshotAnalyzer = cloudScreenshotAnalyzer,
        mobilerunCloudClient = mobilerunCloudClient
    )
    override val commandRecorder = CommandRecorder(application, settingsRepository, actionLogRepository, debugLogStore)
    override val voskOfflineSpeechRecognizer = VoskOfflineSpeechRecognizer(application, actionLogRepository, speechDiagnosticsLogger, debugLogStore)
    override val speechRecognitionController = SpeechRecognitionController(application, actionLogRepository, speechDiagnosticsLogger, voskOfflineSpeechRecognizer)
    override val manualWakeWordEngine = ManualWakeWordEngine()

    override fun mainActivityDeps(): MainActivityDeps = MainActivityDeps(
        speechDiagnosticsLogger = speechDiagnosticsLogger,
        viewModelDeps = droidLmViewModelDeps()
    )

    override fun droidLmViewModelDeps(): DroidLmViewModelDeps = DroidLmViewModelDeps(
        settingsRepository = settingsRepository,
        actionLogRepository = actionLogRepository,
        speechDiagnosticsLogger = speechDiagnosticsLogger,
        debugLogStore = debugLogStore,
        relayClient = relayClient,
        portalController = portalController,
        ocrEngine = ocrEngine,
        executor = executor,
        speechRecognitionController = speechRecognitionController,
        debugBuildUpdater = debugBuildUpdater,
        overlayRuntime = overlayRuntime,
        listeningRuntime = listeningRuntime
    )

    override fun wakeWordForegroundServiceDeps(): WakeWordForegroundServiceDeps = WakeWordForegroundServiceDeps(
        settingsRepository = settingsRepository,
        actionLogRepository = actionLogRepository,
        speechDiagnosticsLogger = speechDiagnosticsLogger,
        executor = executor,
        commandRecorder = commandRecorder,
        speechRecognitionController = speechRecognitionController,
        listeningRuntime = listeningRuntime,
        overlayRuntime = overlayRuntime
    )

    override fun overlayServiceDeps(): OverlayServiceDeps = OverlayServiceDeps(
        settingsRepository = settingsRepository,
        actionLogRepository = actionLogRepository,
        speechDiagnosticsLogger = speechDiagnosticsLogger,
        portalController = portalController,
        executor = executor,
        speechRecognitionController = speechRecognitionController,
        overlayRuntime = overlayRuntime
    )

    override fun recordingPermissionDeps(): RecordingPermissionDeps = RecordingPermissionDeps(
        actionLogRepository = actionLogRepository,
        speechDiagnosticsLogger = speechDiagnosticsLogger
    )

    override fun accessibilityServiceDeps(): AccessibilityServiceDeps = AccessibilityServiceDeps(
        speechDiagnosticsLogger = speechDiagnosticsLogger,
        accessibilityRuntime = accessibilityRuntime
    )
}
