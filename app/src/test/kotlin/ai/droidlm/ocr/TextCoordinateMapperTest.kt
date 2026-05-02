package ai.droidlm.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextCoordinateMapperTest {
    private val mapper = TextCoordinateMapper()

    @Test fun findsLineContainingQuery() {
        val line = OcrLine("Project budget due Friday", Rect(10, 20, 210, 60), emptyList(), null)
        val result = OcrResult("Project budget due Friday", emptyList(), listOf(line), emptyList(), emptyList(), OcrSource.ML_KIT_ON_DEVICE)
        assertSame(line, mapper.findLineContaining(result, "budget"))
    }

    @Test fun estimatesCoordinateAfterElement() {
        val element = OcrElement("budget", Rect(50, 20, 100, 60), emptyList(), null)
        val result = OcrResult("budget", emptyList(), emptyList(), listOf(element), emptyList(), OcrSource.ML_KIT_ON_DEVICE)
        val point = mapper.estimateCoordinateAfterText(result, "budget")
        assertEquals(100, point?.x)
        assertEquals(40, point?.y)
    }

    @Test fun estimatesCoordinateBeforeElement() {
        val element = OcrElement("budget", Rect(50, 20, 100, 60), emptyList(), null)
        val result = OcrResult("budget", emptyList(), emptyList(), listOf(element), emptyList(), OcrSource.ML_KIT_ON_DEVICE)
        val point = mapper.estimateCoordinateBeforeText(result, "budget")
        assertEquals(50, point?.x)
        assertEquals(40, point?.y)
    }

    @Test fun missingAnchorReturnsNull() {
        val result = OcrResult("", emptyList(), emptyList(), emptyList(), emptyList(), OcrSource.ML_KIT_ON_DEVICE)
        assertNull(mapper.estimateCoordinateAfterText(result, "missing"))
    }
}
