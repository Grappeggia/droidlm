package ai.droidlm.openai

import ai.droidlm.agent.AgentBudgets
import ai.droidlm.agent.AgentDecisionStatus
import ai.droidlm.agent.AgentTurnRequest
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.relay.RelayPlanRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiClientTest {
    @Test fun defaultGpt5ModelUsesMaxCompletionTokens() = runTest {
        MockWebServer().use { server ->
            server.enqueue(chatCompletion(planPreviewJson()))
            server.start()

            val result = OpenAiClient(endpointProvider = { server.url("/v1/chat/completions").toString() })
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

            val result = OpenAiClient(endpointProvider = { server.url("/v1/chat/completions").toString() })
                .planAction("sk-test", "gpt-4o-mini", minimalRequest())

            assertTrue(result is RelayCallResult.Success)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("gpt-4o-mini", body.getString("model"))
            assertEquals(900, body.getInt("max_tokens"))
            assertEquals(0, body.getInt("temperature"))
            assertFalse(body.has("max_completion_tokens"))
        }
    }


    @Test fun readTimeoutsAreClassifiedAsTimeouts() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeadersDelay(250, TimeUnit.MILLISECONDS)
                    .setBody("{}")
            )
            server.start()

            val httpClient = OkHttpClient.Builder()
                .connectTimeout(100, TimeUnit.MILLISECONDS)
                .readTimeout(50, TimeUnit.MILLISECONDS)
                .writeTimeout(100, TimeUnit.MILLISECONDS)
                .build()
            val result = OpenAiClient(
                client = httpClient,
                endpointProvider = { server.url("/v1/chat/completions").toString() }
            ).planPreview("sk-test", "gpt-5.4-nano", minimalRequest())

            assertTrue(result is RelayCallResult.Failure)
            result as RelayCallResult.Failure
            assertEquals("TIMEOUT", result.errorCode)
            assertTrue(result.message.contains("timeout", ignoreCase = true))
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

            val result = OpenAiClient(endpointProvider = { server.url("/v1/chat/completions").toString() })
                .planPreview("sk-test", "gpt-5.4-nano", minimalRequest())

            assertTrue(result is RelayCallResult.Failure)
            result as RelayCallResult.Failure
            assertEquals("unsupported_parameter", result.errorCode)
            assertTrue(result.message.contains("Unsupported parameter"))
        }
    }

    @Test fun invalidActionJsonTriggersRepairAttempt() = runTest {
        MockWebServer().use { server ->
            server.enqueue(chatCompletion("{\"action\":\"OPEN_APP\",\"appName\":\"Drive\",\"reason\":\"Open Drive\"}"))
            server.enqueue(chatCompletion("{\"action\":\"OPEN_APP\",\"appName\":\"Drive\",\"packageName\":\"com.google.android.apps.docs\",\"reason\":\"Open Drive\"}"))
            server.start()

            val result = OpenAiClient(endpointProvider = { server.url("/v1/chat/completions").toString() })
                .planAction("sk-test", "gpt-5.4-nano", minimalRequest())

            assertTrue(result is RelayCallResult.Success)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun plannerPromptMentionsSemanticActions() = runTest {
        MockWebServer().use { server ->
            server.enqueue(chatCompletion(planPreviewJson()))
            server.start()

            val result = OpenAiClient(endpointProvider = { server.url("/v1/chat/completions").toString() })
                .planPreview("sk-test", "gpt-5.4-nano", minimalRequest())

            assertTrue(result is RelayCallResult.Success)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            val prompt = body.getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(prompt.contains("SCROLL"))
            assertTrue(prompt.contains("TAP_TEXT"))
            assertTrue(prompt.contains("WAIT_FOR_UI"))
            assertTrue(prompt.contains("OPEN_APP_STORE_LISTING"))
            assertTrue(prompt.contains("launchable=true"))
            assertTrue(prompt.contains("SEARCH_ACCESSIBILITY_CONTENT"))
            assertTrue(prompt.contains("accessibilityContentContext"))
            assertTrue(prompt.contains("Do not use FIND_TEXT_ON_SCREEN to search for an excluded word"))
            assertTrue(prompt.contains("confidence"))
            assertTrue(prompt.contains("expectedResult"))
            assertTrue(prompt.contains("Decision contract"))
            assertFalse(prompt.contains("com.google.android.apps.docs.editors.sheets\",\"reason\":\"why"))
        }
    }

    @Test fun agentTurnPromptUsesToolBudgets() = runTest {
        MockWebServer().use { server ->
            server.enqueue(chatCompletion("{\"status\":\"CALL_TOOLS\",\"message\":\"open\",\"toolCalls\":[{\"id\":\"c1\",\"name\":\"OPEN_APP\",\"args\":{\"packageName\":\"com.google.android.apps.docs\"}}]}"))
            server.start()

            val result = OpenAiClient(endpointProvider = { server.url("/v1/chat/completions").toString() })
                .nextAgentTurn(
                    "sk-test",
                    "gpt-5.4-nano",
                    AgentTurnRequest("open drive", 1, AgentBudgets(maxTurns = 4, maxToolCallsTotal = 8), 8, null, emptyList(), emptyList())
                )

            assertTrue(result is RelayCallResult.Success)
            assertEquals(AgentDecisionStatus.CALL_TOOLS, (result as RelayCallResult.Success).value.status)
            val prompt = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(prompt.contains("Available tools"))
            assertTrue(prompt.contains("Remaining tool calls: 8"))
            assertTrue(prompt.contains("OPEN_APP"))
            assertTrue(prompt.contains("doNotRepeat") || prompt.contains("Do not repeat"))
            assertTrue(prompt.contains("confidence"))
            assertTrue(prompt.contains("expectedResult"))
            assertTrue(prompt.contains("LOW must gather more observation"))
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
            {"index":1,"action":"OPEN_APP","appName":"Drive","packageName":"com.google.android.apps.docs","reason":"Open Drive","requiresConfirmation":false,"confidence":"HIGH","expectedResult":"Drive opens"}
          ]
        }
    """.trimIndent()
}
