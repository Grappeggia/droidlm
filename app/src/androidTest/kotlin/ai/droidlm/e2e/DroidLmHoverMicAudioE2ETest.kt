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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
