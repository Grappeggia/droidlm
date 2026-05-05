package ai.droidlm.diagnostics

import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.settings.SettingsRepository
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DebugLogStore(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val actionLogs: ActionLogRepository,
    private val speechDiagnosticsLogger: SpeechDiagnosticsLogger
) {
    private val captureDirectory = File(context.cacheDir, "droidlm-debug-logs")
    private val exportDirectory = File(context.cacheDir, "droidlm-debug-exports")

    suspend fun isEnabled(): Boolean = settingsRepository.settings.first().debugLoggingEnabled

    fun exportFileName(): String = "droidlm-debug-logs-${utcTimestampForFile(System.currentTimeMillis())}.zip"

    suspend fun retainFile(source: File, category: String, suggestedName: String = source.name): File? = withContext(Dispatchers.IO) {
        if (!isEnabled() || !source.isFile || source.length() <= 0L) return@withContext null
        val target = uniqueFile(category, suggestedName.ifBlank { source.name })
        runCatching {
            source.copyTo(target, overwrite = false)
            target
        }.getOrNull()
    }

    suspend fun retainScreenshot(bitmap: Bitmap, source: String): File? = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext null
        val file = uniqueFile("screenshots", "droidlm-screenshot-${utcTimestampForFile(System.currentTimeMillis())}-${safeName(source)}.png")
        runCatching {
            FileOutputStream(file).use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            file.takeIf { it.length() > 0L } ?: run {
                file.delete()
                null
            }
        }.getOrNull()
    }

    suspend fun createRetainedFile(category: String, extension: String, source: String): File? = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext null
        val suffix = extension.trim().trimStart('.').ifBlank { "bin" }
        uniqueFile(category, "droidlm-${safeName(category)}-${utcTimestampForFile(System.currentTimeMillis())}-${safeName(source)}.$suffix")
    }

    suspend fun createBundle(): File? = withContext(Dispatchers.IO) {
        val speechSnapshot = speechDiagnosticsLogger.exportSnapshot()
        val retainedFiles = captureDirectory
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile && it.length() > 0L }
            ?.toList()
            .orEmpty()

        if (speechSnapshot == null && retainedFiles.isEmpty()) return@withContext null

        exportDirectory.mkdirs()
        val zipFile = uniqueExportFile()
        var entries = 0
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            if (speechSnapshot != null) {
                entries += addFile(zip, speechSnapshot, "speech/${speechSnapshot.name}")
            }
            retainedFiles.sortedBy { it.path }.forEach { file ->
                val relativePath = file.relativeTo(captureDirectory).path.replace(File.separatorChar, '/')
                entries += addFile(zip, file, relativePath)
            }
        }

        if (entries == 0) {
            zipFile.delete()
            null
        } else {
            zipFile
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        captureDirectory.deleteRecursively()
        exportDirectory.deleteRecursively()
        actionLogs.log(ActionLogType.ACTION_RESULT, "Debug log files cleared")
    }

    private fun uniqueFile(category: String, suggestedName: String): File {
        val directory = File(captureDirectory, safeName(category))
        directory.mkdirs()
        val safeSuggestedName = safeName(suggestedName)
        val dot = safeSuggestedName.lastIndexOf('.')
        val baseName = if (dot > 0) safeSuggestedName.substring(0, dot) else safeSuggestedName
        val extension = if (dot > 0) safeSuggestedName.substring(dot) else ""
        var candidate = File(directory, safeSuggestedName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$baseName-$index$extension")
            index += 1
        }
        return candidate
    }

    private fun uniqueExportFile(): File {
        var candidate = File(exportDirectory, exportFileName())
        var index = 1
        while (candidate.exists()) {
            val name = candidate.nameWithoutExtension
            candidate = File(exportDirectory, "$name-$index.zip")
            index += 1
        }
        return candidate
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String): Int {
        if (!file.isFile || file.length() <= 0L) return 0
        val safeEntryName = entryName.split('/').joinToString("/") { safeName(it) }
        zip.putNextEntry(ZipEntry(safeEntryName))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
        return 1
    }

    private fun safeName(value: String): String {
        val sanitized = value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.')
        return sanitized.ifBlank { "file" }
    }

    private fun utcTimestampForFile(timestampMs: Long): String = utcFormatter("yyyyMMdd-HHmmss").format(Date(timestampMs))

    private fun utcFormatter(pattern: String): SimpleDateFormat = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
