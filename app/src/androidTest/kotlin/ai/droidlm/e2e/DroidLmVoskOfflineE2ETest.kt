package ai.droidlm.e2e

import ai.droidlm.DroidLMApp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class DroidLmVoskOfflineE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: DroidLMApp
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun bundledVoskModelTranscribesOpenGoogleDriveAudio() = runBlocking {
        val pcm = readPcm16MonoAsset("droidlm_open_google_drive.wav")
        val transcript = app.voskOfflineSpeechRecognizer.transcribePcm16Mono(
            pcm = pcm,
            sampleRate = 16_000f,
            languageTag = "en-US",
            diagnosticSessionId = "vosk-offline-e2e"
        )

        assertTrue(
            "Expected bundled Vosk model to produce a non-empty transcript for open-google-drive audio, got '$transcript'",
            transcript.isNotBlank()
        )
    }

    @Test
    fun sharedSupportLogPcmReproducesShortTranscripts() = runBlocking {
        val cases = listOf(
            "private-vosk-fixture-a.pcm" to "the",
            "private-vosk-fixture-b.pcm" to "opus",
            "private-vosk-fixture.pcm" to "open"
        )
        assumeTrue(
            "Private support-log PCM fixtures are not packaged in androidTest assets.",
            cases.all { (assetName, _) -> androidTestAssetExists(assetName) }
        )

        cases.forEach { (assetName, expectedTranscript) ->
            val transcript = app.voskOfflineSpeechRecognizer.transcribePcm16Mono(
                pcm = instrumentation.context.assets.open(assetName).use { it.readBytes() },
                sampleRate = 16_000f,
                languageTag = "en-US",
                diagnosticSessionId = "vosk-support-log-$expectedTranscript"
            )

            assertEquals(
                "Expected shared support-log PCM $assetName to reproduce the short transcript",
                expectedTranscript,
                transcript.lowercase().trim()
            )
        }
    }

    private fun androidTestAssetExists(assetName: String): Boolean =
        instrumentation.context.assets.list("").orEmpty().contains(assetName)

    private fun readPcm16MonoAsset(assetName: String): ByteArray {
        val bytes = instrumentation.context.assets.open(assetName).use { it.readBytes() }
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val dataStart = offset + 8
            if (chunkId == "data") return bytes.copyOfRange(dataStart, dataStart + chunkSize)
            offset = dataStart + chunkSize + (chunkSize % 2)
        }
        throw IllegalArgumentException("WAV asset has no data chunk: $assetName")
    }
}
