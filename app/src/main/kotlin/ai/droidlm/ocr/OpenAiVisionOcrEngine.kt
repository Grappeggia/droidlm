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
        val file = File.createTempFile("droidlm-screen-", ".png", context.cacheDir)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        debugLogStore?.retainFile(file, "screenshots", file.name)
        return try {
            when (val result = relayClient.analyzeScreenshot(baseUrlProvider(), file, goalProvider(), deviceContext = deviceContext)) {
                is RelayCallResult.Success -> OcrResult(
                    fullText = result.value.fullText,
                    blocks = emptyList(),
                    lines = result.value.lines,
                    elements = result.value.elements,
                    symbols = emptyList(),
                    source = OcrSource.OPENAI_VISION_RELAY
                )
                is RelayCallResult.Failure -> throw IllegalStateException(result.message, result.cause)
            }
        } finally {
            file.delete()
        }
    }
}
