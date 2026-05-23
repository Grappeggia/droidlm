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
            val device = UiDevice.getInstance(instrumentation)
            var lastFailure = "Overlay record permission flow did not complete"

            repeat(3) { attempt ->
                executeShell("pm revoke ${targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}")
                targetContext.startService(FloatingControlOverlayService.intent(targetContext, FloatingControlOverlayService.ACTION_STOP))
                SystemClock.sleep(1_000)

                targetContext.startService(FloatingControlOverlayService.intent(targetContext, FloatingControlOverlayService.ACTION_SHOW))
                val recordButton = waitForRecordButton(device, 45_000)
                if (recordButton == null) {
                    lastFailure = "Expected floating record button to be visible on attempt ${attempt + 1}"
                    return@repeat
                }

                recordButton.click()
                clickAllowMicrophonePermission(device)

                val handoffOrListening = waitForOverlayText(device, "Mic enabled. Tap record to speak", 45_000) || waitForSpeechListening(45_000)
                if (!handoffOrListening) {
                    lastFailure = "Expected overlay to report the mic grant handoff on attempt ${attempt + 1}"
                    return@repeat
                }
                if (waitForSpeechListening(45_000)) return@runBlocking

                lastFailure = "Repro: after granting mic permission from the overlay record button, DroidLM is not listening for 'Open Google Drive'; current state=${app.speechRecognitionController.state.value}"
            }

            throw AssertionError(lastFailure)
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
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val allowButton = findPermissionAllowButton(device)
            if (allowButton != null) {
                allowButton.click()
                return
            }
            SystemClock.sleep(250)
        }
        val allowButton = findPermissionAllowButton(device)
            ?: throw AssertionError("Expected Android microphone permission dialog")
        allowButton.click()
    }

    private fun waitForRecordButton(device: UiDevice, timeoutMs: Long) = run {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            device.findObject(By.desc(FloatingControlOverlayService.RECORD_BUTTON_CONTENT_DESCRIPTION))?.let { return@run it }
            targetContext.startService(FloatingControlOverlayService.intent(targetContext, FloatingControlOverlayService.ACTION_SHOW))
            device.waitForIdle()
            SystemClock.sleep(500)
        }
        device.findObject(By.desc(FloatingControlOverlayService.RECORD_BUTTON_CONTENT_DESCRIPTION))
    }

    private fun findPermissionAllowButton(device: UiDevice) = listOf(
        "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
        "com.android.permissioncontroller:id/permission_allow_button",
        "com.android.permissioncontroller:id/permission_allow_one_time_button",
        "com.google.android.permissioncontroller:id/permission_allow_foreground_only_button",
        "com.google.android.permissioncontroller:id/permission_allow_button",
        "com.google.android.permissioncontroller:id/permission_allow_one_time_button"
    ).asSequence().mapNotNull { resourceId ->
        device.findObject(By.res(resourceId))
    }.firstOrNull() ?: device.findObject(By.text(Pattern.compile("(?i)(While using the app|Allow|ALLOW|OK)")))

    private fun waitForOverlayText(device: UiDevice, text: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (device.hasObject(By.text(text))) return true
            SystemClock.sleep(250)
        }
        return device.hasObject(By.text(text))
    }

    private fun waitForSpeechListening(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (app.speechRecognitionController.state.value.isListening) return true
            SystemClock.sleep(250)
        }
        return app.speechRecognitionController.state.value.isListening
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
