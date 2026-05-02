package ai.droidlm.ocr

import android.graphics.Point
import android.graphics.Rect

data class OcrResult(
    val fullText: String,
    val blocks: List<OcrBlock>,
    val lines: List<OcrLine>,
    val elements: List<OcrElement>,
    val symbols: List<OcrSymbol>,
    val source: OcrSource
)

data class OcrBlock(
    val text: String,
    val boundingBox: Rect?,
    val cornerPoints: List<Point>,
    val confidence: Float?
)

data class OcrLine(
    val text: String,
    val boundingBox: Rect?,
    val cornerPoints: List<Point>,
    val confidence: Float?
)

data class OcrElement(
    val text: String,
    val boundingBox: Rect?,
    val cornerPoints: List<Point>,
    val confidence: Float?
)

data class OcrSymbol(
    val text: String,
    val boundingBox: Rect?,
    val cornerPoints: List<Point>,
    val confidence: Float?
)

enum class OcrSource {
    ML_KIT_ON_DEVICE,
    OPENAI_VISION_RELAY
}
