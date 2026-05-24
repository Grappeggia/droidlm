package ai.droidlm.e2e

import ai.droidlm.DroidLMApp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
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

    @Ignore("Local-only private PCM fixtures are intentionally excluded from source control")
    @Test
    fun localPrivatePcmFixturesReproduceShortTranscripts() = runBlocking {
        val cases = InstrumentationRegistry.getArguments()
            .getString("privateVoskPcmFixtures")
            .orEmpty()
            .split(';')
            .mapNotNull { raw ->
                val parts = raw.split('=', limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
            }
        assumeTrue(
            "Private PCM fixtures must be supplied with -e privateVoskPcmFixtures assetName=expectedTranscript;...",
            cases.isNotEmpty() && cases.all { (assetName, _) -> androidTestAssetExists(assetName) }
        )

        cases.forEach { (assetName, expectedTranscript) ->
            val transcript = app.voskOfflineSpeechRecognizer.transcribePcm16Mono(
                pcm = instrumentation.context.assets.open(assetName).use { it.readBytes() },
                sampleRate = 16_000f,
                languageTag = "en-US",
                diagnosticSessionId = "vosk-private-fixture"
            )

            assertEquals(
                "Expected private PCM fixture to reproduce the short transcript",
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
