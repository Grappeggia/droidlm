package ai.droidlm.e2e

import ai.droidlm.DroidLMApp
import ai.droidlm.MainActivity
import ai.droidlm.relay.RelayCallResult
import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class DroidLmDriveVoiceInvocationE2ETest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val app: DroidLMApp
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        grantRuntimePermissions()
        enableAccessibilityServiceForEmulator()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { UiDevice.getInstance(instrumentation).pressHome() }
        runCatching { server.shutdown() }
    }

    @Test
    fun voiceSampleInvocationOpensGoogleDrivePackage() = runBlocking {
        assertDrivePackageInstalled()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text":"DroidLM open the Google Drive App","durationMs":1320}""")
        )
        val voiceSample = createVoiceSampleFile()
        assertTrue("Voice sample should be non-empty", voiceSample.length() > 1024)

        val relayBaseUrl = server.url("/").toString()
        val transcription = when (val result = app.relayClient.transcribe(relayBaseUrl, voiceSample, "audio/wav")) {
            is RelayCallResult.Success -> result.value.text
            is RelayCallResult.Failure -> throw AssertionError("Transcription relay failed: ${result.message}")
        }
        assertEquals("DroidLM open the Google Drive App", transcription)
        val relayRequest = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("Expected /transcribe request", relayRequest)
        assertEquals("/transcribe", relayRequest!!.path)

        val execution = app.executor.executeTranscript(transcription)
        assertTrue("DroidLM should execute the OPEN_APP action: ${execution.message}", execution.success)

        val device = UiDevice.getInstance(instrumentation)
        assertTrue(
            "Expected Google Drive or its Google sign-in handoff to be foreground after voice invocation",
            waitForDriveLaunch(device, 10_000)
        )
        assertTrue(
            "Action log should include the parsed OPEN_APP action",
            app.actionLogRepository.logs.value.any { it.message.contains("OPEN_APP", ignoreCase = true) }
        )
    }

    private fun assertDrivePackageInstalled() {
        val launchIntent = targetContext.packageManager.getLaunchIntentForPackage(DRIVE_PACKAGE)
        assertNotNull(
            "Google Drive package is required for this E2E test. Run `./gradlew :driveStub:installDebug` on the emulator or install real Google Drive.",
            launchIntent
        )
    }

    private fun grantRuntimePermissions() {
        executeShell("pm grant ${targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}")
        if (Build.VERSION.SDK_INT >= 33) {
            executeShell("pm grant ${targetContext.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
    }

    private fun enableAccessibilityServiceForEmulator() {
        val service = "${targetContext.packageName}/ai.droidlm.portal.DroidLMAccessibilityService"
        executeShell("settings put secure enabled_accessibility_services $service")
        executeShell("settings put secure accessibility_enabled 1")
    }

    private fun executeShell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return output.use { descriptor ->
            FileInputStreamCompat.readDescriptor(descriptor.fileDescriptor)
        }
    }

    private fun waitForDriveLaunch(device: UiDevice, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isDriveLaunchVisible(device)) return true
            SystemClock.sleep(250)
        }
        return isDriveLaunchVisible(device)
    }

    private fun isDriveLaunchVisible(device: UiDevice): Boolean {
        if (device.currentPackageName == DRIVE_PACKAGE) return true
        if (device.currentPackageName != GOOGLE_PLAY_SERVICES_PACKAGE) return false
        val topDump = executeShell("dumpsys activity top")
        return topDump.contains("TASK") &&
            topDump.contains(DRIVE_PACKAGE) &&
            topDump.contains("auth.uiflows")
    }

    private fun createVoiceSampleFile(): File {
        val file = File(targetContext.cacheDir, "droidlm-open-google-drive-e2e.wav")
        if (file.exists()) file.delete()
        if (copyPackagedVoiceSample(file)) return file
        if (!synthesizeVoiceSample(file)) {
            generateDeterministicWavFallback(file)
        }
        return file
    }

    private fun copyPackagedVoiceSample(file: File): Boolean = runCatching {
        instrumentation.context.assets.open("droidlm_open_google_drive.wav").use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        file.length() > 1024
    }.getOrDefault(false)

    private fun synthesizeVoiceSample(file: File): Boolean {
        val ready = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        var tts: TextToSpeech? = null
        tts = TextToSpeech(targetContext) { status ->
            initStatus = status
            ready.countDown()
        }
        if (!ready.await(10, TimeUnit.SECONDS) || initStatus != TextToSpeech.SUCCESS) {
            tts?.shutdown()
            return false
        }

        val engine = tts ?: return false
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
            engine.synthesizeToFile(PHRASE, null, file, "droidlm-open-drive-e2e")
        } else {
            @Suppress("DEPRECATION")
            engine.synthesizeToFile(PHRASE, hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to "droidlm-open-drive-e2e"), file.absolutePath)
        }
        if (result != TextToSpeech.SUCCESS) {
            engine.shutdown()
            return false
        }
        val completed = finished.await(20, TimeUnit.SECONDS)
        engine.shutdown()
        return completed && success && file.length() > 1024
    }

    private fun generateDeterministicWavFallback(file: File) {
        val sampleRate = 16_000
        val durationSeconds = 3
        val samples = sampleRate * durationSeconds
        val pcm = ByteArrayOutputStream(samples * 2)
        for (index in 0 until samples) {
            val frequency = when (index / (sampleRate / 4)) {
                0, 4, 8 -> 220.0
                1, 5, 9 -> 330.0
                2, 6, 10 -> 440.0
                else -> 550.0
            }
            val envelope = if (index % (sampleRate / 4) < sampleRate / 20) 0.2 else 0.55
            val value = (sin(2.0 * PI * frequency * index / sampleRate) * Short.MAX_VALUE * envelope).toInt().toShort()
            pcm.write(value.toInt() and 0xff)
            pcm.write((value.toInt() shr 8) and 0xff)
        }
        val pcmBytes = pcm.toByteArray()
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray())
            out.write(intLe(36 + pcmBytes.size))
            out.write("WAVEfmt ".toByteArray())
            out.write(intLe(16))
            out.write(shortLe(1))
            out.write(shortLe(1))
            out.write(intLe(sampleRate))
            out.write(intLe(sampleRate * 2))
            out.write(shortLe(2))
            out.write(shortLe(16))
            out.write("data".toByteArray())
            out.write(intLe(pcmBytes.size))
            out.write(pcmBytes)
        }
    }

    private fun intLe(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun shortLe(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    private object FileInputStreamCompat {
        fun readDescriptor(fileDescriptor: java.io.FileDescriptor): String {
            return java.io.FileInputStream(fileDescriptor).bufferedReader().use { it.readText() }
        }
    }

    companion object {
        private const val DRIVE_PACKAGE = "com.google.android.apps.docs"
        private const val GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms"
        private const val PHRASE = "DroidLM open the Google Drive App"
    }
}
