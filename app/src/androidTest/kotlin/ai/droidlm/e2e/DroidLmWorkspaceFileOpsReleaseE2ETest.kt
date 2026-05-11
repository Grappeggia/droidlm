package ai.droidlm.e2e

import ai.droidlm.DroidLMApp
import ai.droidlm.intent.DroidLmAction
import android.os.Environment
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class DroidLmWorkspaceFileOpsReleaseE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val app: DroidLMApp
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runBlocking {
        grantWorkspaceFilePermissions()
        app.executor.cancelActive()
    }

    @After
    fun tearDown() {
        runCatching { executeShell("am force-stop $DOCS_PACKAGE") }
        runCatching { executeShell("am force-stop $SHEETS_PACKAGE") }
    }

    @Test
    fun bulletOperationUpdatesDocumentFile() = runBlocking {
        val file = prepareFile(
            relativePath = "docs/current-line.txt",
            seedText = "Shopping list\ncurrent line\nnext line\n",
            mimeType = "text/plain",
            viewerPackage = DOCS_PACKAGE
        )

        val result = app.workspaceFileOperationController.formatCurrentLineAsBullet(
            "Add a bullet point on the current line",
            DroidLmAction.FormatCurrentLineAsBullet(fileUri = fileUri(file))
        )

        assertTrue(result.success)
        assertEquals("Shopping list\n- current line\nnext line\n", readDeviceFile(file.absolutePath))
    }

    @Test
    fun replaceOperationUpdatesDocumentFile() = runBlocking {
        val file = prepareFile(
            relativePath = "docs/replace-draft.txt",
            seedText = "Release note: draft\n",
            mimeType = "text/plain",
            viewerPackage = DOCS_PACKAGE
        )

        val result = app.workspaceFileOperationController.replaceDocumentText(
            "Replace draft with final",
            DroidLmAction.ReplaceDocumentText(targetText = "draft", replacementText = "final", fileUri = fileUri(file))
        )

        assertTrue(result.success)
        assertEquals("Release note: final\n", readDeviceFile(file.absolutePath))
    }

    @Test
    fun appendNoteOperationUpdatesDocumentFile() = runBlocking {
        val file = prepareFile(
            relativePath = "docs/append-note.txt",
            seedText = "Quarterly notes\n",
            mimeType = "text/plain",
            viewerPackage = DOCS_PACKAGE
        )

        val result = app.workspaceFileOperationController.appendDocumentNote(
            "Append a note saying reviewed by DroidLM",
            DroidLmAction.AppendDocumentNote(note = "reviewed by DroidLM", fileUri = fileUri(file))
        )

        assertTrue(result.success)
        assertEquals("Quarterly notes\nreviewed by DroidLM\n", readDeviceFile(file.absolutePath))
    }

    @Test
    fun setCurrentCellOperationUpdatesSpreadsheetFile() = runBlocking {
        val file = prepareFile(
            relativePath = "spreadsheets/current-cell.csv",
            seedText = "Year,Value\n,10\n",
            mimeType = "text/csv",
            viewerPackage = SHEETS_PACKAGE
        )

        val result = app.workspaceFileOperationController.setCurrentSheetCell(
            "Put 2026 in the current cell",
            DroidLmAction.SetCurrentSheetCell(value = "2026", fileUri = fileUri(file))
        )

        assertTrue(result.success)
        assertEquals("Year,Value\n2026,10\n", readDeviceFile(file.absolutePath))
    }

    @Test
    fun addSpreadsheetRowOperationUpdatesSpreadsheetFile() = runBlocking {
        val file = prepareFile(
            relativePath = "spreadsheets/add-row.csv",
            seedText = "Month,Value,Status\nMarch,80,pending\n",
            mimeType = "text/csv",
            viewerPackage = SHEETS_PACKAGE
        )

        val result = app.workspaceFileOperationController.addSpreadsheetRow(
            "Add a row with April, 120, approved",
            DroidLmAction.AddSpreadsheetRow(values = listOf("April", "120", "approved"), fileUri = fileUri(file))
        )

        assertTrue(result.success)
        assertEquals("Month,Value,Status\nMarch,80,pending\nApril,120,approved\n", readDeviceFile(file.absolutePath))
    }

    private fun prepareFile(relativePath: String, seedText: String, mimeType: String, viewerPackage: String): File {
        val file = File(deviceRunRoot(), relativePath)
        val directory = file.parentFile ?: throw AssertionError("Expected parent directory for ${file.absolutePath}")
        if (!directory.exists()) executeShell("mkdir -p ${directory.absolutePath}")
        assertTrue(directory.exists() || directory.mkdirs())
        FileOutputStream(file).bufferedWriter(Charsets.UTF_8).use { it.write(seedText) }
        assertTrue(file.isFile)
        executeShell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://${file.absolutePath}")
        executeShell(
            "am start -W -a android.intent.action.VIEW -p $viewerPackage -d file://${file.absolutePath} -t $mimeType"
        )
        SystemClock.sleep(250)
        return file
    }


    private fun grantWorkspaceFilePermissions() {
        executeShell("appops set ${targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow")
        executeShell("cmd appops set ${targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow")
    }

    private fun fileUri(file: File): String = "file://${file.absolutePath}"

    private fun readDeviceFile(devicePath: String): String = executeShell("cat $devicePath")

    private fun executeShell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return output.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
    }

    private fun deviceRunRoot(): File {
        val documentsDir = targetContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: throw AssertionError("Expected app-specific external documents directory")
        return File(documentsDir, "DroidLMTestRuns/file-ops-release")
    }

    companion object {
        private const val DOCS_PACKAGE = "com.google.android.apps.docs.editors.docs"
        private const val SHEETS_PACKAGE = "com.google.android.apps.docs.editors.sheets"
    }
}
