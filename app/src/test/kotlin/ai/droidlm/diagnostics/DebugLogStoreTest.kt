package ai.droidlm.diagnostics

import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.settings.SettingsRepository
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
class DebugLogStoreTest {
    @Test fun bundleIncludesIssueDescriptionWhenProvided() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.cacheDir, "droidlm-debug-logs").deleteRecursively()
        File(context.cacheDir, "droidlm-debug-exports").deleteRecursively()
        File(context.cacheDir, "droidlm-diagnostics").deleteRecursively()

        val settingsRepository = SettingsRepository(context)
        val actionLogs = ActionLogRepository()
        val diagnosticsLogger = SpeechDiagnosticsLogger(context, settingsRepository, actionLogs)
        val debugLogStore = DebugLogStore(context, settingsRepository, actionLogs, diagnosticsLogger)
        val description = "Speech stopped after I tapped record."

        settingsRepository.updateDebugLoggingEnabled(true)
        diagnosticsLogger.setEnabled(true)
        assertNotNull(debugLogStore.retainText("state", "test", "diagnostic body"))

        val bundle = debugLogStore.createBundle(description)

        assertNotNull(bundle)
        ZipFile(bundle!!).use { zip ->
            assertEquals("$description\n", zip.readEntry("issue-description.txt"))
            val manifest = zip.readEntry("manifest.json")
            assertTrue(manifest.contains("\"path\":\"issue-description.txt\""))
            assertTrue(manifest.contains("\"category\":\"issue\""))
        }
    }

    @Test fun manifestRecordsPrivacyMetadataForRetainedSensitiveCategories() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.cacheDir, "droidlm-debug-logs").deleteRecursively()
        File(context.cacheDir, "droidlm-debug-exports").deleteRecursively()
        File(context.cacheDir, "droidlm-diagnostics").deleteRecursively()

        val settingsRepository = SettingsRepository(context)
        val actionLogs = ActionLogRepository()
        val diagnosticsLogger = SpeechDiagnosticsLogger(context, settingsRepository, actionLogs)
        val debugLogStore = DebugLogStore(context, settingsRepository, actionLogs, diagnosticsLogger)

        settingsRepository.updateDebugLoggingEnabled(true)
        diagnosticsLogger.setEnabled(true)
        assertNotNull(debugLogStore.retainText("audio", "test-audio", "pcm bytes", extension = "pcm"))
        assertNotNull(debugLogStore.retainText("llm", "plan-preview", "{}", extension = "json"))

        val bundle = debugLogStore.createBundle("network timeout")

        assertNotNull(bundle)
        ZipFile(bundle!!).use { zip ->
            val manifest = zip.readEntry("manifest.json")
            assertTrue(manifest.contains("\"includesRawAudio\":true"))
            assertTrue(manifest.contains("\"includesLlmTraces\":true"))
            assertTrue(manifest.contains("\"raw_microphone_audio\""))
            assertTrue(manifest.contains("\"llm_request_response_trace\""))
            assertTrue(manifest.contains("\"apiKeysIncluded\":false"))
        }
    }

    private fun ZipFile.readEntry(name: String): String {
        val entry = getEntry(name) ?: throw AssertionError("Missing zip entry $name")
        return getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
