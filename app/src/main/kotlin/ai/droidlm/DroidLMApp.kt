package ai.droidlm

import ai.droidlm.cloud.MobilerunCloudClient
import ai.droidlm.execution.DroidLmExecutor
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.ocr.MlKitOcrEngine
import ai.droidlm.ocr.OcrEngine
import ai.droidlm.portal.AccessibilityPortalController
import ai.droidlm.portal.PortalController
import ai.droidlm.relay.RelayClient
import ai.droidlm.safety.SafetyClassifier
import ai.droidlm.settings.SettingsRepository
import ai.droidlm.textedit.TextEditingController
import ai.droidlm.voice.CommandRecorder
import ai.droidlm.voice.ManualWakeWordEngine
import ai.droidlm.voice.SpeechRecognitionController
import android.app.Application
import android.content.Context

class DroidLMApp : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var actionLogRepository: ActionLogRepository
        private set
    lateinit var relayClient: RelayClient
        private set
    lateinit var portalController: PortalController
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
    lateinit var manualWakeWordEngine: ManualWakeWordEngine
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        actionLogRepository = ActionLogRepository()
        relayClient = RelayClient()
        portalController = AccessibilityPortalController(this, actionLogRepository)
        ocrEngine = MlKitOcrEngine()
        textEditingController = TextEditingController(portalController, ocrEngine, relayClient, actionLogRepository)
        safetyClassifier = SafetyClassifier()
        mobilerunCloudClient = MobilerunCloudClient(settingsRepository, actionLogRepository)
        executor = DroidLmExecutor(
            settingsRepository = settingsRepository,
            relayClient = relayClient,
            portalController = portalController,
            textEditingController = textEditingController,
            ocrEngine = ocrEngine,
            logs = actionLogRepository,
            safetyClassifier = safetyClassifier,
            mobilerunCloudClient = mobilerunCloudClient
        )
        commandRecorder = CommandRecorder(this, settingsRepository, actionLogRepository)
        speechRecognitionController = SpeechRecognitionController(this, actionLogRepository)
        manualWakeWordEngine = ManualWakeWordEngine()
    }

    companion object {
        fun from(context: Context): DroidLMApp = context.applicationContext as DroidLMApp
    }
}
