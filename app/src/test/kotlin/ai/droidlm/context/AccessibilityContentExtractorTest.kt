package ai.droidlm.context

import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityContentExtractorTest {
    @Test fun extractsMultilineContentDescriptionsAsGeneralizedContent() {
        val preview = "Summary of Docs\r\nNotes\r\n- Meetings - H&O PAT Weekly, PAT Touchbase\r\n- Planning - Next roadmap"
        val state = PortalState(
            packageName = "com.google.android.apps.docs",
            activityName = "DriveActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(node(nodeId = "preview", contentDescription = preview))
        )

        val extraction = AccessibilityContentExtractor.extract(state)

        assertTrue(extraction.fullText.contains("Meetings - H&O PAT Weekly"))
        assertTrue(extraction.lines.any { it.sourceField == "contentDescription" && it.text.contains("PAT Touchbase") })
        assertFalse(extraction.truncated)
    }

    @Test fun searchCanReturnFirstCandidateInSectionWithExcludedText() {
        val state = PortalState(
            packageName = "com.google.android.apps.docs",
            activityName = "DriveActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(
                node(
                    nodeId = "preview",
                    contentDescription = "- Meetings - Next sync, H&O PAT Weekly, PAT Touchbase\n- Planning - Next roadmap"
                )
            )
        )

        val result = AccessibilityContentExtractor.search(
            state,
            AccessibilityContentSearchQuery(sectionLabel = "Meetings", exclude = "Next", ordinal = 1, maxMatches = 5)
        )

        assertEquals(1, result.getInt("matchCount"))
        assertEquals("H&O PAT Weekly", result.getJSONArray("matches").getJSONObject(0).getString("text"))
    }

    @Test fun extractionReportsTruncationWhenContentExceedsBudget() {
        val state = PortalState(
            packageName = "pkg",
            activityName = "Activity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(node(text = "A".repeat(200)))
        )

        val extraction = AccessibilityContentExtractor.extract(state, maxContentChars = 50)

        assertTrue(extraction.truncated)
        assertEquals(50, extraction.emittedCharCount)
        assertEquals(50, extraction.fullText.length)
    }

    private fun node(
        nodeId: String? = null,
        text: String? = null,
        contentDescription: String? = null
    ) = UiNode(
        nodeId = nodeId,
        text = text,
        contentDescription = contentDescription,
        className = null,
        packageName = "pkg",
        bounds = null,
        clickable = false,
        editable = false,
        focused = false,
        enabled = true,
        selected = false
    )
}
