package ai.droidlm.ocr

import ai.droidlm.relay.DeviceContext
import android.graphics.Bitmap

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap, deviceContext: DeviceContext? = null): OcrResult
}
