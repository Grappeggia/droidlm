package ai.droidlm.diagnostics

import ai.droidlm.BuildConfig

object DebugLogUploadEndpoint {
    @Volatile
    private var overrideUrlForTesting: String? = null

    fun url(): String = overrideUrlForTesting ?: BuildConfig.DEBUG_LOG_UPLOAD_URL.trim()

    fun isConfigured(): Boolean = url().isNotBlank()

    fun setOverrideForTesting(url: String?) {
        if (BuildConfig.DEBUG) {
            overrideUrlForTesting = url?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    fun clearOverrideForTesting() {
        if (BuildConfig.DEBUG) overrideUrlForTesting = null
    }
}
