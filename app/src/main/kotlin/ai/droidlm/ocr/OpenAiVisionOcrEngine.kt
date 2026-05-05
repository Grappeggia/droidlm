package ai.droidlm.ocr

import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.relay.RelayClient
import ai.droidlm.relay.DeviceContext
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

class OpenAiVisionOcrEngine(
    private val context: Context,
    private val relayClient: RelayClient,
    private val baseUrlProvider: suspend () -> String,
    private val goalProvider: () -> String = { "Extract visible text and useful tap coordinates" },
    private val debugLogStore: DebugLogStore? = null
) : OcrEngine {
    override suspend fun recognize(bitmap: Bitmap, deviceContext: DeviceContext?): OcrResult {
        debugLogStore?.recordEvent(
            "cloud_vision_ocr_started",
            mapOf("width" to bitmap.width, "height" to bitmap.height, "hasDeviceContext" to (deviceContext != null), "activePackage" to deviceContext?.activeApp?.packageName)
        )
        val file = File.createTempFile("droidlm-screen-", ".png", context.cacheDir)
        val compressed = FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        debugLogStore?.recordEvent("cloud_vision_screenshot_prepared", mapOf("fileName" to file.name, "bytes" to file.length(), "compressed" to compressed))
        debugLogStore?.retainFile(file, "screenshots", file.name)
        return try {
            val baseUrl = baseUrlProvider()
            debugLogStore?.recordEvent("cloud_vision_request_started", mapOf("baseUrlConfigured" to baseUrl.isNotBlank(), "goalLength" to goalProvider().length))
            when (val result = relayClient.analyzeScreenshot(baseUrl, file, goalProvider(), deviceContext = deviceContext)) {
                is RelayCallResult.Success -> {
                    debugLogStore?.recordEvent(
                        "cloud_vision_request_succeeded",
                        mapOf("lineCount" to result.value.lines.size, "elementCount" to result.value.elements.size, "fullTextLength" to result.value.fullText.length)
                    )
                    OcrResult(
                        fullText = result.value.fullText,
                        blocks = emptyList(),
                        lines = result.value.lines,
                        elements = result.value.elements,
                        symbols = emptyList(),
                        source = OcrSource.OPENAI_VISION_RELAY
                    )
                }
                is RelayCallResult.Failure -> {
                    debugLogStore?.recordEvent("cloud_vision_request_failed", mapOf("message" to result.message, "errorCode" to result.errorCode))
                    throw IllegalStateException(result.message, result.cause)
                }
            }
        } finally {
            val deleted = file.delete()
            debugLogStore?.recordEvent("cloud_vision_temp_file_deleted", mapOf("fileName" to file.name, "deleted" to deleted))
        }
    }
}
