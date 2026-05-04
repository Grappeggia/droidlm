package ai.droidlm.e2e

import ai.droidlm.DroidLMApp
import ai.droidlm.MainActivity
import ai.droidlm.overlay.FloatingControlOverlayService
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
            app.settingsRepository.updatePreferOfflineSpeechRecognition(false)
        }
        args.getString("openAiApiKey")?.takeIf { it.isNotBlank() }?.let { key ->
            runBlocking { app.settingsRepository.saveOpenAiApiKey(key) }
        }
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
                "Expected recognizer to enter listening state before injected audio begins; current state=${app.speechRecognitionController.state.value}",
                waitForListening(8_000)
            )
            executeShell("mkdir -p ${markerPath.substringBeforeLast('/')}")
            executeShell("touch $markerPath")

            assertTrue(
                "Expected injected mic audio to produce an Open Google Drive transcript or launch Drive; state=${app.speechRecognitionController.state.value}",
                waitForTranscriptOrDrive(45_000)
            )
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
