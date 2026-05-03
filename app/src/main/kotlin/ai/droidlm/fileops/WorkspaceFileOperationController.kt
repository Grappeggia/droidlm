package ai.droidlm.fileops

import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.SpeechTextNormalizer
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.portal.ActionResult
import ai.droidlm.textedit.EditableTarget
import ai.droidlm.textedit.TextEditingController
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

class WorkspaceFileOperationController(
    private val context: Context,
    private val textEditingController: TextEditingController,
    private val logs: ActionLogRepository
) {
    suspend fun formatCurrentLineAsBullet(transcript: String, action: DroidLmAction.FormatCurrentLineAsBullet): ActionResult {
        if (action.fileUri.isNullOrBlank()) {
            runEditableTextUpdate { target, text, selection ->
                val lineIndex = lineIndexForSelection(text, selection)
                addBulletToLine(text, action.bulletPrefix, lineIndex)
            }?.let { return it }
        }

        val file = resolveFile(action.fileUri, FileKind.DOCUMENT)
            ?: return ActionResult.fail("No editable document file found", "FILE_NOT_FOUND")
        val updated = updateTextFile(file) { addBulletToLine(it, action.bulletPrefix, preferredLineIndex = null) }
        return finishFileEdit(updated, file, "Added bullet point to current line")
    }

    suspend fun replaceDocumentText(transcript: String, action: DroidLmAction.ReplaceDocumentText): ActionResult {
        val inferred = inferReplace(transcript)
        val targetText = action.targetText.ifBlank { inferred?.first.orEmpty() }
        val replacementText = action.replacementText.ifBlank { inferred?.second.orEmpty() }
        if (targetText.isBlank()) return ActionResult.fail("No replacement target was provided", "MISSING_TARGET_TEXT")

        if (action.fileUri.isNullOrBlank()) {
            val editable = textEditingController.replaceText(targetText, replacementText)
            if (editable.success) return editable
        }

        val file = resolveFile(action.fileUri, FileKind.DOCUMENT)
            ?: return ActionResult.fail("No editable document file found", "FILE_NOT_FOUND")
        val updated = updateTextFile(file) { text ->
            text.replaceFirst(Regex(Regex.escape(targetText), RegexOption.IGNORE_CASE), replacementText)
        }
        return finishFileEdit(updated, file, "Replaced $targetText")
    }

    suspend fun appendDocumentNote(transcript: String, action: DroidLmAction.AppendDocumentNote): ActionResult {
        val note = action.note.ifBlank { inferAppendNote(transcript) }
        if (note.isBlank()) return ActionResult.fail("No note text was provided", "MISSING_NOTE_TEXT")
        val textToAppend = "\n$note"

        if (action.fileUri.isNullOrBlank()) {
            val editable = textEditingController.appendText(textToAppend)
            if (editable.success) return editable
        }

        val file = resolveFile(action.fileUri, FileKind.DOCUMENT)
            ?: return ActionResult.fail("No editable document file found", "FILE_NOT_FOUND")
        val updated = updateTextFile(file) { text ->
            appendOrNormalizeNote(text, note)
        }
        return finishFileEdit(updated, file, "Appended document note")
    }

    suspend fun setCurrentSheetCell(transcript: String, action: DroidLmAction.SetCurrentSheetCell): ActionResult {
        val value = action.value.ifBlank { inferCurrentCellValue(transcript) }
        if (value.isBlank()) return ActionResult.fail("No cell value was provided", "MISSING_CELL_VALUE")

        if (action.fileUri.isNullOrBlank()) {
            val editable = textEditingController.replaceSelection(value)
            if (editable.success) return editable
        }

        val file = resolveFile(action.fileUri, FileKind.SPREADSHEET)
            ?: return ActionResult.fail("No editable spreadsheet file found", "FILE_NOT_FOUND")
        val updated = updateTextFile(file) { setFirstEditableCsvCell(it, value) }
        return finishFileEdit(updated, file, "Set current spreadsheet cell")
    }

    suspend fun addSpreadsheetRow(transcript: String, action: DroidLmAction.AddSpreadsheetRow): ActionResult {
        val values = action.values.ifEmpty { inferSpreadsheetRow(transcript) }
        if (values.isEmpty()) return ActionResult.fail("No row values were provided", "MISSING_ROW_VALUES")
        val rowText = values.joinToString("\t")

        if (action.fileUri.isNullOrBlank()) {
            val editable = textEditingController.insertTextAtSelection(rowText)
            if (editable.success) return editable
        }

        val file = resolveFile(action.fileUri, FileKind.SPREADSHEET)
            ?: return ActionResult.fail("No editable spreadsheet file found", "FILE_NOT_FOUND")
        val updated = updateTextFile(file) { text ->
            appendOrNormalizeCsvRow(text, values)
        }
        return finishFileEdit(updated, file, "Added spreadsheet row")
    }

    private suspend fun runEditableTextUpdate(
        update: suspend (EditableTarget, String, Int?) -> String
    ): ActionResult? {
        val target = textEditingController.getFocusedEditable() ?: return null
        val snapshot = textEditingController.readEditableText(target)
        if (snapshot.text.isBlank()) return null
        val updated = update(target, snapshot.text, snapshot.selectionStart)
        if (updated == snapshot.text) return ActionResult.ok("No text change needed")
        return textEditingController.setFullText(target, updated)
    }

    private fun lineIndexForSelection(text: String, selection: Int?): Int? {
        val safeSelection = selection?.coerceIn(0, text.length) ?: return null
        return text.substring(0, safeSelection).count { it == '\n' }
    }

    private fun addBulletToLine(text: String, bulletPrefix: String, preferredLineIndex: Int?): String {
        val hadTrailingNewline = text.endsWith("\n")
        val lines = text.trimEnd('\n').split('\n').toMutableList()
        if (lines.isEmpty()) return text
        val targetIndex = preferredLineIndex
            ?.takeIf { it in lines.indices && lines[it].isNotBlank() }
            ?: defaultBodyLineIndex(lines)
            ?: return text
        val line = lines[targetIndex]
        if (line.trimStart().startsWith(bulletPrefix.trim())) return text
        val indent = line.takeWhile { it.isWhitespace() }
        lines[targetIndex] = indent + bulletPrefix + line.trimStart()
        return lines.joinToString("\n") + if (hadTrailingNewline) "\n" else ""
    }

    private fun defaultBodyLineIndex(lines: List<String>): Int? {
        val nonEmpty = lines.mapIndexedNotNull { index, line -> index.takeIf { line.isNotBlank() } }
        if (nonEmpty.isEmpty()) return null
        return nonEmpty.getOrNull(1) ?: nonEmpty.first()
    }

    private suspend fun updateTextFile(file: File, transform: (String) -> String): Boolean = withContext(Dispatchers.IO) {
        val original = file.readText()
        val updated = transform(original)
        if (updated == original) return@withContext true
        file.writeText(updated)
        true
    }

    private suspend fun finishFileEdit(updated: Boolean, file: File, message: String): ActionResult {
        if (!updated) return ActionResult.fail("File was not updated", "FILE_UPDATE_FAILED")
        logs.log(ActionLogType.TEXT_EDIT_RESULT, "$message in ${file.name}")
        openPreview(file)
        return ActionResult.ok(message)
    }

    private fun resolveFile(explicitUri: String?, kind: FileKind): File? {
        explicitUri?.let { uri -> fileFromUri(uri)?.takeIf { it.isFile }?.let { return it } }
        return findRecentWorkspaceFile(kind)
    }

    private fun fileFromUri(value: String): File? = runCatching {
        when {
            value.startsWith("file://") -> File(URI(value))
            value.startsWith("/") -> File(value)
            else -> File(Uri.parse(value).path.orEmpty())
        }
    }.getOrNull()

    private fun findRecentWorkspaceFile(kind: FileKind): File? {
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val roots = listOf(
            File(documents, "DroidLMTestRuns"),
            File(documents, "DroidLMFixtures"),
            documents
        )
        return roots.asSequence()
            .filter { it.exists() }
            .flatMap { root -> runCatching { root.walkTopDown().asSequence() }.getOrDefault(emptySequence()) }
            .filter { it.isFile && kind.matches(it) }
            .maxByOrNull { it.lastModified() }
    }

    private fun openPreview(file: File) {
        val previewFile = createPreviewCopy(file)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", previewFile)
        val baseIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "text/plain")
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        val chromeIntent = Intent(baseIntent).setPackage("com.android.chrome")
        runCatching { context.startActivity(chromeIntent) }
            .recoverCatching { context.startActivity(baseIntent) }
    }

    private fun createPreviewCopy(file: File): File {
        val previewDir = File(context.cacheDir, "workspace-previews")
        previewDir.mkdirs()
        val previewFile = File(previewDir, "${file.nameWithoutExtension}-${System.currentTimeMillis()}.txt")
        previewFile.writeText(file.readText())
        return previewFile
    }

    private fun inferReplace(transcript: String): Pair<String, String>? =
        Regex("replace (.+?) with (.+)", RegexOption.IGNORE_CASE).find(transcript)?.let { match ->
            cleanup(match.groupValues[1]) to SpeechTextNormalizer.normalizeDictatedText(match.groupValues[2])
        }

    private fun inferAppendNote(transcript: String): String =
        Regex("append (?:a )?note saying (.+)", RegexOption.IGNORE_CASE).find(transcript)
            ?.let { SpeechTextNormalizer.normalizeDictatedText(it.groupValues[1]) }
            ?: Regex("append (.+)", RegexOption.IGNORE_CASE).find(transcript)
                ?.let { SpeechTextNormalizer.normalizeDictatedText(it.groupValues[1]) }
            ?: ""

    private fun inferCurrentCellValue(transcript: String): String =
        Regex("put (.+) in (?:the )?current cell", RegexOption.IGNORE_CASE).find(transcript)
            ?.let { SpeechTextNormalizer.normalizeDictatedText(it.groupValues[1]) }
            ?: ""

    private fun inferSpreadsheetRow(transcript: String): List<String> =
        Regex("add (?:a )?row with (.+)", RegexOption.IGNORE_CASE).find(transcript)
            ?.groupValues
            ?.get(1)
            ?.split(",", " and ")
            ?.map { SpeechTextNormalizer.normalizeDictatedText(it).trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun appendOrNormalizeNote(text: String, note: String): String {
        val separator = if (text.endsWith("\n") || text.isBlank()) "" else "\n"
        return text + separator + note + "\n"
    }

    private fun appendOrNormalizeCsvRow(text: String, values: List<String>): String {
        val desiredRow = values.joinToString(",")
        val separator = if (text.endsWith("\n") || text.isBlank()) "" else "\n"
        return text + separator + desiredRow + "\n"
    }

    private fun setFirstEditableCsvCell(text: String, value: String): String {
        val hadTrailingNewline = text.endsWith("\n")
        val lines = text.trimEnd('\n').split('\n').toMutableList()
        if (lines.isEmpty()) return value + if (hadTrailingNewline) "\n" else ""
        val startRow = if (lines.size > 1) 1 else 0
        for (rowIndex in startRow until lines.size) {
            val cells = lines[rowIndex].split(',').toMutableList()
            val emptyIndex = cells.indexOfFirst { it.isBlank() }
            if (emptyIndex >= 0) {
                cells[emptyIndex] = value
                lines[rowIndex] = cells.joinToString(",")
                return lines.joinToString("\n") + if (hadTrailingNewline) "\n" else ""
            }
        }
        val cells = lines[startRow].split(',').toMutableList()
        if (cells.isEmpty()) cells += value else cells[0] = value
        lines[startRow] = cells.joinToString(",")
        return lines.joinToString("\n") + if (hadTrailingNewline) "\n" else ""
    }

    private fun cleanup(value: String): String = value.trim().trim(',', '.', ':', ';')

    private enum class FileKind(private val extensions: Set<String>) {
        DOCUMENT(setOf("txt", "md", "text")),
        SPREADSHEET(setOf("csv", "tsv"));

        fun matches(file: File): Boolean = extensions.contains(file.extension.lowercase())
    }
}
