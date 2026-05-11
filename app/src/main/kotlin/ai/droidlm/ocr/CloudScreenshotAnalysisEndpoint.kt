package ai.droidlm.ocr

import ai.droidlm.BuildConfig

object CloudScreenshotAnalysisEndpoint {
    @Volatile
    private var overrideUrlForTesting: String? = null

    fun url(): String = overrideUrlForTesting ?: BuildConfig.CLOUD_SCREENSHOT_ANALYSIS_URL.trim()

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
