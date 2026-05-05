package ai.droidlm.openai

import ai.droidlm.relay.RelayCallResult
import ai.droidlm.relay.RelayPlanRequest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiClientTest {
    @Test fun defaultGpt5ModelUsesMaxCompletionTokens() = runTest {
        MockWebServer().use { server ->
            server.enqueue(chatCompletion(planPreviewJson()))
            server.start()

            val result = OpenAiClient(endpoint = server.url("/v1/chat/completions").toString())
                .planPreview("sk-test", "", minimalRequest())

            assertTrue(result is RelayCallResult.Success)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("gpt-5.4-nano", body.getString("model"))
            assertEquals(1800, body.getInt("max_completion_tokens"))
            assertFalse(body.has("max_tokens"))
            assertFalse(body.has("temperature"))
        }
    }

    @Test fun legacyChatModelKeepsLegacyTokenParameter() = runTest {
        MockWebServer().use { server ->
            server.enqueue(chatCompletion("""{"action":"NO_OP","reason":"ok","requiresConfirmation":false,"message":"ok"}"""))
            server.start()

            val result = OpenAiClient(endpoint = server.url("/v1/chat/completions").toString())
                .planAction("sk-test", "gpt-4o-mini", minimalRequest())

            assertTrue(result is RelayCallResult.Success)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("gpt-4o-mini", body.getString("model"))
            assertEquals(900, body.getInt("max_tokens"))
            assertEquals(0, body.getInt("temperature"))
            assertFalse(body.has("max_completion_tokens"))
        }
    }

    @Test fun unsupportedParameterErrorsRemainActionable() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "error": {
                            "message": "Unsupported parameter: 'max_tokens' is not supported.",
                            "code": "unsupported_parameter",
                            "type": "invalid_request_error"
                          }
                        }
                        """.trimIndent()
                    )
            )
            server.start()

            val result = OpenAiClient(endpoint = server.url("/v1/chat/completions").toString())
                .planPreview("sk-test", "gpt-5.4-nano", minimalRequest())

            assertTrue(result is RelayCallResult.Failure)
            result as RelayCallResult.Failure
            assertEquals("unsupported_parameter", result.errorCode)
            assertTrue(result.message.contains("Unsupported parameter"))
        }
    }

    private fun minimalRequest(): RelayPlanRequest = RelayPlanRequest(
        goal = "open drive",
        uiState = null,
        packages = emptyList(),
        history = emptyList(),
        maxSteps = 1
    )

    private fun chatCompletion(content: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            JSONObject()
                .put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put("message", JSONObject().put("content", content))
                    )
                )
                .toString()
        )

    private fun planPreviewJson(): String = """
        {
          "model":"gpt-5.4-nano",
          "summary":"Open Drive",
          "riskLevel":"LOW",
          "requiresConfirmation":false,
          "steps":[
            {"index":1,"action":"OPEN_APP","appName":"Drive","packageName":"com.google.android.apps.docs","reason":"Open Drive","requiresConfirmation":false}
          ]
        }
    """.trimIndent()
}
