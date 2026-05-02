package ai.droidlm.relay

import ai.droidlm.intent.DroidLmAction
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RelayClientTest {
    @Test fun healthSuccess() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            server.start()
            val result = RelayClient().health(server.url("/").toString())
            assertTrue(result is RelayCallResult.Success && result.value)
        }
    }

    @Test fun transcriptionJsonParse() {
        val parsed = RelayClient().parseTranscriptionJson("{\"text\":\"open drive\",\"durationMs\":123}")
        assertEquals("open drive", parsed.text)
        assertEquals(123L, parsed.durationMs)
    }

    @Test fun invalidJsonError() {
        val result = runCatching { RelayClient().parseTranscriptionJson("not-json") }
        assertTrue(result.isFailure)
    }

    @Test fun timeoutError() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val client = RelayClient(OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build())
            val result = client.health(server.url("/").toString())
            assertTrue(result is RelayCallResult.Failure)
        }
    }

    @Test fun non2xxError() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            server.start()
            val result = RelayClient().health(server.url("/").toString())
            assertTrue(result is RelayCallResult.Failure)
        }
    }

    @Test fun planActionJsonParse() {
        val action = RelayClient().parsePlanActionJson("{\"action\":\"OPEN_APP\",\"appName\":\"Drive\",\"packageName\":\"com.google.android.apps.docs\",\"reason\":\"test\",\"requiresConfirmation\":false}")
        assertTrue(action is DroidLmAction.OpenApp)
    }

    @Test fun analyzeScreenshotJsonParse() {
        val result = RelayClient().parseVisionAnalysisJson("{\"fullText\":\"Budget\",\"suggestedAction\":{\"type\":\"TAP\",\"x\":1,\"y\":2,\"confidence\":0.74},\"lines\":[{\"text\":\"Budget\",\"boundingBox\":{\"x\":1,\"y\":2,\"width\":3,\"height\":4}}],\"elements\":[]}")
        assertEquals("Budget", result.fullText)
        assertEquals(1, result.lines.size)
        assertEquals(1, result.suggestedAction?.x)
    }
}
