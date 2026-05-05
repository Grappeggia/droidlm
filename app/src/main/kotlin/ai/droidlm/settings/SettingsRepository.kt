package ai.droidlm.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("droidlm_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val relayBaseUrl = stringPreferencesKey("relay_base_url")
        val openAiApiKeyConfigured = booleanPreferencesKey("openai_api_key_configured")
        val openAiModel = stringPreferencesKey("openai_model")
        val wakePhrase = stringPreferencesKey("wake_phrase")
        val transcriptionProvider = stringPreferencesKey("transcription_provider")
        val preferOfflineSpeechRecognition = booleanPreferencesKey("prefer_offline_speech_recognition")
        val showPartialSpeechRecognition = booleanPreferencesKey("show_partial_speech_recognition")
        val floatingOverlayEnabled = booleanPreferencesKey("floating_overlay_enabled")
        val overlayX = intPreferencesKey("overlay_x")
        val overlayY = intPreferencesKey("overlay_y")
        val hideOverlayDuringAutomation = booleanPreferencesKey("hide_overlay_during_automation")
        val wakeWordProvider = stringPreferencesKey("wake_word_provider")
        val picovoiceAccessKeyConfigured = booleanPreferencesKey("picovoice_access_key_configured")
        val wakeWordModelAssetPath = stringPreferencesKey("wake_word_model_asset_path")
        val wakeSensitivity = floatPreferencesKey("wake_sensitivity")
        val executionMode = stringPreferencesKey("execution_mode")
        val autoAcceptSafePlans = booleanPreferencesKey("auto_accept_safe_plans")
        val mobilerunApiKeyConfigured = booleanPreferencesKey("mobilerun_api_key_configured")
        val mobilerunDeviceId = stringPreferencesKey("mobilerun_device_id")
        val mobilerunLlmModel = stringPreferencesKey("mobilerun_llm_model")
        val maxAutonomousSteps = intPreferencesKey("max_autonomous_steps")
        val requireRiskConfirmation = booleanPreferencesKey("require_risk_confirmation")
        val onDeviceOcrEnabled = booleanPreferencesKey("on_device_ocr_enabled")
        val cloudScreenshotAnalysisEnabled = booleanPreferencesKey("cloud_screenshot_analysis_enabled")
        val debugLoggingEnabled = booleanPreferencesKey("debug_logging_enabled")
        val legacyDebugScreenshotRetention = booleanPreferencesKey("debug_screenshot_retention")
        val legacyDebugAudioRetention = booleanPreferencesKey("debug_audio_retention")
        val legacySpeechDiagnosticsEnabled = booleanPreferencesKey("speech_diagnostics_enabled")
        val onboardingCompletedVersion = intPreferencesKey("onboarding_completed_version")
        val sensitiveAppScreenshotDenylist = stringPreferencesKey("sensitive_app_screenshot_denylist")
        val packageAllowlist = stringPreferencesKey("package_allowlist")
        val packageDenylist = stringPreferencesKey("package_denylist")
    }

    private val securePreferences: SharedPreferences by lazy { createSecurePreferences() }

    val settings: Flow<DroidLmSettings> = context.settingsDataStore.data.map { preferences ->
        DroidLmSettings(
            relayBaseUrl = preferences[Keys.relayBaseUrl].orEmpty(),
            openAiApiKeyConfigured = hasOpenAiApiKey(),
            openAiModel = preferences[Keys.openAiModel] ?: "gpt-5.4-nano",
            wakePhrase = preferences[Keys.wakePhrase] ?: "DroidLM",
            transcriptionProvider = androidSpeechTranscriptionProvider(preferences[Keys.transcriptionProvider]),
            preferOfflineSpeechRecognition = preferences[Keys.preferOfflineSpeechRecognition] ?: true,
            showPartialSpeechRecognition = preferences[Keys.showPartialSpeechRecognition] ?: true,
            floatingOverlayEnabled = preferences[Keys.floatingOverlayEnabled] ?: false,
            overlayX = preferences[Keys.overlayX] ?: 24,
            overlayY = preferences[Keys.overlayY] ?: 250,
            hideOverlayDuringAutomation = preferences[Keys.hideOverlayDuringAutomation] ?: true,
            wakeWordProvider = enumValueOrDefault(
                preferences[Keys.wakeWordProvider],
                WakeWordProvider.MANUAL_PUSH_TO_TALK
            ),
            picovoiceAccessKeyConfigured = preferences[Keys.picovoiceAccessKeyConfigured] ?: hasPicovoiceAccessKey(),
            wakeWordModelAssetPath = preferences[Keys.wakeWordModelAssetPath].orEmpty(),
            wakeSensitivity = preferences[Keys.wakeSensitivity] ?: 0.65f,
            executionMode = enumValueOrDefault(preferences[Keys.executionMode], ExecutionMode.LOCAL_RULE_FIRST),
            autoAcceptSafePlans = preferences[Keys.autoAcceptSafePlans] ?: false,
            mobilerunApiKeyConfigured = preferences[Keys.mobilerunApiKeyConfigured] ?: hasMobilerunApiKey(),
            mobilerunDeviceId = preferences[Keys.mobilerunDeviceId].orEmpty(),
            mobilerunLlmModel = preferences[Keys.mobilerunLlmModel].orEmpty(),
            maxAutonomousSteps = preferences[Keys.maxAutonomousSteps] ?: 12,
            requireRiskConfirmation = preferences[Keys.requireRiskConfirmation] ?: true,
            onDeviceOcrEnabled = preferences[Keys.onDeviceOcrEnabled] ?: true,
            cloudScreenshotAnalysisEnabled = preferences[Keys.cloudScreenshotAnalysisEnabled] ?: false,
            debugLoggingEnabled = preferences[Keys.debugLoggingEnabled]
                ?: (preferences[Keys.legacyDebugScreenshotRetention] == true ||
                    preferences[Keys.legacyDebugAudioRetention] == true ||
                    preferences[Keys.legacySpeechDiagnosticsEnabled] == true),
            onboardingCompletedVersion = preferences[Keys.onboardingCompletedVersion] ?: 0,
            sensitiveAppScreenshotDenylist = preferences[Keys.sensitiveAppScreenshotDenylist]
                ?: DroidLmSettings.DEFAULT_SENSITIVE_DENYLIST,
            packageAllowlist = preferences[Keys.packageAllowlist].orEmpty(),
            packageDenylist = preferences[Keys.packageDenylist].orEmpty()
        )
    }

    suspend fun updateRelayBaseUrl(value: String) = editString(Keys.relayBaseUrl, value.trim())
    suspend fun updateOpenAiModel(value: String) = editString(Keys.openAiModel, value.trim().ifBlank { "gpt-5.4-nano" })
    suspend fun updateWakePhrase(value: String) = editString(Keys.wakePhrase, value.ifBlank { "DroidLM" })
    suspend fun updateTranscriptionProvider(value: TranscriptionProvider) = editString(Keys.transcriptionProvider, value.name)
    suspend fun updatePreferOfflineSpeechRecognition(value: Boolean) = editBoolean(Keys.preferOfflineSpeechRecognition, value)
    suspend fun updateShowPartialSpeechRecognition(value: Boolean) = editBoolean(Keys.showPartialSpeechRecognition, value)
    suspend fun updateFloatingOverlayEnabled(value: Boolean) = editBoolean(Keys.floatingOverlayEnabled, value)
    suspend fun updateHideOverlayDuringAutomation(value: Boolean) = editBoolean(Keys.hideOverlayDuringAutomation, value)
    suspend fun updateOverlayPosition(x: Int, y: Int) {
        context.settingsDataStore.edit {
            it[Keys.overlayX] = x.coerceAtLeast(0)
            it[Keys.overlayY] = y.coerceAtLeast(0)
        }
    }
    suspend fun updateWakeWordProvider(value: WakeWordProvider) = editString(Keys.wakeWordProvider, value.name)
    suspend fun updateWakeWordModelAssetPath(value: String) = editString(Keys.wakeWordModelAssetPath, value.trim())
    suspend fun updateWakeSensitivity(value: Float) = context.settingsDataStore.edit { it[Keys.wakeSensitivity] = value.coerceIn(0f, 1f) }
    suspend fun updateExecutionMode(value: ExecutionMode) = editString(Keys.executionMode, value.name)
    suspend fun updateAutoAcceptSafePlans(value: Boolean) = editBoolean(Keys.autoAcceptSafePlans, value)
    suspend fun updateMobilerunDeviceId(value: String) = editString(Keys.mobilerunDeviceId, value.trim())
    suspend fun updateMobilerunLlmModel(value: String) = editString(Keys.mobilerunLlmModel, value.trim())
    suspend fun updateMaxAutonomousSteps(value: Int) = context.settingsDataStore.edit { it[Keys.maxAutonomousSteps] = value.coerceIn(1, 40) }
    suspend fun updateRequireRiskConfirmation(value: Boolean) = editBoolean(Keys.requireRiskConfirmation, value)
    suspend fun updateOnDeviceOcrEnabled(value: Boolean) = editBoolean(Keys.onDeviceOcrEnabled, value)
    suspend fun updateCloudScreenshotAnalysisEnabled(value: Boolean) = editBoolean(Keys.cloudScreenshotAnalysisEnabled, value)
    suspend fun updateDebugLoggingEnabled(value: Boolean) = editBoolean(Keys.debugLoggingEnabled, value)
    suspend fun updateOnboardingCompletedVersion(value: Int) = context.settingsDataStore.edit {
        it[Keys.onboardingCompletedVersion] = value.coerceAtLeast(0)
    }
    suspend fun updateSensitiveAppScreenshotDenylist(value: String) = editString(Keys.sensitiveAppScreenshotDenylist, value)
    suspend fun updatePackageAllowlist(value: String) = editString(Keys.packageAllowlist, value)
    suspend fun updatePackageDenylist(value: String) = editString(Keys.packageDenylist, value)

    suspend fun saveOpenAiApiKey(value: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            clearOpenAiApiKey()
            return
        }
        securePreferences.edit().putString(OPENAI_API_KEY, trimmed).apply()
        editBoolean(Keys.openAiApiKeyConfigured, true)
    }

    suspend fun clearOpenAiApiKey() {
        securePreferences.edit().remove(OPENAI_API_KEY).apply()
        editBoolean(Keys.openAiApiKeyConfigured, false)
    }

    fun getOpenAiApiKey(): String? = securePreferences.getString(OPENAI_API_KEY, null)
    fun hasOpenAiApiKey(): Boolean = !getOpenAiApiKey().isNullOrBlank()

    suspend fun savePicovoiceAccessKey(value: String) {
        securePreferences.edit().putString(PICOVOICE_ACCESS_KEY, value).apply()
        editBoolean(Keys.picovoiceAccessKeyConfigured, value.isNotBlank())
    }

    fun getPicovoiceAccessKey(): String? = securePreferences.getString(PICOVOICE_ACCESS_KEY, null)
    fun hasPicovoiceAccessKey(): Boolean = !getPicovoiceAccessKey().isNullOrBlank()

    suspend fun saveMobilerunApiKey(value: String) {
        securePreferences.edit().putString(MOBILERUN_API_KEY, value).apply()
        editBoolean(Keys.mobilerunApiKeyConfigured, value.isNotBlank())
    }

    fun getMobilerunApiKey(): String? = securePreferences.getString(MOBILERUN_API_KEY, null)
    fun hasMobilerunApiKey(): Boolean = !getMobilerunApiKey().isNullOrBlank()

    private suspend fun editString(key: Preferences.Key<String>, value: String) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private suspend fun editBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private fun createSecurePreferences(): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "secure_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.getSharedPreferences("secure_settings_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun androidSpeechTranscriptionProvider(value: String?): TranscriptionProvider {
        return when (enumValueOrDefault(value, TranscriptionProvider.ANDROID_SPEECH_RECOGNIZER)) {
            TranscriptionProvider.ANDROID_SPEECH_RECOGNIZER -> TranscriptionProvider.ANDROID_SPEECH_RECOGNIZER
            TranscriptionProvider.OPENAI_DIRECT -> TranscriptionProvider.ANDROID_SPEECH_RECOGNIZER
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T {
        return value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
    }

    companion object {
        private const val OPENAI_API_KEY = "openai_api_key"
        private const val PICOVOICE_ACCESS_KEY = "picovoice_access_key"
        private const val MOBILERUN_API_KEY = "mobilerun_api_key"
    }
}
