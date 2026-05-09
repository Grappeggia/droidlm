package ai.droidlm.ui

import ai.droidlm.DroidLMApp
import ai.droidlm.logs.ActionLogType
import ai.droidlm.overlay.FloatingControlOverlayService
import ai.droidlm.settings.TranscriptionProvider
import ai.droidlm.settings.WakeWordProvider
import ai.droidlm.voice.WakeWordForegroundService
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DroidLmViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DroidLMApp
    val settings = app.settingsRepository.settings
    val logs = app.actionLogRepository.logs
    val executionState = app.executor.uiState
    val pendingConfirmation = app.executor.pendingConfirmation
    val listeningState = WakeWordForegroundService.isRunningState
    val speechRecognitionState = app.speechRecognitionController.state
    val pendingPlan = app.executor.pendingPlan
    val plannerKeySetupRequest = app.executor.plannerKeySetupRequest
    val overlayState = FloatingControlOverlayService.isRunningState

    private val _overlayPermissionGranted = MutableStateFlow(Settings.canDrawOverlays(app))
    val overlayPermissionGranted: StateFlow<Boolean> = _overlayPermissionGranted.asStateFlow()


    private val _accessibilityEnabled = MutableStateFlow(false)
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    fun refreshAccessibility() {
        viewModelScope.launch { _accessibilityEnabled.value = app.portalController.isAccessibilityEnabled() }
    }


    fun refreshOverlayPermission(): Boolean {
        val granted = Settings.canDrawOverlays(app)
        _overlayPermissionGranted.value = granted
        return granted
    }

    fun startListening() {
        ContextCompat.startForegroundService(
            app,
            WakeWordForegroundService.intent(app, WakeWordForegroundService.ACTION_START_LISTENING)
        )
    }

    fun stopListening() {
        app.startService(WakeWordForegroundService.intent(app, WakeWordForegroundService.ACTION_STOP_LISTENING))
    }

    fun pushToTalk() {
        val sessionId = app.speechDiagnosticsLogger.startSession("main_push_to_talk")
        ContextCompat.startForegroundService(
            app,
            WakeWordForegroundService.intent(app, WakeWordForegroundService.ACTION_PUSH_TO_TALK, sessionId)
        )
    }

    fun startOverlay() {
        app.startService(FloatingControlOverlayService.intent(app, FloatingControlOverlayService.ACTION_SHOW))
    }

    fun stopOverlay() {
        app.startService(FloatingControlOverlayService.intent(app, FloatingControlOverlayService.ACTION_STOP))
    }

    fun acceptPendingPlan(alwaysAcceptSafePlans: Boolean) {
        viewModelScope.launch { app.executor.acceptPendingPlan(alwaysAcceptSafePlans) }
    }

    fun rejectPendingPlan() {
        app.executor.rejectPendingPlan()
    }

    fun dismissPlannerKeySetup() {
        app.executor.clearPlannerKeySetupRequest()
    }

    fun saveOpenAiApiKey(apiKey: String) {
        viewModelScope.launch {
            app.settingsRepository.saveOpenAiApiKey(apiKey)
            app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "OpenAI API key saved on this device")
            app.executor.retryPlannerKeySetupRequest()
        }
    }

    fun clearOpenAiApiKey() {
        viewModelScope.launch {
            app.settingsRepository.clearOpenAiApiKey()
            app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "OpenAI API key cleared from this device")
        }
    }

    fun cancelCurrentTask() {
        app.executor.cancelActive()
        app.startService(WakeWordForegroundService.intent(app, WakeWordForegroundService.ACTION_CANCEL))
    }

    fun executeTextCommand(command: String) {
        viewModelScope.launch { app.executor.executeTranscript(command) }
    }


    fun testOcr() {
        viewModelScope.launch {
            val screenshot = app.portalController.takeScreenshot()
            if (!screenshot.success || screenshot.bitmap == null) {
                app.actionLogRepository.log(ActionLogType.ERROR, "OCR test failed: ${screenshot.message}", screenshot.errorCode)
                return@launch
            }
            app.debugLogStore.retainScreenshot(screenshot.bitmap, "ocr-test")
            runCatching { app.ocrEngine.recognize(screenshot.bitmap) }
                .onSuccess { app.actionLogRepository.log(ActionLogType.OCR_RESULT, "OCR test detected ${it.lines.size} lines") }
                .onFailure { app.actionLogRepository.log(ActionLogType.ERROR, "OCR test failed: ${it.message}") }
        }
    }

    fun confirmPending() = app.executor.respondToConfirmation(true)
    fun rejectPending() = app.executor.respondToConfirmation(false)

    fun updateOpenAiModel(value: String) = viewModelScope.launch { app.settingsRepository.updateOpenAiModel(value) }
    fun updateWakeWordProvider(provider: WakeWordProvider) = viewModelScope.launch { app.settingsRepository.updateWakeWordProvider(provider) }
    fun updateTranscriptionProvider(provider: TranscriptionProvider) = viewModelScope.launch { app.settingsRepository.updateTranscriptionProvider(provider) }
    fun updatePreferOfflineSpeech(value: Boolean) = viewModelScope.launch { app.settingsRepository.updatePreferOfflineSpeechRecognition(value) }
    fun updateShowPartialSpeech(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateShowPartialSpeechRecognition(value) }
    fun updateRiskConfirmation(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateRequireRiskConfirmation(value) }
    fun updateOnDeviceOcr(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateOnDeviceOcrEnabled(value) }
    fun updateCloudVision(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateCloudScreenshotAnalysisEnabled(value) }
    fun updateDebugLogging(value: Boolean) = viewModelScope.launch {
        app.settingsRepository.updateDebugLoggingEnabled(value)
        app.speechDiagnosticsLogger.setEnabled(value)
        if (value) {
            app.debugLogStore.recordEvent("setting_enabled", mapOf("source" to "settings_ui"))
            app.actionLogRepository.log(
                ActionLogType.ACTION_RESULT,
                "Debug logging enabled",
                "Exports may include spoken text, screenshots, retained audio, and speech-recognition state."
            )
        } else {
            app.speechDiagnosticsLogger.clear()
            app.debugLogStore.clear()
            app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Debug logging disabled and cleared")
        }
    }

    fun debugLogsExportFileName(): String = app.debugLogStore.exportFileName()

    fun saveDebugLogsToUri(uri: Uri) = viewModelScope.launch {
        app.debugLogStore.recordEvent("save_requested", mapOf("uriScheme" to uri.scheme))
        val file = withContext(Dispatchers.IO) { app.debugLogStore.createBundle() }
        if (file == null) {
            app.debugLogStore.recordEvent("save_empty")
            app.actionLogRepository.log(ActionLogType.ERROR, "No debug logs to save")
            return@launch
        }
        runCatching {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not open destination file")
            }
        }.onSuccess {
            app.debugLogStore.recordEvent("save_succeeded", mapOf("zipName" to file.name, "zipBytes" to file.length()))
            app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Saved debug logs")
        }.onFailure { error ->
            app.debugLogStore.recordEvent("save_failed", mapOf("message" to error.message, "errorClass" to error::class.java.name, "zipName" to file.name, "zipBytes" to file.length()))
            app.actionLogRepository.log(ActionLogType.ERROR, "Could not save debug logs: ${error.message}")
        }
    }

    fun shareDebugLogs(issueDescription: String) = viewModelScope.launch {
        app.debugLogStore.recordEvent("share_requested", mapOf("issueDescriptionLength" to issueDescription.trim().length))
        val file = withContext(Dispatchers.IO) { app.debugLogStore.createBundle(issueDescription) }
        if (file == null) {
            app.debugLogStore.recordEvent("share_empty")
            app.actionLogRepository.log(ActionLogType.ERROR, "No debug logs to share")
            return@launch
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, "DroidLM debug logs")
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "DroidLM debug logs attached.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Share debug logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(chooser) }
            .onSuccess {
                app.debugLogStore.recordEvent("share_sheet_opened", mapOf("zipName" to file.name, "zipBytes" to file.length()))
                app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Opened debug logs share sheet")
            }
            .onFailure { error ->
                app.debugLogStore.recordEvent("share_failed", mapOf("message" to error.message, "errorClass" to error::class.java.name, "zipName" to file.name, "zipBytes" to file.length()))
                app.actionLogRepository.log(ActionLogType.ERROR, "Could not share debug logs: ${error.message}")
            }
    }

    fun clearDebugLogs() {
        app.debugLogStore.recordEvent("clear_requested", mapOf("source" to "settings_ui"))
        app.speechDiagnosticsLogger.clear()
        viewModelScope.launch { app.debugLogStore.clear() }
    }

    fun completeOnboarding() = viewModelScope.launch {
        app.settingsRepository.updateOnboardingCompletedVersion(ONBOARDING_VERSION)
        app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Onboarding completed")
    }

    fun checkSpeechSetup(preferOffline: Boolean) {
        app.speechRecognitionController.checkSpeechSetup(preferOffline)
    }

    fun openSpeechRecognitionSettings() {
        val options = listOf(
            "voice_input_settings" to Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            "input_method_settings" to Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
            "app_settings" to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
        )
        val target = options.firstOrNull { (_, intent) -> canOpen(intent) }
        if (target == null) {
            app.actionLogRepository.log(ActionLogType.ERROR, "Could not open Android speech settings on this device")
            return
        }
        openSettingsIntent(target.first, target.second)
    }

    fun openRecognizerAppSettings() {
        val packageName = voiceRecognitionServicePackageName()
        if (packageName == null) {
            app.actionLogRepository.log(ActionLogType.ERROR, "No Android speech recognizer app was reported by the device")
            openSpeechRecognitionSettings()
            return
        }
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        if (!canOpen(intent)) {
            app.actionLogRepository.log(ActionLogType.ERROR, "Could not open settings for speech recognizer app $packageName")
            openSpeechRecognitionSettings()
            return
        }
        openSettingsIntent("recognizer_app_settings", intent, mapOf("recognizerPackage" to packageName))
    }

    private fun openSettingsIntent(label: String, intent: Intent, fields: Map<String, Any?> = emptyMap()) {
        val launchIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.speechDiagnosticsLogger.record(
            null,
            "speech_settings_opened",
            mapOf("target" to label, "action" to launchIntent.action, "data" to launchIntent.dataString) + fields
        )
        runCatching { app.startActivity(launchIntent) }
            .onSuccess { app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Opened Android speech settings") }
            .onFailure { error -> app.actionLogRepository.log(ActionLogType.ERROR, "Could not open Android speech settings: ${error.message}") }
    }

    private fun canOpen(intent: Intent): Boolean = intent.resolveActivity(app.packageManager) != null

    private fun voiceRecognitionServicePackageName(): String? {
        val component = Settings.Secure.getString(app.contentResolver, "voice_recognition_service").orEmpty()
        return ComponentName.unflattenFromString(component)?.packageName
            ?: component.substringBefore('/').takeIf { it.isNotBlank() }
    }
    fun updateMobilerunDeviceId(value: String) = viewModelScope.launch { app.settingsRepository.updateMobilerunDeviceId(value) }
    fun saveMobilerunApiKey(value: String) = viewModelScope.launch { app.settingsRepository.saveMobilerunApiKey(value) }
    fun savePicovoiceAccessKey(value: String) = viewModelScope.launch { app.settingsRepository.savePicovoiceAccessKey(value) }

    companion object {
        const val ONBOARDING_VERSION = 1
    }

}
