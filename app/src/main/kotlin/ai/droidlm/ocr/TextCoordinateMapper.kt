package ai.droidlm.ocr

import android.graphics.Point
import android.graphics.Rect

class TextCoordinateMapper {
    fun findLineContaining(result: OcrResult, query: String): OcrLine? {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return null
        return result.lines.firstOrNull { it.text.lowercase().contains(needle) }
            ?: result.lines.maxByOrNull { similarity(needle, it.text.lowercase()) }?.takeIf { similarity(needle, it.text.lowercase()) >= 0.65 }
    }

    fun findElementContaining(result: OcrResult, query: String): OcrElement? {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return null
        return result.elements.firstOrNull { it.text.lowercase().contains(needle) || needle.contains(it.text.lowercase()) }
            ?: result.elements.maxByOrNull { similarity(needle, it.text.lowercase()) }?.takeIf { similarity(needle, it.text.lowercase()) >= 0.65 }
    }

    fun estimateCoordinateAfterText(result: OcrResult, textAnchor: String): Point? {
        val box = findElementContaining(result, textAnchor)?.boundingBox ?: findLineContaining(result, textAnchor)?.boundingBox
        return box?.let { Point(it.right, it.centerY()) }
    }

    fun estimateCoordinateBeforeText(result: OcrResult, textAnchor: String): Point? {
        val box = findElementContaining(result, textAnchor)?.boundingBox ?: findLineContaining(result, textAnchor)?.boundingBox
        return box?.let { Point(it.left, it.centerY()) }
    }

    fun estimateCoordinateAtLineEnd(result: OcrResult, lineOrdinal: Int): Point? {
        val line = result.lines.getOrNull((lineOrdinal - 1).coerceAtLeast(0)) ?: return null
        return line.boundingBox?.let { Point(it.right, it.centerY()) }
    }

    fun estimateCoordinateAtParagraphStart(result: OcrResult, paragraphOrdinal: Int): Point? {
        val block = result.blocks.getOrNull((paragraphOrdinal - 1).coerceAtLeast(0)) ?: return null
        return block.boundingBox?.let { Point(it.left, it.top + it.height().coerceAtLeast(1) / 3) }
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        val tokensA = a.split(Regex("\\s+")).toSet()
        val tokensB = b.split(Regex("\\s+")).toSet()
        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val union = tokensA.union(tokensB).size.toDouble().coerceAtLeast(1.0)
        return intersection / union
    }
}
