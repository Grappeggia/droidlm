package ai.droidlm.e2e

import ai.droidlm.DroidLMApp
import ai.droidlm.MainActivity
import ai.droidlm.overlay.FloatingControlOverlayService
import ai.droidlm.overlay.OverlayStatusFormatter
import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class DroidLmHoverMicAudioE2ETest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val targetContext = instrumentation.targetContext
    private val app: DroidLMApp
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        enableAccessibilityServiceForEmulator()
        grantOverlayPermissionForEmulator()
        executeShell("pm grant ${targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}")
        if (Build.VERSION.SDK_INT >= 33) {
            executeShell("pm grant ${targetContext.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        runBlocking {
            app.actionLogRepository.clear()
            app.executor.cancelActive()
            app.speechRecognitionController.clear()
            val preferOffline = args.getString("preferOfflineSpeechRecognition")?.toBooleanStrictOrNull() ?: true
            app.settingsRepository.updatePreferOfflineSpeechRecognition(preferOffline)
        }
        args.getString("openAiApiKey")?.takeIf { it.isNotBlank() }?.let { key ->
            runBlocking { app.settingsRepository.saveOpenAiApiKey(key) }
        } ?: runBlocking { app.settingsRepository.clearOpenAiApiKey() }
    }

    @Test
    fun hoverRecordCapturesInjectedOpenGoogleDriveAudio() {
        runBlocking {
            val markerPath = args.getString("micAudioMarkerPath")
                ?: throw AssertionError("micAudioMarkerPath instrumentation arg is required")
            targetContext.startService(FloatingControlOverlayService.intent(targetContext, FloatingControlOverlayService.ACTION_SHOW))
            val device = UiDevice.getInstance(instrumentation)
            val recordButton = device.wait(
                Until.findObject(By.desc(FloatingControlOverlayService.RECORD_BUTTON_CONTENT_DESCRIPTION)),
                5_000
            ) ?: throw AssertionError("Expected floating record button to be visible")

            recordButton.click()
            assertTrue(
                "Expected recognizer to become active quickly after tap; current state=${app.speechRecognitionController.state.value}",
                waitForActive(750)
            )
            assertRecordButtonStaysActive(device, 2_000)
            assertTrue(
                "Expected recognizer to enter listening state before injected audio begins; current state=${app.speechRecognitionController.state.value}",
                waitForListening(3_000)
            )
            executeShell("mkdir -p ${markerPath.substringBeforeLast('/')}")
            executeShell("touch $markerPath")

            val hasOpenAiKey = args.getString("openAiApiKey")?.isNotBlank() == true
            if (hasOpenAiKey) {
                assertTrue(
                    "Expected injected mic audio to produce a GPT plan or launch Drive; state=${app.speechRecognitionController.state.value}; execution=${app.executor.uiState.value}",
                    waitForPlanOrDrive(45_000)
                )
                assertFalse(
                    "OpenAI request used an unsupported parameter: ${app.actionLogRepository.logs.value}",
                    hasUnsupportedOpenAiParameterError()
                )
            } else {
                assertTrue(
                    "Expected injected mic audio to produce an Open Google Drive transcript or launch Drive; state=${app.speechRecognitionController.state.value}",
                    waitForTranscriptOrDrive(45_000)
                )
            }
        }
    }

    @Test
    fun hoverRecordOpenGoogleDriveAudioCapturesEnoughPcm() {
        runBlocking {
            val markerPath = args.getString("micAudioMarkerPath")
                ?: throw AssertionError("micAudioMarkerPath instrumentation arg is required")
            val recordHoldMs = longArg("captureRecordHoldMs", 5_000L)
            val injectBeforeListening = booleanArg("captureInjectBeforeListening", false)
            val assertMetrics = booleanArg("captureAssertMetrics", true)
            val minAudioDurationMs = longArg("captureMinAudioDurationMs", 2_200L)
            val minCaptureEfficiency = doubleArg("captureMinEfficiency", 0.65)
            val maxReadGapMsAllowed = longArg("captureMaxReadGapMs", 1_000L)
            val maxCompletionLatencyMs = longArg("captureMaxCompletionLatencyMs", 8_000L)
            val cpuStressThreads = intArg("captureCpuStressThreads", 0)
            val memoryPressureMb = intArg("captureMemoryPressureMb", 0)
            val stress = startCpuStressThreads(cpuStressThreads)
            val memoryPressure = allocateMemoryPressure(memoryPressureMb)
            try {
                app.settingsRepository.updateDebugLoggingEnabled(true)
                assertTrue("Expected debug logging to enable for capture regression diagnostics", waitForDebugLoggingEnabled(5_000))
                app.speechDiagnosticsLogger.clear()
                SystemClock.sleep(250)
                app.actionLogRepository.clear()
                app.executor.cancelActive()
                app.speechRecognitionController.clear()

                targetContext.startService(FloatingControlOverlayService.intent(targetContext, FloatingControlOverlayService.ACTION_SHOW))
                val device = UiDevice.getInstance(instrumentation)
                val recordButton = device.wait(
                    Until.findObject(By.desc(FloatingControlOverlayService.RECORD_BUTTON_CONTENT_DESCRIPTION)),
                    5_000
                ) ?: throw AssertionError("Expected floating record button to be visible")

                recordButton.click()
                assertTrue(
                    "Expected open-google-drive capture regression run to activate speech recognition quickly; state=${app.speechRecognitionController.state.value}",
                    waitForActive(750)
                )
                if (injectBeforeListening) {
                    executeShell("mkdir -p ${markerPath.substringBeforeLast('/')}")
                    executeShell("touch $markerPath")
                }
                assertRecordButtonStaysActive(device, 2_000)
                assertTrue(
                    "Expected open-google-drive capture regression run to reach listening state before injected audio completes; state=${app.speechRecognitionController.state.value}",
                    waitForListening(20_000)
                )
                if (!injectBeforeListening) {
                    executeShell("mkdir -p ${markerPath.substringBeforeLast('/')}")
                    executeShell("touch $markerPath")
                }
                SystemClock.sleep(recordHoldMs)

                val stopButton = device.wait(
                    Until.findObject(By.desc(FloatingControlOverlayService.RECORD_BUTTON_CONTENT_DESCRIPTION)),
                    2_000
                ) ?: throw AssertionError("Expected floating record button to remain visible for stop tap")
                stopButton.click()

                assertTrue(
                    "Expected speech session to finish after stop tap; speech=${app.speechRecognitionController.state.value}; execution=${app.executor.uiState.value}; events=${diagnosticEventSummary()}",
                    waitForSpeechSessionToFinish(45_000)
                )

                val events = readDiagnosticEvents()
                val primarySessionEvents = latestCompletedSessionEvents(events)
                val capture = primarySessionEvents.lastOrNull { it.optString("event") == "audio_capture_summary" }
                    ?: throw AssertionError("Expected audio_capture_summary for open-google-drive capture run. Events=$primarySessionEvents")
                val finalEvent = primarySessionEvents.lastOrNull { it.optString("event") == "vosk_final" }
                val finalTranscript = finalEvent
                    ?.optString("transcript")
                    ?.lowercase()
                    ?.trim()
                    .orEmpty()
                val stopRequestEvent = primarySessionEvents.lastOrNull { it.optString("event") == "vosk_stop_requested" }
                    ?: primarySessionEvents.lastOrNull { it.optString("event") == "audio_capture_stop_reason" }
                val completionLatencyMs = if (stopRequestEvent != null && finalEvent != null) {
                    (finalEvent.optLong("tMs") - stopRequestEvent.optLong("tMs")).coerceAtLeast(0L)
                } else {
                    Long.MAX_VALUE
                }
                val audioDurationMs = capture.optLong("audioDurationMs")
                val wallDurationMs = capture.optLong("wallDurationMs")
                val captureEfficiency = capture.optDouble("captureEfficiency")
                val maxReadGapMs = capture.optLong("maxReadGapMs")
                val queueOverflowCount = capture.optLong("queueOverflowCount")
                val summary = JSONObject()
                    .put("audioDurationMs", audioDurationMs)
                    .put("wallDurationMs", wallDurationMs)
                    .put("captureEfficiency", captureEfficiency)
                    .put("maxReadGapMs", maxReadGapMs)
                    .put("readCount", capture.optLong("readCount"))
                    .put("slowReadGapCount", capture.optLong("slowReadGapCount"))
                    .put("queueOverflowCount", queueOverflowCount)
                    .put("maxQueueDepth", capture.optLong("maxQueueDepth"))
                    .put("queueCapacity", capture.optLong("queueCapacity"))
                    .put("discardedAudioDurationMs", capture.optLong("discardedAudioDurationMs"))
                    .put("postStopDrainWallMs", capture.optLong("postStopDrainWallMs"))
                    .put("completionLatencyMs", completionLatencyMs)
                    .put("maxCompletionLatencyMs", maxCompletionLatencyMs)
                    .put("transcript", finalTranscript)
                    .put("cpuStressThreads", cpuStressThreads)
                    .put("memoryPressureMb", memoryPressureMb)
                    .put("recordHoldMs", recordHoldMs)
                    .put("injectBeforeListening", injectBeforeListening)
                    .put("memoryPressureBytes", memoryPressure?.size ?: 0)
                println("DROIDLM_CAPTURE_METRICS $summary")

                if (assertMetrics) {
                    assertTrue(
                        "Expected live emulator mic capture to retain most audio, avoid queue overflow, and finish promptly after stop; metrics=$summary; capture=$capture; events=${compactEvents(primarySessionEvents)}",
                        audioDurationMs >= minAudioDurationMs &&
                            captureEfficiency >= minCaptureEfficiency &&
                            maxReadGapMs < maxReadGapMsAllowed &&
                            queueOverflowCount == 0L &&
                            completionLatencyMs <= maxCompletionLatencyMs
                    )
                }
            } finally {
                stress.close()
            }
        }
    }

    @Test
    fun hoverRecordSupportLogAudioReproducesAmbiguousOpenRegression() {
        runBlocking {
            val markerPath = args.getString("micAudioMarkerPath")
                ?: throw AssertionError("micAudioMarkerPath instrumentation arg is required")
            app.settingsRepository.updateDebugLoggingEnabled(true)
            assertTrue("Expected debug logging to enable for support-log regression capture", waitForDebugLoggingEnabled(5_000))
            app.speechDiagnosticsLogger.clear()
            SystemClock.sleep(250)
            app.actionLogRepository.clear()
            app.executor.cancelActive()
            app.speechRecognitionController.clear()

            targetContext.startService(FloatingControlOverlayService.intent(targetContext, FloatingControlOverlayService.ACTION_SHOW))
            val device = UiDevice.getInstance(instrumentation)
            val recordButton = device.wait(
                Until.findObject(By.desc(FloatingControlOverlayService.RECORD_BUTTON_CONTENT_DESCRIPTION)),
                5_000
            ) ?: throw AssertionError("Expected floating record button to be visible")

            recordButton.click()
            assertTrue(
                "Expected support-log regression run to activate speech recognition quickly; state=${app.speechRecognitionController.state.value}",
                waitForActive(750)
            )
            assertRecordButtonStaysActive(device, 2_000)
            assertTrue(
                "Expected support-log regression run to reach listening state before injecting audio; state=${app.speechRecognitionController.state.value}",
                waitForListening(3_000)
            )
            executeShell("mkdir -p ${markerPath.substringBeforeLast('/')}")
            executeShell("touch $markerPath")
            SystemClock.sleep(5_000)

            val stopButton = device.wait(
                Until.findObject(By.desc(FloatingControlOverlayService.RECORD_BUTTON_CONTENT_DESCRIPTION)),
                2_000
            ) ?: throw AssertionError("Expected floating record button to remain visible for stop tap")
            stopButton.click()

            assertTrue(
                "Expected May 10 support-log audio to settle into a failed short-transcript outcome after the stop tap; speech=${app.speechRecognitionController.state.value}; execution=${app.executor.uiState.value}; logs=${app.actionLogRepository.logs.value}",
                waitForSpeechSessionToFinish(30_000)
            )

            val events = readDiagnosticEvents()
            val speechSessions = completedSpeechSessions(events)
            assertTrue(
                "Expected support-log regression run to record at least one completed speech session. Events=$events",
                speechSessions.isNotEmpty()
            )

            val sessionSummaries = speechSessions.joinToString(prefix = "[", postfix = "]") { session -> compactEvents(session) }
            val offlineFallbackSessions = speechSessions.filter { session ->
                hasSessionEvent(session, "prefer_offline_vosk_direct") || hasSessionEvent(session, "vosk_fallback_started")
            }
            assertTrue(
                "Expected support-log regression sessions to route through offline-preferred Vosk path. Sessions=$sessionSummaries",
                offlineFallbackSessions.isNotEmpty()
            )

            val shortOrBlankTranscriptSessions = speechSessions.filter { session ->
                val transcript = lastTranscriptOrBlank(session).lowercase().trim()
                transcript.isBlank() || (transcript.length <= 5 && !transcript.contains("google") && !transcript.contains("docs") && !transcript.contains("sheets"))
            }
            assertTrue(
                "Expected support-log regression speech sessions to remain blank/short and not resolve full app names. Sessions=$sessionSummaries",
                shortOrBlankTranscriptSessions.size == speechSessions.size
            )

            val voskFailureSessions = speechSessions.filter { session ->
                hasSessionEvent(session, "vosk_error") && hasSessionEvent(session, "vosk_fallback_failed")
            }
            if (voskFailureSessions.isNotEmpty()) {
                assertTrue(
                    "Expected unsupported offline-fallback sessions to avoid launching an app package despite bad transcript. Sessions=${voskFailureSessions.joinToString { compactEvents(it) }}",
                    voskFailureSessions.all { session ->
                        val transcript = lastTranscriptOrBlank(session).lowercase().trim()
                        transcript.isBlank() || transcript.length <= 5
                    }
                )
                assertTrue(
                    "Expected offline fallback failures in support-log replay to surface push-to-talk failure telemetry. Sessions=${voskFailureSessions.joinToString { compactEvents(it) }}",
                    voskFailureSessions.all { session -> hasSessionEvent(session, "push_to_talk_failed") || hasSessionEvent(session, "push_to_talk_execution_failed") }
                )
            }

            val explicitStopSessionPairs = speechSessions.filter { session ->
                hasSessionEvent(session, "vosk_stop_requested") && hasSessionEvent(session, "stop_current_requested")
            }
            explicitStopSessionPairs.forEach { primaryStopSession ->
                val stopServiceSessionId = events
                    .firstOrNull { event -> event.optString("event") == "foreground_stop_listening_requested" }
                    ?.optString("sessionId")
                assertTrue(
                    "Expected foreground stop request to be logged in a separate diagnostic session when the speech session requests explicit stop. Events=$events",
                    stopServiceSessionId != null && stopServiceSessionId !=
                        primaryStopSession.lastOrNull { event -> event.optString("sessionId").isNotBlank() }?.optString("sessionId")
                )
            }

            assertFalse(
                "Support-log regression should not accidentally foreground Google Docs/Drive packages; currentPackage=${device.currentPackageName}",
                device.currentPackageName?.startsWith("com.google.android.apps.docs") == true
            )
        }
    }

    private fun waitForActive(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (app.speechRecognitionController.state.value.isActive) return true
            SystemClock.sleep(50)
        }
        return app.speechRecognitionController.state.value.isActive
    }

    private fun assertRecordButtonStaysActive(device: UiDevice, timeoutMs: Long) {
        val inactiveText = OverlayStatusFormatter.recordButton(false, "Idle")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val button = device.findObject(By.desc(FloatingControlOverlayService.RECORD_BUTTON_CONTENT_DESCRIPTION))
                ?: throw AssertionError("Record button disappeared during microphone startup")
            if (button.text == inactiveText) throw AssertionError("Record button showed inactive state during microphone startup")
            SystemClock.sleep(100)
        }
    }

    private fun waitForListening(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (app.speechRecognitionController.state.value.isListening) return true
            SystemClock.sleep(100)
        }
        return app.speechRecognitionController.state.value.isListening
    }

    private fun waitForDebugLoggingEnabled(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (app.speechDiagnosticsLogger.isEnabledNow()) return true
            SystemClock.sleep(50)
        }
        return app.speechDiagnosticsLogger.isEnabledNow()
    }

    private fun waitForPartialTranscript(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (app.speechRecognitionController.state.value.partialTranscript.isNotBlank()) return true
            SystemClock.sleep(100)
        }
        return app.speechRecognitionController.state.value.partialTranscript.isNotBlank()
    }

    private fun waitForSpeechSessionToFinish(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val speech = app.speechRecognitionController.state.value
            val execution = app.executor.uiState.value
            val settled = !speech.isActive && !speech.isListening && !speech.isStarting &&
                (speech.finalTranscript.isNotBlank() || speech.errorMessage?.isNotBlank() == true || execution.status == "Error")
            if (settled) return true
            SystemClock.sleep(100)
        }
        val speech = app.speechRecognitionController.state.value
        val execution = app.executor.uiState.value
        return !speech.isActive && !speech.isListening && !speech.isStarting &&
            (speech.finalTranscript.isNotBlank() || speech.errorMessage?.isNotBlank() == true || execution.status == "Error")
    }

    private fun readDiagnosticEvents(): List<JSONObject> {
        val exported = runBlocking { app.speechDiagnosticsLogger.exportSnapshot() }
            ?: throw AssertionError("Expected support-log regression run to produce an exported diagnostics snapshot")
        return exported.readLines()
            .filter { it.isNotBlank() }
            .map(::JSONObject)
    }

    private val speechSessionTerminalEvents: Set<String> = setOf(
        "audio_capture_summary",
        "vosk_final",
        "vosk_error",
        "vosk_fallback_failed",
        "push_to_talk_execution_failed",
        "push_to_talk_failed",
        "session_end"
    )

    private val speechSessionRecognitionEvents: Set<String> = setOf(
        "recognize_command_start",
        "prefer_offline_vosk_direct",
        "vosk_fallback_started",
        "vosk_fallback_failed",
        "vosk_final",
        "vosk_error",
        "vosk_stop_requested",
        "push_to_talk_started",
        "push_to_talk_execution_failed",
        "push_to_talk_failed"
    )

    private fun completedSpeechSessions(events: List<JSONObject>): List<List<JSONObject>> {
        val bySession = linkedMapOf<String, MutableList<JSONObject>>()

        events.forEach { event ->
            val sessionId = event.optString("sessionId")
            if (sessionId.isNotBlank()) {
                bySession.getOrPut(sessionId) { mutableListOf() }.add(event)
            }
        }

        return bySession.values.filter { session ->
            speechSessionRecognitionEvents.any { eventName -> hasSessionEvent(session, eventName) } &&
                speechSessionTerminalEvents.any { eventName -> hasSessionEvent(session, eventName) }
        }
    }

    private fun hasSessionEvent(session: List<JSONObject>, eventName: String): Boolean =
        session.any { it.optString("event") == eventName }

    private fun hasSessionEvent(session: List<JSONObject>, eventNames: Set<String>): Boolean =
        session.any { eventNames.contains(it.optString("event")) }

    private fun lastTranscriptOrBlank(session: List<JSONObject>): String {
        return session.lastOrNull { it.optString("event") == "vosk_final" }
            ?.optString("transcript")
            ?.lowercase()
            ?.trim()
            ?: ""
    }

    private fun latestCompletedSessionEvents(events: List<JSONObject>): List<JSONObject> {
        val sessionId = events.lastOrNull { event ->
            event.optString("event") in setOf("audio_capture_summary", "vosk_final", "push_to_talk_execution_failed", "push_to_talk_failed")
        }?.optString("sessionId")
            ?: throw AssertionError("Expected a completed speech session in diagnostics. Events=$events")
        return events.filter { event -> event.optString("sessionId") == sessionId }
    }

    private fun diagnosticEventSummary(): String = runCatching {
        compactEvents(readDiagnosticEvents())
    }.getOrElse { error ->
        "<diagnostics unavailable: ${error.message}>"
    }

    private fun compactEvents(events: List<JSONObject>): String = events.joinToString(prefix = "[", postfix = "]") { event ->
        val fields = listOf(
            event.optString("event"),
            event.optString("sessionId"),
            event.optString("transcript"),
            event.optString("message"),
            event.optString("errorCode"),
            event.optString("audioDurationMs"),
            event.optString("wallDurationMs"),
            event.optString("captureEfficiency"),
            event.optString("maxReadGapMs")
        ).filter { it.isNotBlank() }
        fields.joinToString("/")
    }

    private fun intArg(name: String, defaultValue: Int): Int =
        args.getString(name)?.toIntOrNull() ?: defaultValue

    private fun longArg(name: String, defaultValue: Long): Long =
        args.getString(name)?.toLongOrNull() ?: defaultValue

    private fun doubleArg(name: String, defaultValue: Double): Double =
        args.getString(name)?.toDoubleOrNull() ?: defaultValue

    private fun booleanArg(name: String, defaultValue: Boolean): Boolean =
        args.getString(name)?.toBooleanStrictOrNull() ?: defaultValue

    private fun startCpuStressThreads(threadCount: Int): AutoCloseable {
        if (threadCount <= 0) return AutoCloseable { }
        val running = AtomicBoolean(true)
        val workers = (1..threadCount).map { index ->
            Thread({
                var value = index.toLong()
                while (running.get()) {
                    value = value * 1_103_515_245L + 12_345L
                    value = value xor (value ushr 17)
                    if (value == Long.MIN_VALUE) Thread.yield()
                }
            }, "droidlm-capture-stress-$index").apply {
                isDaemon = true
                start()
            }
        }
        return AutoCloseable {
            running.set(false)
            workers.forEach { worker -> runCatching { worker.join(1_000L) } }
        }
    }

    private fun allocateMemoryPressure(megabytes: Int): ByteArray? {
        if (megabytes <= 0) return null
        val bytes = ByteArray(megabytes * 1024 * 1024)
        var index = 0
        while (index < bytes.size) {
            bytes[index] = (index and 0xff).toByte()
            index += 4096
        }
        return bytes
    }

    private fun diagnosticsLogFile(): File = File(targetContext.cacheDir, "droidlm-diagnostics/speech-diagnostics.jsonl")

    private fun waitForTranscriptOrDrive(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val transcript = app.speechRecognitionController.state.value.finalTranscript.lowercase()
            if (transcript.contains("open") && transcript.contains("drive")) return true
            if (currentPackage().contains("com.google.android.apps.docs")) return true
            SystemClock.sleep(250)
        }
        return false
    }

    private fun waitForPlanOrDrive(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasUnsupportedOpenAiParameterError()) return false
            if (app.executor.pendingPlan.value != null) return true
            if (currentPackage().contains("com.google.android.apps.docs")) return true
            SystemClock.sleep(250)
        }
        return app.executor.pendingPlan.value != null || currentPackage().contains("com.google.android.apps.docs")
    }

    private fun hasUnsupportedOpenAiParameterError(): Boolean = app.actionLogRepository.logs.value.any { entry ->
        entry.message.contains("Unsupported parameter", ignoreCase = true) ||
            entry.message.contains("max_tokens", ignoreCase = true) ||
            entry.details?.contains("unsupported_parameter", ignoreCase = true) == true
    }

    private fun currentPackage(): String =
        executeShell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1")

    private fun enableAccessibilityServiceForEmulator() {
        val service = "${targetContext.packageName}/ai.droidlm.portal.DroidLMAccessibilityService"
        executeShell("settings put secure enabled_accessibility_services $service")
        executeShell("settings put secure accessibility_enabled 1")
    }

    private fun grantOverlayPermissionForEmulator() {
        executeShell("appops set ${targetContext.packageName} SYSTEM_ALERT_WINDOW allow")
        executeShell("cmd appops set ${targetContext.packageName} SYSTEM_ALERT_WINDOW allow")
    }

    private fun executeShell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return output.use { descriptor ->
            FileInputStreamCompat.readDescriptor(descriptor.fileDescriptor)
        }
    }

    private object FileInputStreamCompat {
        fun readDescriptor(fileDescriptor: java.io.FileDescriptor): String {
            return java.io.FileInputStream(fileDescriptor).bufferedReader().use { it.readText() }
        }
    }
}
