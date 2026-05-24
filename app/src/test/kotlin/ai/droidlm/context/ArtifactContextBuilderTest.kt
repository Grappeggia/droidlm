package ai.droidlm.context

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactContextBuilderTest {
    @Test fun extractNavigationRequestHandlesSpeechShortenedNavigateAndTrailingTask() {
        val query = ArtifactContextBuilder.extractNavigationRequest(
            "Navig to the meeting session, then select the first meeting which doesn't have Next on its title"
        )

        assertEquals("meeting session", query)
    }

    @Test fun extractNavigationRequestHandlesOpenVisibleArtifactTarget() {
        val query = ArtifactContextBuilder.extractNavigationRequest("open Summary of Docs")

        assertEquals("Summary of Docs", query)
    }

    @Test fun matchingTargetFindsVisibleDocumentTitleContainingAppName() {
        val artifactContext = JSONObject().put(
            "navigationTargets",
            JSONArray()
                .put(JSONObject().put("label", "Distractor Meeting Notes").put("kind", "document"))
                .put(JSONObject().put("label", "Summary of Docs").put("kind", "document").put("nodeId", "doc-row"))
        )

        val target = ArtifactContextBuilder.matchingTarget(artifactContext, "Summary of Docs")

        assertEquals("Summary of Docs", target?.getString("label"))
        assertEquals("doc-row", target?.getString("nodeId"))
        assertTrue(ArtifactContextBuilder.hasMatchingTarget(artifactContext, "summary of docs"))
    }
}
