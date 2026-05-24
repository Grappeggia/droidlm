package ai.droidlm.e2e

import ai.droidlm.DroidLMApp
import ai.droidlm.MainActivity
import ai.droidlm.auth.AllowlistRepository
import ai.droidlm.auth.AuthRepository
import ai.droidlm.auth.AuthState
import ai.droidlm.auth.AuthTokenResult
import ai.droidlm.auth.AuthUser
import ai.droidlm.diagnostics.DebugLogUploadEndpoint
import ai.droidlm.logs.ActionLogType
import ai.droidlm.relay.FirebaseBearerTokenResult
import ai.droidlm.relay.RelayClient
import ai.droidlm.ui.DroidLmViewModel
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DroidLmDebugLogUploadE2ETest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val app: DroidLMApp
        get() = ApplicationProvider.getApplicationContext()

    private var server: MockWebServer? = null
    private var allowlistServer: MockWebServer? = null

    @Before
    fun setUp() = runBlocking {
        assumeTrue(
            "Debug log upload E2E tests run only via `./gradlew connectedDebugLogUploadE2e`.",
            InstrumentationRegistry.getArguments().getString("debugLogUploadE2e") == "true"
        )
        val args = InstrumentationRegistry.getArguments()
        val liveUploadUrl = args.getString("debugLogUploadUrl")?.takeIf { it.isNotBlank() }
        allowlistServer = MockWebServer().also { mockServer ->
            repeat(2) {
                mockServer.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"allowed":true,"email":"e2e@example.test","message":"Access approved."}""")
                )
            }
            mockServer.start()
        }
        val uploadUrl = liveUploadUrl ?: MockWebServer().also { mockServer ->

            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"ok":true,"bucket":"example-debug-logs","objectName":"debug-logs/synthetic/e2e.zip","gsUri":"gs://example-debug-logs/debug-logs/synthetic/e2e.zip","sizeBytes":1234,"contentType":"application/zip"}"""
                    )
            )
            mockServer.start()
            server = mockServer
        }.url("/").toString()
        DebugLogUploadEndpoint.setOverrideForTesting(uploadUrl)
        app.debugLogStore.clear()
        app.settingsRepository.updateOnboardingCompletedVersion(DroidLmViewModel.ONBOARDING_VERSION)
        app.settingsRepository.updateDebugLoggingEnabled(true)
        app.speechDiagnosticsLogger.setEnabled(true)
        app.actionLogRepository.clear()
    }

    @After
    fun tearDown() {
        runBlocking {
            runCatching { server?.shutdown() }
            runCatching { allowlistServer?.shutdown() }
            runCatching { DebugLogUploadEndpoint.clearOverrideForTesting() }
            runCatching { app.settingsRepository.updateDebugLoggingEnabled(false) }
            runCatching { app.speechDiagnosticsLogger.setEnabled(false) }
            runCatching { app.debugLogStore.clear() }
        }
    }

    @Test
    fun uploadLogsUsesHiddenConfiguredEndpoint() = runBlocking {
        app.debugLogStore.retainText(
            category = "e2e",
            source = "debug-log-upload",
            text = "Debug log upload E2E marker"
        )
        app.actionLogRepository.log(ActionLogType.ACTION_RESULT, "Debug log upload E2E marker")

        val fakeAuth = FakeAuthRepository()
        val relayClient = RelayClient(firebaseIdTokenProvider = { FirebaseBearerTokenResult.Success("e2e-token") })
        val allowlistRepository = AllowlistRepository(
            authRepository = fakeAuth,
            relayClient = relayClient,
            logs = app.actionLogRepository,
            endpointProvider = { allowlistServer?.url("/")?.toString() ?: DebugLogUploadEndpoint.url() }
        )
        assertTrue("Expected test account to be allowlisted", allowlistRepository.ensureAllowed(forceRefresh = true))
        allowlistServer?.let { mockServer ->
            val allowlistRequest = mockServer.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull("Expected the app to check allowlist access", allowlistRequest)
            assertEquals("/allowlist/check", allowlistRequest!!.path?.substringBefore('?'))
            assertEquals("Bearer e2e-token", allowlistRequest.getHeader("Authorization"))
        }
        val deps = app.graph.droidLmViewModelDeps().copy(
            authRepository = fakeAuth,
            allowlistRepository = allowlistRepository,
            relayClient = relayClient
        )
        DroidLmViewModel(app, deps).shareDebugLogs("E2E issue description").join()

        server?.let { mockServer ->
            val request = mockServer.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull("Expected the app to upload debug logs to the hidden endpoint", request)
            assertEquals("/", request!!.path?.substringBefore('?'))
            assertEquals("POST", request.method)
            assertTrue(
                "Upload should be multipart/form-data",
                request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data")
            )
            assertEquals("Bearer e2e-token", request.getHeader("Authorization"))
            val body = request.body.readByteArray().toString(Charsets.ISO_8859_1)
            assertTrue("Multipart body should include the logs file field", body.contains("name=\"logs\""))
            assertTrue("Multipart body should include a zip filename", body.contains("filename=\"droidlm-debug-logs-"))
            assertTrue("Multipart body should include the app package field", body.contains("name=\"appPackage\""))
            assertTrue("Multipart body should identify the debug app package", body.contains(app.packageName))
        }
        assertTrue(
            "Action log should report the debug log upload without exposing the GCS URI",
            app.actionLogRepository.logs.value.any {
                it.message == "Uploaded debug logs" && it.details.isNullOrBlank()
            }
        )
    }

    private class FakeAuthRepository : AuthRepository {
        override val authState: StateFlow<AuthState> = MutableStateFlow(
            AuthState(
                configured = true,
                ready = true,
                loading = false,
                user = AuthUser(
                    uid = "e2e-user",
                    displayName = "E2E User",
                    email = "e2e@example.test",
                    emailVerified = true
                )
            )
        )

        override suspend fun signInWithGoogle(context: Context) = Unit
        override suspend fun signInWithEmail(email: String, password: String) = Unit
        override suspend fun createAccountWithEmail(email: String, password: String) = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
        override suspend fun currentIdTokenResult(forceRefresh: Boolean): AuthTokenResult = AuthTokenResult.Success("e2e-token")
        override suspend fun reloadCurrentUser() = Unit
        override fun signOut() = Unit
    }
}
