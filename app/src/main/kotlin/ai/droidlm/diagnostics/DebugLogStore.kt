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

    fun recordEvent(event: String, fields: Map<String, Any?> = emptyMap()) {
        speechDiagnosticsLogger.record(null, "debug_$event", fields)
    }

    suspend fun retainFile(source: File, category: String, suggestedName: String = source.name): File? = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext null
        if (!source.isFile || source.length() <= 0L) {
            recordEvent(
                "file_retention_skipped",
                mapOf("category" to category, "sourceName" to source.name, "reason" to "missing_or_empty", "sourceBytes" to source.length())
            )
            return@withContext null
        }
        val target = uniqueFile(category, suggestedName.ifBlank { source.name })
        runCatching {
            source.copyTo(target, overwrite = false)
            recordEvent(
                "file_retained",
                mapOf(
                    "category" to category,
                    "sourceName" to source.name,
                    "sourceBytes" to source.length(),
                    "retainedName" to target.name,
                    "retainedBytes" to target.length()
                )
            )
            target
        }.onFailure { error ->
            recordEvent(
                "file_retention_failed",
                mapOf("category" to category, "sourceName" to source.name, "sourceBytes" to source.length(), "message" to error.message)
            )
        }.getOrNull()
    }

    suspend fun retainScreenshot(bitmap: Bitmap, source: String): File? = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext null
        val file = uniqueFile("screenshots", "droidlm-screenshot-${utcTimestampForFile(System.currentTimeMillis())}-${safeName(source)}.png")
        recordEvent("screenshot_retention_started", mapOf("source" to source, "width" to bitmap.width, "height" to bitmap.height, "targetName" to file.name))
        runCatching {
            val compressed = FileOutputStream(file).use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            if (!compressed) error("Bitmap compression returned false")
            file.takeIf { it.length() > 0L }?.also {
                recordEvent(
                    "screenshot_retained",
                    mapOf("source" to source, "retainedName" to it.name, "retainedBytes" to it.length(), "width" to bitmap.width, "height" to bitmap.height)
                )
            } ?: run {
                file.delete()
                recordEvent("screenshot_retention_failed", mapOf("source" to source, "reason" to "empty_file", "width" to bitmap.width, "height" to bitmap.height))
                null
            }
        }.onFailure { error ->
            file.delete()
            recordEvent("screenshot_retention_failed", mapOf("source" to source, "message" to error.message, "width" to bitmap.width, "height" to bitmap.height))
        }.getOrNull()
    }

    suspend fun retainText(category: String, source: String, text: String, extension: String = "txt"): File? = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext null
        val suffix = extension.trim().trimStart('.').ifBlank { "txt" }
        val file = uniqueFile(category, "droidlm-${safeName(category)}-${utcTimestampForFile(System.currentTimeMillis())}-${safeName(source)}.$suffix")
        runCatching {
            file.writeText(text, Charsets.UTF_8)
            file.takeIf { it.length() > 0L }?.also {
                recordEvent(
                    "text_retained",
                    mapOf("category" to category, "source" to source, "retainedName" to it.name, "retainedBytes" to it.length())
                )
            } ?: run {
                file.delete()
                recordEvent("text_retention_failed", mapOf("category" to category, "source" to source, "reason" to "empty_file"))
                null
            }
        }.onFailure { error ->
            file.delete()
            recordEvent("text_retention_failed", mapOf("category" to category, "source" to source, "message" to error.message))
        }.getOrNull()
    }

    suspend fun createRetainedFile(category: String, extension: String, source: String): File? = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext null
        val suffix = extension.trim().trimStart('.').ifBlank { "bin" }
        uniqueFile(category, "droidlm-${safeName(category)}-${utcTimestampForFile(System.currentTimeMillis())}-${safeName(source)}.$suffix").also {
            recordEvent("retained_file_created", mapOf("category" to category, "source" to source, "extension" to suffix, "targetName" to it.name))
        }
    }

    suspend fun createBundle(issueDescription: String? = null): File? = withContext(Dispatchers.IO) {
        val issueDescriptionText = issueDescription?.let(::issueDescriptionText)
        val issueDescriptionBytes = issueDescriptionText?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        recordEvent(
            "bundle_requested",
            mapOf(
                "issueDescriptionProvided" to (issueDescription != null),
                "issueDescriptionBytes" to issueDescriptionBytes
            )
        )
        val speechSnapshot = speechDiagnosticsLogger.exportSnapshot()
        val retainedFiles = captureDirectory
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile && it.length() > 0L }
            ?.toList()
            .orEmpty()

        recordEvent(
            "bundle_sources_collected",
            mapOf(
                "speechSnapshotBytes" to (speechSnapshot?.length() ?: 0L),
                "retainedFileCount" to retainedFiles.size,
                "retainedBytes" to retainedFiles.sumOf { it.length() },
                "categories" to categorySummary(retainedFiles),
                "issueDescriptionBytes" to issueDescriptionBytes
            )
        )
        recordEvent("bundle_privacy_metadata", privacyMetadata(speechSnapshot, retainedFiles, issueDescriptionText))

        if (speechSnapshot == null && retainedFiles.isEmpty() && issueDescriptionText == null) {
            recordEvent("bundle_empty")
            return@withContext null
        }

        exportDirectory.mkdirs()
        val zipFile = uniqueExportFile()
        var entries = 0
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            entries += addText(zip, "manifest.json", manifestJson(speechSnapshot, retainedFiles, issueDescriptionText))
            if (issueDescriptionText != null) {
                entries += addText(zip, ISSUE_DESCRIPTION_ENTRY, issueDescriptionText)
            }
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
            recordEvent("bundle_failed", mapOf("reason" to "no_entries"))
            null
        } else {
            recordEvent("bundle_created", mapOf("zipName" to zipFile.name, "zipBytes" to zipFile.length(), "entryCount" to entries))
            zipFile
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        val retainedCount = captureDirectory.takeIf { it.isDirectory }?.walkTopDown()?.count { it.isFile } ?: 0
        val exportCount = exportDirectory.takeIf { it.isDirectory }?.walkTopDown()?.count { it.isFile } ?: 0
        recordEvent("files_clear_requested", mapOf("retainedFileCount" to retainedCount, "exportFileCount" to exportCount))
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

    private fun addText(zip: ZipOutputStream, entryName: String, text: String): Int {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        return 1
    }

    private fun manifestJson(speechSnapshot: File?, retainedFiles: List<File>, issueDescriptionText: String?): String {
        val files = mutableListOf<Map<String, Any?>>()
        if (issueDescriptionText != null) {
            files += mapOf(
                "path" to ISSUE_DESCRIPTION_ENTRY,
                "bytes" to issueDescriptionText.toByteArray(Charsets.UTF_8).size.toLong(),
                "category" to "issue",
                "sensitivity" to sensitivityForCategory("issue")
            )
        }
        if (speechSnapshot != null) {
            files += mapOf("path" to "speech/${speechSnapshot.name}", "bytes" to speechSnapshot.length(), "category" to "speech", "sensitivity" to sensitivityForCategory("speech"))
        }
        retainedFiles.sortedBy { it.path }.forEach { file ->
            val relativePath = file.relativeTo(captureDirectory).path.replace(File.separatorChar, '/')
            val category = relativePath.substringBefore('/')
            files += mapOf("path" to relativePath, "bytes" to file.length(), "category" to category, "sensitivity" to sensitivityForCategory(category))
        }
        val privacy = privacyMetadata(speechSnapshot, retainedFiles, issueDescriptionText)
        return jsonValue(
            linkedMapOf(
                "createdAt" to utcTimestamp(System.currentTimeMillis()),
                "fileCount" to files.size,
                "totalBytes" to files.sumOf { (it["bytes"] as? Long) ?: 0L },
                "privacy" to privacy,
                "files" to files
            )
        ) + "\n"
    }

    private fun privacyMetadata(speechSnapshot: File?, retainedFiles: List<File>, issueDescriptionText: String?): Map<String, Any?> {
        val categories = retainedFiles.map { file -> file.relativeTo(captureDirectory).path.substringBefore(File.separator) }.toSet()
        return mapOf(
            "includesIssueDescription" to (issueDescriptionText != null),
            "includesSpeechDiagnostics" to (speechSnapshot != null),
            "includesRawAudio" to categories.contains("audio"),
            "includesScreenshots" to categories.contains("screenshots"),
            "includesLlmTraces" to categories.contains("llm"),
            "rawAudioFiles" to retainedFiles.count { it.relativeTo(captureDirectory).path.substringBefore(File.separator) == "audio" },
            "screenshotFiles" to retainedFiles.count { it.relativeTo(captureDirectory).path.substringBefore(File.separator) == "screenshots" },
            "llmTraceFiles" to retainedFiles.count { it.relativeTo(captureDirectory).path.substringBefore(File.separator) == "llm" },
            "categorySensitivity" to categories.plus(listOfNotNull(if (speechSnapshot != null) "speech" else null, if (issueDescriptionText != null) "issue" else null))
                .sorted()
                .associateWith(::sensitivityForCategory),
            "apiKeysIncluded" to false
        )
    }

    private fun sensitivityForCategory(category: String): String = when (category) {
        "audio" -> "raw_microphone_audio"
        "screenshots" -> "screen_pixels"
        "llm" -> "llm_request_response_trace"
        "speech" -> "speech_diagnostics_spoken_text_device_state"
        "issue" -> "user_provided_issue_text"
        else -> "retained_debug_file"
    }

    private fun issueDescriptionText(issueDescription: String): String {
        val description = issueDescription.trim().take(MAX_ISSUE_DESCRIPTION_CHARS).ifBlank { "No issue description provided." }
        return "$description\n"
    }

    private fun categorySummary(files: List<File>): Map<String, Map<String, Any>> = files
        .groupBy { file -> file.relativeTo(captureDirectory).path.substringBefore(File.separator) }
        .mapValues { (_, categoryFiles) ->
            mapOf("count" to categoryFiles.size, "bytes" to categoryFiles.sumOf { it.length() })
        }

    private fun safeName(value: String): String {
        val sanitized = value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.')
        return sanitized.ifBlank { "file" }
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Number -> value.toString()
        is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "\"${escapeJson(key.toString())}\":${jsonValue(item)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonValue(it) }
        else -> "\"${escapeJson(value.toString())}\""
    }

    private fun escapeJson(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }

    private fun utcTimestamp(timestampMs: Long): String = utcFormatter("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(Date(timestampMs))

    private fun utcTimestampForFile(timestampMs: Long): String = utcFormatter("yyyyMMdd-HHmmss").format(Date(timestampMs))

    private fun utcFormatter(pattern: String): SimpleDateFormat = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private companion object {
        const val ISSUE_DESCRIPTION_ENTRY = "issue-description.txt"
        const val MAX_ISSUE_DESCRIPTION_CHARS = 4_000
    }
}
