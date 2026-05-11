package ai.droidlm.ocr

import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.relay.DeviceContext
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.relay.RelayClient
import ai.droidlm.relay.VisionAnalysis
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

class RelayCloudScreenshotAnalyzer(
    private val context: Context,
    private val relayClient: RelayClient,
    private val endpointProvider: () -> String,
    private val debugLogStore: DebugLogStore? = null
) : CloudScreenshotAnalyzer {
    override fun isConfigured(): Boolean = endpointProvider().isNotBlank()

    override suspend fun analyze(bitmap: Bitmap, goal: String, deviceContext: DeviceContext?): VisionAnalysis {
        val file = File.createTempFile("droidlm-screen-", ".png", context.cacheDir)
        val compressed = FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        debugLogStore?.recordEvent(
            "cloud_screenshot_analysis_file_prepared",
            mapOf(
                "fileName" to file.name,
                "bytes" to file.length(),
                "compressed" to compressed,
                "goalLength" to goal.length,
                "hasDeviceContext" to (deviceContext != null)
            )
        )
        debugLogStore?.retainFile(file, "screenshots", file.name)
        return try {
            when (val result = relayClient.analyzeScreenshot(endpointProvider(), file, goal, deviceContext = deviceContext)) {
                is RelayCallResult.Success -> result.value
                is RelayCallResult.Failure -> throw IllegalStateException(result.message, result.cause)
            }
        } finally {
            val deleted = file.delete()
            debugLogStore?.recordEvent(
                "cloud_screenshot_analysis_temp_deleted",
                mapOf("fileName" to file.name, "deleted" to deleted)
            )
        }
    }
}
