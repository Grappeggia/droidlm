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

    private fun ZipFile.readEntry(name: String): String {
        val entry = getEntry(name) ?: throw AssertionError("Missing zip entry $name")
        return getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
