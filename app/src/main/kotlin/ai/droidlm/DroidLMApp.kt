package ai.droidlm

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.cloud.MobilerunCloudClient
import ai.droidlm.context.DeviceContextAggregator
import ai.droidlm.context.GoogleDocsContextProvider
import ai.droidlm.context.GoogleDriveContextProvider
import ai.droidlm.context.GoogleSheetsContextProvider
import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.execution.DroidLmExecutor
import ai.droidlm.fileops.WorkspaceFileOperationController
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.ocr.MlKitOcrEngine
import ai.droidlm.openai.OpenAiClient
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.portal.AccessibilityPortalController
import ai.droidlm.portal.PortalController
import ai.droidlm.prompts.PromptHistoryRepository
import ai.droidlm.relay.RelayClient
import ai.droidlm.safety.SafetyClassifier
import ai.droidlm.settings.SettingsRepository
import ai.droidlm.textedit.TextEditingController
import ai.droidlm.voice.CommandRecorder
import ai.droidlm.voice.ManualWakeWordEngine
import ai.droidlm.voice.SpeechRecognitionController
import ai.droidlm.voice.VoskOfflineSpeechRecognizer
import android.app.Application
import android.content.Context

class DroidLMApp : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var actionLogRepository: ActionLogRepository
        private set
    lateinit var speechDiagnosticsLogger: SpeechDiagnosticsLogger
        private set
    lateinit var debugLogStore: DebugLogStore
        private set
    lateinit var promptHistoryRepository: PromptHistoryRepository
        private set
    lateinit var relayClient: RelayClient
        private set
    lateinit var openAiClient: OpenAiClient
        private set
    lateinit var portalController: PortalController
        private set
    lateinit var appInventoryRepository: AppInventoryRepository
        private set
    lateinit var deviceContextAggregator: DeviceContextAggregator
        private set
    lateinit var ocrEngine: OcrEngine
        private set
    lateinit var textEditingController: TextEditingController
        private set
    lateinit var safetyClassifier: SafetyClassifier
        private set
    lateinit var mobilerunCloudClient: MobilerunCloudClient
        private set
    lateinit var executor: DroidLmExecutor
        private set
    lateinit var commandRecorder: CommandRecorder
        private set
    lateinit var speechRecognitionController: SpeechRecognitionController
        private set
    lateinit var voskOfflineSpeechRecognizer: VoskOfflineSpeechRecognizer
        private set
    lateinit var workspaceFileOperationController: WorkspaceFileOperationController
        private set
    lateinit var manualWakeWordEngine: ManualWakeWordEngine
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        actionLogRepository = ActionLogRepository()
        promptHistoryRepository = PromptHistoryRepository(this)
        speechDiagnosticsLogger = SpeechDiagnosticsLogger(this, settingsRepository, actionLogRepository)
        debugLogStore = DebugLogStore(this, settingsRepository, actionLogRepository, speechDiagnosticsLogger)
        relayClient = RelayClient()
        openAiClient = OpenAiClient(debugLogStore = debugLogStore)
        portalController = AccessibilityPortalController(this, actionLogRepository)
        appInventoryRepository = AppInventoryRepository(this)
        ocrEngine = MlKitOcrEngine()
        textEditingController = TextEditingController(portalController, ocrEngine, relayClient, actionLogRepository, debugLogStore)
        deviceContextAggregator = DeviceContextAggregator(
            appInventoryRepository = appInventoryRepository,
            providers = listOf(
                GoogleDocsContextProvider(),
                GoogleSheetsContextProvider(),
                GoogleDriveContextProvider()
            )
        )
        workspaceFileOperationController = WorkspaceFileOperationController(this, textEditingController, actionLogRepository)
        safetyClassifier = SafetyClassifier()
        mobilerunCloudClient = MobilerunCloudClient(settingsRepository, actionLogRepository)
        executor = DroidLmExecutor(
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
            mobilerunCloudClient = mobilerunCloudClient
        )
        commandRecorder = CommandRecorder(this, settingsRepository, actionLogRepository, debugLogStore)
        voskOfflineSpeechRecognizer = VoskOfflineSpeechRecognizer(this, actionLogRepository, speechDiagnosticsLogger, debugLogStore)
        speechRecognitionController = SpeechRecognitionController(this, actionLogRepository, speechDiagnosticsLogger, voskOfflineSpeechRecognizer)
        manualWakeWordEngine = ManualWakeWordEngine()
    }

    companion object {
        fun from(context: Context): DroidLMApp = context.applicationContext as DroidLMApp
    }
}
