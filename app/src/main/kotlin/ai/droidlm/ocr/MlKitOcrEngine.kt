package ai.droidlm.ocr

import android.graphics.Bitmap
import android.graphics.Point
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitOcrEngine : OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = suspendCancellableCoroutine<Text> { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
        val blocks = text.textBlocks.map { block ->
            OcrBlock(block.text, block.boundingBox, block.cornerPoints?.map { Point(it) }.orEmpty(), null)
        }
        val lines = text.textBlocks.flatMap { block ->
            block.lines.map { line -> OcrLine(line.text, line.boundingBox, line.cornerPoints?.map { Point(it) }.orEmpty(), null) }
        }
        val elements = text.textBlocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.elements.map { element -> OcrElement(element.text, element.boundingBox, element.cornerPoints?.map { Point(it) }.orEmpty(), null) }
            }
        }
        return OcrResult(
            fullText = text.text,
            blocks = blocks,
            lines = lines,
            elements = elements,
            symbols = emptyList(),
            source = OcrSource.ML_KIT_ON_DEVICE
        )
    }
}
