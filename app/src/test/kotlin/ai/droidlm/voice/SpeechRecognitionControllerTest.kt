package ai.droidlm.voice

import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.settings.SettingsRepository
import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class SpeechRecognitionControllerTest {
    @Test fun stopCurrentKeepsVoskActiveUntilTranscriptFinalizes() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val logs = ActionLogRepository()
        val diagnostics = SpeechDiagnosticsLogger(context, SettingsRepository(context), logs)
        val fakeVosk = FakeVoskRecognizer(context, logs, diagnostics)
        val controller = SpeechRecognitionController(context, logs, diagnostics, fakeVosk)

        val recognition = async {
            controller.recognizeCommand(
                preferOffline = true,
                maxDurationMs = 20_000L,
                languageTag = "en-US",
                diagnosticSessionId = "test-session"
            )
        }
        fakeVosk.ready.await()

        assertTrue(controller.stopCurrent())
        assertTrue(controller.state.value.isStopping)
        assertTrue(controller.state.value.isActive)

        fakeVosk.completeTranscript("open google docs")

        assertEquals("open google docs", recognition.await())
        assertEquals(1, fakeVosk.stopCalls)
        assertEquals(0, fakeVosk.cancelCalls)
        assertFalse(controller.state.value.isListening)
        assertFalse(controller.state.value.isStopping)
    }

    @Test fun voskAutoStopTransitionsToStoppingBeforeFinalTranscript() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val logs = ActionLogRepository()
        val diagnostics = SpeechDiagnosticsLogger(context, SettingsRepository(context), logs)
        val fakeVosk = FakeVoskRecognizer(context, logs, diagnostics)
        val controller = SpeechRecognitionController(context, logs, diagnostics, fakeVosk)

        val recognition = async {
            controller.recognizeCommand(
                preferOffline = true,
                maxDurationMs = 20_000L,
                languageTag = "en-US",
                diagnosticSessionId = "test-session"
            )
        }
        fakeVosk.ready.await()

        fakeVosk.signalStopping("silence_after_speech")

        assertFalse(controller.state.value.isListening)
        assertTrue(controller.state.value.isStopping)
        assertTrue(controller.state.value.isActive)

        fakeVosk.completeTranscript("open google docs")

        assertEquals("open google docs", recognition.await())
        assertFalse(controller.state.value.isStopping)
        assertEquals("open google docs", controller.state.value.finalTranscript)
    }

    private class FakeVoskRecognizer(
        context: Context,
        logs: ActionLogRepository,
        diagnostics: SpeechDiagnosticsLogger
    ) : VoskOfflineSpeechRecognizer(context, logs, diagnostics) {
        val ready = CompletableDeferred<Unit>()
        private val transcript = CompletableDeferred<String>()
        private lateinit var callbacks: Callbacks
        var stopCalls = 0
            private set
        var cancelCalls = 0
            private set

        fun completeTranscript(value: String) {
            transcript.complete(value)
        }

        fun signalStopping(reason: String) {
            callbacks.onStopping(reason)
        }

        override fun supportsLanguage(languageTag: String): Boolean = true

        override suspend fun recognizeCommand(
            languageTag: String,
            maxDurationMs: Long,
            diagnosticSessionId: String?,
            callbacks: Callbacks
        ): String {
            this.callbacks = callbacks
            callbacks.onStarting()
            callbacks.onReady()
            ready.complete(Unit)
            return transcript.await()
        }

        override fun stopCurrent(): Boolean {
            stopCalls += 1
            return true
        }

        override fun cancelCurrent(): Boolean {
            cancelCalls += 1
            transcript.completeExceptionally(IllegalStateException("Built-in offline speech cancelled."))
            return true
        }
    }
}
