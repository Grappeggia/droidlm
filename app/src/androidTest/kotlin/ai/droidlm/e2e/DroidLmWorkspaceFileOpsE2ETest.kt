package ai.droidlm.e2e

import ai.droidlm.DroidLMApp
import ai.droidlm.MainActivity
import ai.droidlm.overlay.FloatingControlOverlayService
import ai.droidlm.relay.RelayCallResult
import ai.droidlm.settings.TranscriptionProvider
import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DroidLmWorkspaceFileOpsE2ETest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val app: DroidLMApp
        get() = ApplicationProvider.getApplicationContext()
    private val device: UiDevice
        get() = UiDevice.getInstance(instrumentation)

    private lateinit var server: MockWebServer

    @Before
    fun setUp() = runBlocking {
        assumeTrue(
            "Workspace file operation E2E tests run only via `./gradlew connectedWorkspaceFileOpsE2e`.",
            InstrumentationRegistry.getArguments().getString("workspaceFileOpsE2e") == "true"
        )
        grantRuntimePermissions()
        grantOverlayPermissionForEmulator()
        enableAccessibilityServiceForEmulator()
        server = MockWebServer()
        server.start()
        app.executor.cancelActive()
        app.settingsRepository.updateRelayBaseUrl(server.url("/").toString())
        app.settingsRepository.updateTranscriptionProvider(TranscriptionProvider.OPENAI_DIRECT)
        app.settingsRepository.updateAutoAcceptSafePlans(true)
        app.settingsRepository.updateRequireRiskConfirmation(false)
        app.settingsRepository.updateDebugLoggingEnabled(false)
        app.settingsRepository.updateOverlayPosition(24, 250)
    }

    @After
    fun tearDown() {
        runCatching { targetContext.stopService(FloatingControlOverlayService.intent(targetContext, FloatingControlOverlayService.ACTION_STOP)) }
        runCatching { targetContext.stopService(ai.droidlm.voice.WakeWordForegroundService.intent(targetContext, ai.droidlm.voice.WakeWordForegroundService.ACTION_CANCEL)) }
        runCatching { device.pressHome() }
        runCatching { server.shutdown() }
    }

    @Test
    fun addBulletOnCurrentLineFromHoverWidgetWithoutOpenAiKey() = runBlocking {
        runOperationWithoutOpenAiKey(
            operation = WorkspaceOperation(
                transcript = "Add a bullet point on the current line",
                devicePath = "$DEVICE_RUN_ROOT/docs/current-line.txt",
                mimeType = "text/plain",
                viewerPackage = DOCS_PACKAGE,
                seedText = "Shopping list\ncurrent line\nnext line\n",
                expectedTexts = listOf("- current line"),
                unsupportedAction = "FORMAT_CURRENT_LINE_AS_BULLET"
            )
        )
    }

    @Test
    fun addBulletOnCurrentLineFromHoverWidgetWithOpenAiKeyWhenRequested() = runBlocking {
        runOperationWithOpenAiKeyWhenRequested(
            operation = WorkspaceOperation(
                transcript = "Add a bullet point on the current line",
                devicePath = "$DEVICE_RUN_ROOT/docs/current-line-key.txt",
                mimeType = "text/plain",
                viewerPackage = DOCS_PACKAGE,
                seedText = "Shopping list\ncurrent line\nnext line\n",
                expectedTexts = listOf("- current line"),
                unsupportedAction = "FORMAT_CURRENT_LINE_AS_BULLET"
            )
        )
    }

    @Test
    fun replaceDraftWithFinalFromHoverWidgetWithOpenAiKeyWhenRequested() = runBlocking {
        runOperationWithOpenAiKeyWhenRequested(
            operation = WorkspaceOperation(
                transcript = "Replace draft with final",
                devicePath = "$DEVICE_RUN_ROOT/docs/replace-draft.txt",
                mimeType = "text/plain",
                viewerPackage = DOCS_PACKAGE,
                seedText = "Release note: draft\n",
                expectedTexts = listOf("Release note: final"),
                unsupportedAction = "REPLACE_CURRENT_DOCUMENT_TEXT"
            )
        )
    }

    @Test
    fun replaceOnlyFirstDraftMatchFromHoverWidgetWithPlannedFileAction() = runBlocking {
        runOperationWithPlannedFileAction(
            operation = WorkspaceOperation(
                transcript = "Replace draft with final",
                devicePath = "$DEVICE_RUN_ROOT/docs/replace-first-only.txt",
                mimeType = "text/plain",
                viewerPackage = DOCS_PACKAGE,
                seedText = "draft title\ndraft body\n",
                expectedTexts = listOf("final title", "draft body"),
                expectedFileText = "final title\ndraft body\n",
                unsupportedAction = "REPLACE_CURRENT_DOCUMENT_TEXT"
            )
        )
    }

    @Test
    fun appendReviewNoteFromHoverWidgetWithOpenAiKeyWhenRequested() = runBlocking {
        runOperationWithOpenAiKeyWhenRequested(
            operation = WorkspaceOperation(
                transcript = "Append a note saying reviewed by DroidLM",
                devicePath = "$DEVICE_RUN_ROOT/docs/append-note.txt",
                mimeType = "text/plain",
                viewerPackage = DOCS_PACKAGE,
                seedText = "Quarterly notes\n",
                expectedTexts = listOf("reviewed by DroidLM"),
                unsupportedAction = "APPEND_DOCUMENT_NOTE"
            )
        )
    }

    @Test
    fun appendDuplicateReviewNoteFromHoverWidgetWithPlannedFileAction() = runBlocking {
        runOperationWithPlannedFileAction(
            operation = WorkspaceOperation(
                transcript = "Append a note saying reviewed by DroidLM",
                devicePath = "$DEVICE_RUN_ROOT/docs/append-duplicate-note.txt",
                mimeType = "text/plain",
                viewerPackage = DOCS_PACKAGE,
                seedText = "Quarterly notes\nreviewed by DroidLM\n",
                expectedTexts = listOf("reviewed by DroidLM"),
                expectedFileText = "Quarterly notes\nreviewed by DroidLM\nreviewed by DroidLM\n",
                unsupportedAction = "APPEND_DOCUMENT_NOTE"
            )
        )
    }

    @Test
    fun put2026InCurrentCellFromHoverWidgetWithOpenAiKeyWhenRequested() = runBlocking {
        runOperationWithOpenAiKeyWhenRequested(
            operation = WorkspaceOperation(
                transcript = "Put 2026 in the current cell",
                devicePath = "$DEVICE_RUN_ROOT/spreadsheets/current-cell.csv",
                mimeType = "text/csv",
                viewerPackage = SHEETS_PACKAGE,
                seedText = "Year,Value\n,10\n",
                expectedTexts = listOf("2026"),
                unsupportedAction = "SET_CURRENT_SHEET_CELL"
            )
        )
    }

    @Test
    fun addSpreadsheetRowFromHoverWidgetWithOpenAiKeyWhenRequested() = runBlocking {
        runOperationWithOpenAiKeyWhenRequested(
            operation = WorkspaceOperation(
                transcript = "Add a row with April, 120, approved",
                devicePath = "$DEVICE_RUN_ROOT/spreadsheets/add-row.csv",
                mimeType = "text/csv",
                viewerPackage = SHEETS_PACKAGE,
                seedText = "Month,Value,Status\nMarch,80,pending\n",
                expectedTexts = listOf("April", "120", "approved"),
                unsupportedAction = "ADD_SPREADSHEET_ROW"
            )
        )
    }

    @Test
    fun addDuplicateSpreadsheetRowFromHoverWidgetWithPlannedFileAction() = runBlocking {
        runOperationWithPlannedFileAction(
            operation = WorkspaceOperation(
                transcript = "Add a row with April, 120, approved",
                devicePath = "$DEVICE_RUN_ROOT/spreadsheets/add-duplicate-row.csv",
                mimeType = "text/csv",
                viewerPackage = SHEETS_PACKAGE,
                seedText = "Month,Value,Status\nMarch,80,pending\nApril,120,approved\n",
                expectedTexts = listOf("April", "120", "approved"),
                expectedFileText = "Month,Value,Status\nMarch,80,pending\nApril,120,approved\nApril,120,approved\n",
                unsupportedAction = "ADD_SPREADSHEET_ROW"
            )
        )
    }

    private suspend fun runOperationWithoutOpenAiKey(operation: WorkspaceOperation) {
        server.dispatcher = operationDispatcher(operation, firstPlanMissingKey = true, secondPlanUnsupported = false)
        openOperationFile(operation)
        triggerHoverWidgetVoiceCommand(operation.transcript)
        waitForPlannerKeyRequest()
        assertExpectedTextsVisible(operation)
    }

    private suspend fun runOperationWithOpenAiKeyWhenRequested(operation: WorkspaceOperation) {
        server.dispatcher = operationDispatcher(operation, firstPlanMissingKey = true, secondPlanUnsupported = true)
        openOperationFile(operation)
        triggerHoverWidgetVoiceCommand(operation.transcript)
        waitForPlannerKeyRequest()
        saveOpenAiKeyToRelay()
        app.executor.retryPlannerKeySetupRequest()
        waitForExecutionToSettle()
        assertExpectedTextsVisible(operation)
    }

    private suspend fun runOperationWithPlannedFileAction(operation: WorkspaceOperation) {
        server.dispatcher = operationDispatcher(operation, firstPlanMissingKey = false, secondPlanUnsupported = true)
        openOperationFile(operation)
        triggerHoverWidgetVoiceCommand(operation.transcript)
        waitForExecutionToSettle()
        assertExpectedTextsVisible(operation)
    }

    private fun operationDispatcher(
        operation: WorkspaceOperation,
        firstPlanMissingKey: Boolean,
        secondPlanUnsupported: Boolean
    ): Dispatcher {
        val planRequests = AtomicInteger(0)
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path?.substringBefore('?')) {
                    "/transcribe" -> {
                        if (request.body.size <= 1024L) {
                            MockResponse().setResponseCode(400).setBody("{\"message\":\"audio too small\"}")
                        } else {
                            jsonResponse("{\"text\":\"${operation.transcript}\",\"durationMs\":1800}")
                        }
                    }
                    "/plan-preview" -> {
                        val index = planRequests.incrementAndGet()
                        if (firstPlanMissingKey && index == 1) missingOpenAiKeyResponse()
                        else if (secondPlanUnsupported) unsupportedPlanResponse(operation)
                        else missingOpenAiKeyResponse()
                    }
                    "/setup/openai-key" -> jsonResponse("{\"ok\":true,\"openAiKeyConfigured\":true}")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun missingOpenAiKeyResponse(): MockResponse = MockResponse()
        .setResponseCode(401)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"detail\":{\"errorCode\":\"OPENAI_API_KEY_MISSING\",\"message\":\"OpenAI API key is not configured on this DroidLM relay\"}}")

    private fun unsupportedPlanResponse(operation: WorkspaceOperation): MockResponse = jsonResponse(
        """
        {
          "model":"gpt-5.4-nano",
          "summary":"${operation.transcript}",
          "riskLevel":"LOW",
          "requiresConfirmation":false,
          "steps":[
            {
              "index":1,
              "action":"${operation.unsupportedAction}",
              ${operation.planFieldsJson()}
              "reason":"Execute a generic Workspace file operation",
              "requiresConfirmation":false
            }
          ]
        }
        """.trimIndent()
    )

    private fun openOperationFile(operation: WorkspaceOperation) {
        seedDeviceFile(operation.devicePath, operation.seedText)
        executeShell("am force-stop ${operation.viewerPackage}")
        val output = executeShell(
            "am start -W -a android.intent.action.VIEW -p ${operation.viewerPackage} " +
                "-d file://${operation.devicePath} -t ${operation.mimeType}"
        )
        assertTrue("Expected fixture to open on device: $output", output.contains("Status: ok"))
        SystemClock.sleep(2500)
        device.click(540, 1100)
    }

    private fun seedDeviceFile(devicePath: String, content: String) {
        val file = File(devicePath)
        val directory = file.parentFile ?: throw AssertionError("Expected parent directory for $devicePath")
        assertTrue("Expected to create ${directory.absolutePath}", directory.exists() || directory.mkdirs())
        FileOutputStream(file).bufferedWriter(Charsets.UTF_8).use { it.write(content) }
        assertTrue("Expected seeded file to exist at $devicePath", file.isFile)
        executeShell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$devicePath")
    }

    private fun triggerHoverWidgetVoiceCommand(transcript: String) {
        val audioFile = synthesizeVoiceSample(transcript)
        app.commandRecorder.queueDebugRecordedCommand(audioFile, "audio/wav", 1800)
        targetContext.startService(FloatingControlOverlayService.intent(targetContext, FloatingControlOverlayService.ACTION_SHOW))
        val recordButton = waitForOverlayRecordButton()
        recordButton.click()
        assertTrue("Expected relay transcription request for queued real audio", waitUntil(15_000) { server.requestCount >= 1 })
    }

    private fun waitForOverlayRecordButton(): androidx.test.uiautomator.UiObject2 {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val button = device.findObject(By.desc(FloatingControlOverlayService.RECORD_BUTTON_CONTENT_DESCRIPTION))
            if (button != null) return button
            SystemClock.sleep(250)
        }
        fail("Hover widget record button was not visible")
        throw AssertionError("unreachable")
    }

    private fun waitForPlannerKeyRequest() {
        assertTrue(
            "Expected planner to request an OpenAI key after hover-widget audio command",
            waitUntil(20_000) { app.executor.plannerKeySetupRequest.value != null }
        )
    }

    private suspend fun saveOpenAiKeyToRelay() {
        val openAiKey = InstrumentationRegistry.getArguments().getString("openAiApiKey").orEmpty()
        assertTrue("OPENAI_API_KEY from .env.local must be provided to connectedWorkspaceFileOpsE2e", openAiKey.startsWith("sk-"))
        val relayUrl = app.settingsRepository.settings.first().relayBaseUrl
        when (val result = app.relayClient.saveOpenAiKey(relayUrl, "test-setup-token", openAiKey)) {
            is RelayCallResult.Success -> assertTrue("Expected relay to accept OpenAI key setup", result.value.ok)
            is RelayCallResult.Failure -> fail("Could not save OpenAI key to mock relay: ${result.message}")
        }
    }

    private fun waitForExecutionToSettle() {
        assertTrue(
            "Expected planner retry to execute or fail within timeout",
            waitUntil(20_000) {
                val status = app.executor.uiState.value.status
                status == "Idle" || status == "Error"
            }
        )
    }

    private fun assertExpectedTextsVisible(operation: WorkspaceOperation) {
        val visible = waitUntil(10_000) {
            val fileText = readDeviceFile(operation.devicePath)
            val textExpectationsMet = operation.expectedTexts.all { expected ->
                device.hasObject(By.textContains(expected)) ||
                    device.hasObject(By.descContains(expected)) ||
                    fileText.contains(expected)
            }
            val exactFileMatch = operation.expectedFileText?.let { fileText == it } ?: true
            textExpectationsMet && exactFileMatch
        }
        val fileText = readDeviceFile(operation.devicePath)
        val missing = operation.expectedTexts.filterNot { expected ->
            device.hasObject(By.textContains(expected)) ||
                device.hasObject(By.descContains(expected)) ||
                fileText.contains(expected)
        }
        val state = app.executor.uiState.value
        val expectedFileSuffix = operation.expectedFileText?.let { "; expected file=${it.take(200)}" }.orEmpty()
        assertTrue(
            "Expected ${operation.expectedTexts} after '${operation.transcript}', but missing $missing. " +
                "Last status=${state.status}; last result=${state.lastResult}; parsed=${state.parsedAction}; " +
                "device file=${fileText.take(200)}$expectedFileSuffix",
            visible
        )
        operation.expectedFileText?.let { expectedFileText ->
            assertEquals("Expected exact device file for '${operation.transcript}'", expectedFileText, fileText)
        }
    }

    private fun readDeviceFile(devicePath: String): String = executeShell("cat $devicePath")

    private fun grantRuntimePermissions() {
        executeShell("pm grant ${targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}")
        if (Build.VERSION.SDK_INT >= 33) {
            executeShell("pm grant ${targetContext.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
    }

    private fun grantOverlayPermissionForEmulator() {
        executeShell("appops set ${targetContext.packageName} SYSTEM_ALERT_WINDOW allow")
        executeShell("cmd appops set ${targetContext.packageName} SYSTEM_ALERT_WINDOW allow")
        executeShell("appops set ${targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow")
        executeShell("cmd appops set ${targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow")
    }

    private fun enableAccessibilityServiceForEmulator() {
        val service = "${targetContext.packageName}/ai.droidlm.portal.DroidLMAccessibilityService"
        executeShell("settings put secure enabled_accessibility_services $service")
        executeShell("settings put secure accessibility_enabled 1")
    }

    private fun synthesizeVoiceSample(phrase: String): File {
        val file = File(targetContext.cacheDir, "workspace-file-op-${phrase.hashCode()}.wav")
        if (file.exists()) file.delete()
        val ready = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        var tts: TextToSpeech? = null
        tts = TextToSpeech(targetContext) { status ->
            initStatus = status
            ready.countDown()
        }
        assertTrue("TextToSpeech should initialize for real audio generation", ready.await(10, TimeUnit.SECONDS))
        assertTrue("TextToSpeech should be available for real command audio", initStatus == TextToSpeech.SUCCESS)
        val engine = tts ?: throw AssertionError("TextToSpeech was not initialized")
        engine.language = Locale.US
        val finished = CountDownLatch(1)
        var success = false
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                success = true
                finished.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finished.countDown()
            }
        })
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            engine.synthesizeToFile(phrase, null, file, "workspace-file-op")
        } else {
            @Suppress("DEPRECATION")
            engine.synthesizeToFile(phrase, hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to "workspace-file-op"), file.absolutePath)
        }
        assertTrue("TextToSpeech should accept synthesizeToFile", result == TextToSpeech.SUCCESS)
        assertTrue("TextToSpeech should finish writing real command audio", finished.await(20, TimeUnit.SECONDS))
        engine.shutdown()
        assertTrue("Real command audio should be non-empty", success && file.length() > 1024)
        return file
    }

    private fun executeShell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return output.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(250)
        }
        return condition()
    }

    private data class WorkspaceOperation(
        val transcript: String,
        val devicePath: String,
        val mimeType: String,
        val viewerPackage: String,
        val seedText: String,
        val expectedTexts: List<String>,
        val unsupportedAction: String,
        val expectedFileText: String? = null
    ) {
        fun planFieldsJson(): String = when (unsupportedAction) {
            "FORMAT_CURRENT_LINE_AS_BULLET" -> "\"fileUri\":\"file://$devicePath\",\"bulletPrefix\":\"- \","
            "REPLACE_CURRENT_DOCUMENT_TEXT" -> "\"fileUri\":\"file://$devicePath\",\"targetText\":\"draft\",\"replacementText\":\"final\","
            "APPEND_DOCUMENT_NOTE" -> "\"fileUri\":\"file://$devicePath\",\"note\":\"reviewed by DroidLM\","
            "SET_CURRENT_SHEET_CELL" -> "\"fileUri\":\"file://$devicePath\",\"value\":\"2026\","
            "ADD_SPREADSHEET_ROW" -> "\"fileUri\":\"file://$devicePath\",\"values\":[\"April\",\"120\",\"approved\"],"
            else -> "\"fileUri\":\"file://$devicePath\","
        }
    }

    companion object {
        private const val DEVICE_RUN_ROOT = "/sdcard/Documents/DroidLMTestRuns/file-ops"
        private const val DOCS_PACKAGE = "com.google.android.apps.docs.editors.docs"
        private const val SHEETS_PACKAGE = "com.google.android.apps.docs.editors.sheets"
    }
}
