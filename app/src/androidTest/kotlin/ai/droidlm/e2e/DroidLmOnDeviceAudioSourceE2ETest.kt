package ai.droidlm.e2e

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class DroidLmOnDeviceAudioSourceE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Before
    fun setUp() {
        executeShell("pm grant ${targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}")
    }

    @Test
    fun onDeviceRecognizerTranscribesProvidedOpenGoogleDriveAudio() = runBlocking {
        assumeTrue("Android SpeechRecognizer must be available", SpeechRecognizer.isRecognitionAvailable(targetContext))
        assumeTrue(
            "RECORD_AUDIO permission must be granted",
            ContextCompat.checkSelfPermission(targetContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
        val audioPath = args.getString("audioSourcePath")
            ?: throw AssertionError("audioSourcePath instrumentation arg is required")
        val transcript = recognizePcmFile(
            audioFile = File(audioPath),
            preferOffline = args.getString("preferOfflineSpeechRecognition")?.toBoolean() ?: false
        )

        assertTrue(
            "Expected on-device recognizer to transcribe 'Open Google Drive', got '$transcript'",
            transcript.lowercase().contains("open") && transcript.lowercase().contains("drive")
        )
    }

    private suspend fun recognizePcmFile(audioFile: File, preferOffline: Boolean): String = withTimeout(30_000L) {
        val result = CompletableDeferred<Result<String>>()
        val ready = CompletableDeferred<Unit>()
        lateinit var recognizer: SpeechRecognizer
        val pipe = ParcelFileDescriptor.createPipe()
        val readPfd = pipe[0]
        val writePfd = pipe[1]
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { ready.complete(Unit) }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                result.complete(Result.failure(IllegalStateException("SpeechRecognizer error $error")))
            }

            override fun onResults(results: Bundle?) {
                val transcript = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }
                    ?.trim()
                    .orEmpty()
                result.complete(Result.success(transcript))
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                val transcript = segmentResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }
                    ?.trim()
                    .orEmpty()
                if (transcript.isNotBlank()) result.complete(Result.success(transcript))
            }

            override fun onEndOfSegmentedSession() {
                if (!result.isCompleted) result.complete(Result.success(""))
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readPfd)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
        }
        instrumentation.runOnMainSync {
            recognizer = SpeechRecognizer.createSpeechRecognizer(targetContext)
            recognizer.setRecognitionListener(listener)
            recognizer.startListening(intent)
        }
        val writer = Thread {
            runBlocking { ready.await() }
            SystemClock.sleep(100)
            ParcelFileDescriptor.AutoCloseOutputStream(writePfd).use { output ->
                FileInputStream(audioFile).use { input ->
                    val buffer = ByteArray(3_200)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        output.flush()
                        SystemClock.sleep(100)
                    }
                }
            }
        }.apply { start() }
        try {
            result.await().getOrThrow()
        } finally {
            SystemClock.sleep(100)
            instrumentation.runOnMainSync {
                runCatching { recognizer.destroy() }
                runCatching { readPfd.close() }
                runCatching { writePfd.close() }
                runCatching { writer.interrupt() }
            }
        }
    }

    private fun executeShell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return output.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
    }
}
