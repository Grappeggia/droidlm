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
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class DroidLmOverlayRecordPermissionE2ETest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val app: DroidLMApp
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        enableAccessibilityServiceForEmulator()
        grantOverlayPermissionForEmulator()
        if (Build.VERSION.SDK_INT >= 33) {
            executeShell("pm grant ${targetContext.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
    }

    @Test
    fun firstOverlayRecordTapAfterMicGrantDoesNotListenForOpenGoogleDrive() {
        runBlocking {
            executeShell("pm revoke ${targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}")


            targetContext.startService(FloatingControlOverlayService.intent(targetContext, FloatingControlOverlayService.ACTION_SHOW))
            val device = UiDevice.getInstance(instrumentation)
            val recordButton = device.wait(
                Until.findObject(By.desc(FloatingControlOverlayService.RECORD_BUTTON_CONTENT_DESCRIPTION)),
                5_000
            ) ?: throw AssertionError("Expected floating record button to be visible")

            recordButton.click()
            clickAllowMicrophonePermission(device)

            assertTrue(
                "Expected overlay to report the mic grant handoff",
                waitForOverlayText(device, "Mic enabled. Tap record to speak", 5_000)
            )
            assertTrue(
                "Repro: after granting mic permission from the overlay record button, DroidLM is not listening for 'Open Google Drive'; current state=${app.speechRecognitionController.state.value}",
                app.speechRecognitionController.state.value.isListening
            )
        }
    }

    private fun enableAccessibilityServiceForEmulator() {
        val service = "${targetContext.packageName}/ai.droidlm.portal.DroidLMAccessibilityService"
        executeShell("settings put secure enabled_accessibility_services $service")
        executeShell("settings put secure accessibility_enabled 1")
    }

    private fun grantOverlayPermissionForEmulator() {
        executeShell("appops set ${targetContext.packageName} SYSTEM_ALERT_WINDOW allow")
        executeShell("cmd appops set ${targetContext.packageName} SYSTEM_ALERT_WINDOW allow")
    }

    private fun clickAllowMicrophonePermission(device: UiDevice) {
        val allowButton = device.wait(
            Until.findObject(By.text(Pattern.compile("(?i)(While using the app|Allow|ALLOW|OK)"))),
            5_000
        ) ?: throw AssertionError("Expected Android microphone permission dialog")
        allowButton.click()
    }

    private fun waitForOverlayText(device: UiDevice, text: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (device.hasObject(By.text(text))) return true
            SystemClock.sleep(250)
        }
        return device.hasObject(By.text(text))
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
