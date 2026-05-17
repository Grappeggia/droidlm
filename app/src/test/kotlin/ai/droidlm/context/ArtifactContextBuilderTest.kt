package ai.droidlm.context

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtifactContextBuilderTest {
    @Test fun extractNavigationRequestHandlesSpeechShortenedNavigateAndTrailingTask() {
        val query = ArtifactContextBuilder.extractNavigationRequest(
            "Navig to the meeting session, then select the first meeting which doesn't have Next on its title"
        )

        assertEquals("meeting session", query)
    }
}
