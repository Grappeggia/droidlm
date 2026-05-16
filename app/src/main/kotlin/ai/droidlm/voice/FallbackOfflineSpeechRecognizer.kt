package ai.droidlm.voice

import ai.droidlm.diagnostics.SpeechDiagnosticsLogger

class FallbackOfflineSpeechRecognizer(
    private val primary: OfflineSpeechRecognizer,
    private val fallback: OfflineSpeechRecognizer,
    private val diagnostics: SpeechDiagnosticsLogger
) : OfflineSpeechRecognizer {
    override val providerLabel: String = primary.providerLabel

    @Volatile private var active: OfflineSpeechRecognizer? = null

    override fun supportsLanguage(languageTag: String): Boolean =
        primary.supportsLanguage(languageTag) || fallback.supportsLanguage(languageTag)

    override suspend fun preloadModel(languageTag: String, source: String): Boolean {
        val primaryReady = if (primary.supportsLanguage(languageTag)) {
            primary.preloadModel(languageTag, source)
        } else {
            false
        }
        if (primaryReady) return true
        return fallback.supportsLanguage(languageTag) && fallback.preloadModel(languageTag, source)
    }

    override suspend fun recognizeCommand(
        languageTag: String,
        maxDurationMs: Long,
        diagnosticSessionId: String?,
        callbacks: VoskOfflineSpeechRecognizer.Callbacks
    ): String {
        if (!supportsLanguage(languageTag)) {
            throw IllegalStateException("Built-in offline speech currently supports English only.")
        }
        if (primary.supportsLanguage(languageTag)) {
            active = primary
            val primaryResult = runCatching {
                primary.recognizeCommand(languageTag, maxDurationMs, diagnosticSessionId, callbacks)
            }
            active = null
            primaryResult.onSuccess { return it }
            val error = primaryResult.exceptionOrNull()
            val cancelled = error?.message?.contains("cancelled", ignoreCase = true) == true
            diagnostics.record(
                diagnosticSessionId,
                if (cancelled) "offline_primary_cancelled" else "offline_primary_failed_falling_back",
                mapOf(
                    "primaryProvider" to primary.providerLabel,
                    "fallbackProvider" to fallback.providerLabel,
                    "errorClass" to error?.javaClass?.name,
                    "message" to error?.message
                )
            )
            if (cancelled || !fallback.supportsLanguage(languageTag)) {
                throw error ?: IllegalStateException("Offline speech failed.")
            }
        }
        active = fallback
        return try {
            fallback.recognizeCommand(languageTag, maxDurationMs, diagnosticSessionId, callbacks)
        } finally {
            active = null
        }
    }

    override fun stopCurrent(): Boolean = active?.stopCurrent() ?: false

    override fun cancelCurrent(): Boolean = active?.cancelCurrent() ?: false
}
