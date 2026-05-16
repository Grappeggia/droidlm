package ai.droidlm.openai

import ai.droidlm.diagnostics.DebugLogStore
import ai.droidlm.diagnostics.NetworkDiagnostics
import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.relay.RelayPlanRequest
import ai.droidlm.settings.SettingsRepository
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@Config(sdk = [29])
@RunWith(RobolectricTestRunner::class)
class OpenAiDiagnosticsTest {
    @Test fun failedPlanRetainsNetworkTraceMetadata() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearDebugDirectories(context)
        val settingsRepository = SettingsRepository(context)
        val actionLogs = ActionLogRepository()
        val diagnosticsLogger = SpeechDiagnosticsLogger(context, settingsRepository, actionLogs)
        val debugLogStore = DebugLogStore(context, settingsRepository, actionLogs, diagnosticsLogger)

        settingsRepository.updateDebugLoggingEnabled(true)
        diagnosticsLogger.setEnabled(true)

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
                endpointProvider = { server.url("/v1/chat/completions").toString() },
                debugLogStore = debugLogStore,
                networkDiagnostics = NetworkDiagnostics(context)
            ).planPreview("sk-test", "gpt-5.4-nano", RelayPlanRequest("open drive", null, emptyList(), emptyList(), 1))

            assertTrue(result is RelayCallResult.Failure)
            result as RelayCallResult.Failure
            assertEquals("TIMEOUT", result.errorCode)
        }

        val traceFile = File(context.cacheDir, "droidlm-debug-logs/llm")
            .listFiles()
            .orEmpty()
            .singleOrNull { it.name.contains("plan-preview") }
        assertNotNull(traceFile)
        val trace = JSONObject(traceFile!!.readText())
        assertEquals("direct_openai", trace.getString("endpointMode"))
        assertEquals(false, trace.getJSONObject("response").getBoolean("success"))
        assertEquals(50, trace.getJSONObject("timeoutConfig").getInt("readTimeoutMs"))
        assertTrue(trace.getJSONObject("requestMetadata").getInt("requestBytes") > 0)
        assertTrue(trace.getJSONObject("requestMetadata").getInt("promptChars") > 0)
        assertTrue(trace.getJSONObject("networkTrace").getJSONArray("events").length() > 0)
        assertTrue(trace.has("connectivity"))
    }

    private fun clearDebugDirectories(context: Context) {
        File(context.cacheDir, "droidlm-debug-logs").deleteRecursively()
        File(context.cacheDir, "droidlm-debug-exports").deleteRecursively()
        File(context.cacheDir, "droidlm-diagnostics").deleteRecursively()
    }
}
