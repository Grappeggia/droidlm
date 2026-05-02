package ai.droidlm.ui

import ai.droidlm.DroidLMApp
import ai.droidlm.logs.ActionLogType
import ai.droidlm.overlay.FloatingControlOverlayService
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.settings.ExecutionMode
import ai.droidlm.settings.TranscriptionProvider
import ai.droidlm.settings.WakeWordProvider
import ai.droidlm.voice.WakeWordForegroundService
import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    private val _relayStatus = MutableStateFlow("Unknown")
    val relayStatus: StateFlow<String> = _relayStatus.asStateFlow()

    private val _accessibilityEnabled = MutableStateFlow(false)
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    fun refreshAccessibility() {
        viewModelScope.launch { _accessibilityEnabled.value = app.portalController.isAccessibilityEnabled() }
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
        ContextCompat.startForegroundService(
            app,
            WakeWordForegroundService.intent(app, WakeWordForegroundService.ACTION_PUSH_TO_TALK)
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

    fun saveOpenAiKeyToRelay(setupToken: String, apiKey: String) {
        viewModelScope.launch {
            val relayUrl = settings.first().relayBaseUrl
            when (val result = app.relayClient.saveOpenAiKey(relayUrl, setupToken, apiKey)) {
                is RelayCallResult.Success -> {
                    app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "OpenAI key saved on relay")
                    app.executor.retryPlannerKeySetupRequest()
                }
                is RelayCallResult.Failure -> {
                    app.actionLogRepository.log(ActionLogType.ERROR, "Could not save OpenAI key on relay: ${result.message}", result.errorCode)
                }
            }
        }
    }

    fun cancelCurrentTask() {
        app.executor.cancelActive()
        app.startService(WakeWordForegroundService.intent(app, WakeWordForegroundService.ACTION_CANCEL))
    }

    fun executeTextCommand(command: String) {
        viewModelScope.launch { app.executor.executeTranscript(command) }
    }

    fun testRelay() {
        viewModelScope.launch {
            _relayStatus.value = "Checking..."
            val relayUrl = settings.first().relayBaseUrl
            when (val result = app.relayClient.health(relayUrl)) {
                is RelayCallResult.Success -> _relayStatus.value = if (result.value) "Reachable" else "Unhealthy"
                is RelayCallResult.Failure -> _relayStatus.value = "Unreachable: ${result.message}"
            }
        }
    }

    fun testOcr() {
        viewModelScope.launch {
            val screenshot = app.portalController.takeScreenshot()
            if (!screenshot.success || screenshot.bitmap == null) {
                app.actionLogRepository.log(ActionLogType.ERROR, "OCR test failed: ${screenshot.message}", screenshot.errorCode)
                return@launch
            }
            runCatching { app.ocrEngine.recognize(screenshot.bitmap) }
                .onSuccess { app.actionLogRepository.log(ActionLogType.OCR_RESULT, "OCR test detected ${it.lines.size} lines") }
                .onFailure { app.actionLogRepository.log(ActionLogType.ERROR, "OCR test failed: ${it.message}") }
        }
    }

    fun confirmPending() = app.executor.respondToConfirmation(true)
    fun rejectPending() = app.executor.respondToConfirmation(false)
    fun updateAutoAcceptSafePlans(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateAutoAcceptSafePlans(value) }

    fun updateRelayBaseUrl(value: String) = viewModelScope.launch { app.settingsRepository.updateRelayBaseUrl(value) }
    fun updateExecutionMode(mode: ExecutionMode) = viewModelScope.launch { app.settingsRepository.updateExecutionMode(mode) }
    fun updateWakeWordProvider(provider: WakeWordProvider) = viewModelScope.launch { app.settingsRepository.updateWakeWordProvider(provider) }
    fun updateTranscriptionProvider(provider: TranscriptionProvider) = viewModelScope.launch { app.settingsRepository.updateTranscriptionProvider(provider) }
    fun updatePreferOfflineSpeech(value: Boolean) = viewModelScope.launch { app.settingsRepository.updatePreferOfflineSpeechRecognition(value) }
    fun updateShowPartialSpeech(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateShowPartialSpeechRecognition(value) }
    fun updateHideOverlayDuringAutomation(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateHideOverlayDuringAutomation(value) }
    fun updateMaxSteps(value: Int) = viewModelScope.launch { app.settingsRepository.updateMaxAutonomousSteps(value) }
    fun updateRiskConfirmation(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateRequireRiskConfirmation(value) }
    fun updateOnDeviceOcr(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateOnDeviceOcrEnabled(value) }
    fun updateCloudVision(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateCloudScreenshotAnalysisEnabled(value) }
    fun updateConfirmScreenshots(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateConfirmBeforeSendingScreenshots(value) }
    fun updateDebugAudio(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateDebugAudioRetention(value) }
    fun updateDebugScreenshots(value: Boolean) = viewModelScope.launch { app.settingsRepository.updateDebugScreenshotRetention(value) }
    fun updateMobilerunDeviceId(value: String) = viewModelScope.launch { app.settingsRepository.updateMobilerunDeviceId(value) }
    fun saveMobilerunApiKey(value: String) = viewModelScope.launch { app.settingsRepository.saveMobilerunApiKey(value) }
    fun savePicovoiceAccessKey(value: String) = viewModelScope.launch { app.settingsRepository.savePicovoiceAccessKey(value) }
}
