package ai.droidlm.e2e

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class DroidLmEmulatorMicProbeE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val targetContext = instrumentation.targetContext

    @Before
    fun setUp() {
        executeShell("pm grant ${targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}")
        if (Build.VERSION.SDK_INT >= 33) {
            executeShell("pm grant ${targetContext.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
    }

    @Test
    fun injectedAudioProducesGuestMicEnergy() {
        val markerPath = args.getString("micAudioMarkerPath")
            ?: throw AssertionError("micAudioMarkerPath instrumentation arg is required")
        val sampleRate = 16_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding).coerceAtLeast(sampleRate)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, encoding, minBuffer)
        assertTrue("Expected AudioRecord to initialize", recorder.state == AudioRecord.STATE_INITIALIZED)

        val buffer = ByteArray(minBuffer)
        var peakRms = 0.0
        try {
            recorder.startRecording()
            executeShell("mkdir -p ${markerPath.substringBeforeLast('/')}")
            executeShell("touch $markerPath")
            val deadline = SystemClock.elapsedRealtime() + 6_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 1) peakRms = maxOf(peakRms, calculateRms(buffer, read))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        assertTrue(
            "Expected injected audio to reach guest microphone with meaningful energy; peakRms=$peakRms",
            peakRms >= 600.0
        )
    }

    private fun calculateRms(buffer: ByteArray, read: Int): Double {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < read) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xff)).toShort().toDouble()
            sum += sample * sample
            count++
            index += 2
        }
        return sqrt(sum / count.coerceAtLeast(1))
    }

    private fun executeShell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return output.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
    }
}
