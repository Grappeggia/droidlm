package ai.droidlm.execution

import ai.droidlm.context.GoogleDocsContextProvider
import ai.droidlm.portal.PortalState
import ai.droidlm.portal.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechTranscriptCorrectorTest {
    @Test fun correctsVisibleDocsTitleMisheardAsDucks() {
        val state = PortalState(
            packageName = GoogleDocsContextProvider.DOCS_PACKAGE,
            activityName = "DocsActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = listOf(node(text = "Summary of Docs"))
        )

        val correction = SpeechTranscriptCorrector().correct("open my summary of ducks", state)

        assertEquals("visible_label", correction?.source)
        assertEquals("summary of ducks", correction?.targetText)
        assertEquals("Summary of Docs", correction?.replacementText)
        assertEquals("open my Summary of Docs", correction?.correctedTranscript)
    }

    @Test fun correctsDocsSoundalikeOnlyInWorkspaceContext() {
        val state = PortalState(
            packageName = GoogleDocsContextProvider.DOCS_PACKAGE,
            activityName = "DocsActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = emptyList()
        )

        val correction = SpeechTranscriptCorrector().correct("open summary of ducks", state)

        assertEquals("workspace_alias", correction?.source)
        assertEquals("summary of docs", correction?.replacementText)
        assertEquals("open summary of docs", correction?.correctedTranscript)
    }

    @Test fun doesNotRewriteDuckPhrasesOutsideWorkspaceContext() {
        val state = PortalState(
            packageName = "com.example.gallery",
            activityName = "GalleryActivity",
            screenWidth = 100,
            screenHeight = 200,
            nodes = emptyList()
        )

        val correction = SpeechTranscriptCorrector().correct("open photos of ducks", state)

        assertNull(correction)
    }

    private fun node(text: String) = UiNode(
        nodeId = "label",
        text = text,
        contentDescription = null,
        className = null,
        packageName = GoogleDocsContextProvider.DOCS_PACKAGE,
        bounds = null,
        clickable = false,
        editable = false,
        focused = false,
        enabled = true,
        selected = false
    )
}
