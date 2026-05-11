package ai.droidlm.ocr

import ai.droidlm.relay.DeviceContext
import ai.droidlm.relay.VisionAnalysis
import android.graphics.Bitmap

interface CloudScreenshotAnalyzer {
    fun isConfigured(): Boolean

    suspend fun analyze(
        bitmap: Bitmap,
        goal: String,
        deviceContext: DeviceContext? = null
    ): VisionAnalysis
}
