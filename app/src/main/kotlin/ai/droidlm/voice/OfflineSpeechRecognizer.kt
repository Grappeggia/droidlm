package ai.droidlm.voice

import java.util.Locale

interface OfflineSpeechRecognizer {
    val providerLabel: String

    fun supportsLanguage(languageTag: String): Boolean

    suspend fun preloadModel(
        languageTag: String = Locale.getDefault().toLanguageTag(),
        source: String = "app_start"
    ): Boolean

    suspend fun recognizeCommand(
        languageTag: String,
        maxDurationMs: Long,
        diagnosticSessionId: String?,
        callbacks: VoskOfflineSpeechRecognizer.Callbacks = VoskOfflineSpeechRecognizer.Callbacks()
    ): String

    fun stopCurrent(): Boolean

    fun cancelCurrent(): Boolean
}
