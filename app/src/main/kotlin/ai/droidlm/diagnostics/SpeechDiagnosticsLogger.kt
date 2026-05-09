package ai.droidlm.diagnostics

import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.settings.DroidLmSettings
import ai.droidlm.settings.SettingsRepository
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SpeechDiagnosticsLogger(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val actionLogs: ActionLogRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionStarts = ConcurrentHashMap<String, Long>()
    private val sequence = AtomicLong(0)
    private val eventSequence = AtomicLong(0)
    private val logDirectory = File(context.cacheDir, "droidlm-diagnostics")
    private val logFile = File(logDirectory, "speech-diagnostics.jsonl")
    private val pendingWrites = mutableListOf<Job>()

    @Volatile private var enabled = false

    init {
        scope.launch {
            settingsRepository.settings
                .map { it.debugLoggingEnabled }
                .distinctUntilChanged()
                .collect { value -> setEnabled(value) }
        }
    }

    fun setEnabled(value: Boolean) {
        val changed = enabled != value
        if (!value && changed) {
            record(sessionId = null, event = "diagnostics_disabled", fields = deviceFields())
        }
        enabled = value
        if (value && changed) {
            record(
                sessionId = null,
                event = "diagnostics_enabled",
                fields = deviceFields()
            )
        }
    }

    fun startSession(source: String, fields: Map<String, Any?> = emptyMap()): String {
        val id = "speech-${System.currentTimeMillis()}-${sequence.incrementAndGet()}"
        sessionStarts[id] = SystemClock.elapsedRealtime()
        record(
            sessionId = id,
            event = "session_start",
            fields = mapOf("source" to source) + deviceFields() + fields
        )
        scope.launch {
            runCatching { settingsRepository.settings.first() }
                .onSuccess { settings -> record(id, "settings_snapshot", settingsFields(settings)) }
                .onFailure { error -> record(id, "settings_snapshot_failed", mapOf("errorClass" to error::class.java.name, "message" to error.message)) }
        }
        return id
    }

    fun record(sessionId: String?, event: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        val line = diagnosticLine(sessionId, event, fields)
        Log.d(LOG_TAG, line)
        enqueueWrite(line)
    }

    fun isEnabledNow(): Boolean = enabled

    fun endSession(sessionId: String?, reason: String, fields: Map<String, Any?> = emptyMap()) {
        record(sessionId, "session_end", mapOf("reason" to reason) + fields)
    }

    fun clear() {
        scope.launch {
            runCatching {
                if (logFile.exists()) logFile.delete()
                logDirectory.listFiles { file -> file.name.startsWith("speech-diagnostics-") }?.forEach { it.delete() }
            }
            actionLogs.log(ActionLogType.ACTION_RESULT, "Speech diagnostics cleared")
        }
    }

    fun exportFileName(): String = "droidlm-speech-diagnostics-${utcTimestampForFile(System.currentTimeMillis())}.jsonl"

    suspend fun exportSnapshot(): File? = withContext(Dispatchers.IO) {
        record(
            sessionId = null,
            event = "diagnostics_export_requested",
            fields = mapOf("sourceExists" to logFile.exists(), "sourceBytes" to if (logFile.exists()) logFile.length() else 0L)
        )
        awaitPendingWrites()
        if (!logFile.exists() || logFile.length() == 0L) return@withContext null
        logDirectory.mkdirs()
        val shareFile = File(logDirectory, exportFileName())
        val header = diagnosticLine(
            sessionId = null,
            event = "diagnostics_export",
            fields = deviceFields() + mapOf("sourceBytes" to logFile.length())
        )
        shareFile.bufferedWriter().use { writer ->
            writer.appendLine(header)
            logFile.bufferedReader().useLines { lines ->
                lines.forEach { writer.appendLine(it) }
            }
        }
        shareFile
    }

    suspend fun shareFile(): File? = exportSnapshot()

    private fun diagnosticLine(sessionId: String?, event: String, fields: Map<String, Any?>): String {
        val nowMs = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val values = linkedMapOf<String, Any?>(
            "wallTime" to utcTimestamp(nowMs),
            "wallTimeMs" to nowMs,
            "seq" to eventSequence.incrementAndGet(),
            "event" to event
        )
        if (sessionId != null) {
            val start = sessionStarts[sessionId]
            values["sessionId"] = sessionId
            values["tMs"] = if (start != null) nowElapsed - start else null
        }
        fields.forEach { (key, value) -> values[key] = value }
        return values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escapeJson(key)}\":${jsonValue(value)}"
        }
    }

    @Synchronized
    private fun enqueueWrite(line: String) {
        val job = scope.launch { appendLine(line) }
        pendingWrites += job
        job.invokeOnCompletion {
            synchronized(this@SpeechDiagnosticsLogger) {
                pendingWrites.remove(job)
            }
        }
    }

    private suspend fun awaitPendingWrites() {
        while (true) {
            val jobs = synchronized(this) { pendingWrites.toList() }
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }

    @Synchronized
    private fun appendLine(line: String) {
        logDirectory.mkdirs()
        logFile.appendText(line + "\n")
        trimIfNeeded()
    }

    private fun trimIfNeeded() {
        if (logFile.length() <= MAX_LOG_BYTES) return
        val lines = logFile.readLines()
        val retained = lines.takeLast(MAX_LOG_LINES)
        logFile.writeText(retained.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun deviceFields(): Map<String, Any?> = mapOf(
        "appPackage" to context.packageName,
        "appVersion" to appVersionName(),
        "debuggable" to isDebuggable(),
        "sdk" to Build.VERSION.SDK_INT,
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL
    )

    private fun settingsFields(settings: DroidLmSettings): Map<String, Any?> = mapOf(
        "relayBaseUrlConfigured" to settings.relayBaseUrl.isNotBlank(),
        "openAiApiKeyConfigured" to settings.openAiApiKeyConfigured,
        "openAiModel" to settings.openAiModel,
        "wakePhraseLength" to settings.wakePhrase.length,
        "transcriptionProvider" to settings.transcriptionProvider.name,
        "preferOfflineSpeechRecognition" to settings.preferOfflineSpeechRecognition,
        "showPartialSpeechRecognition" to settings.showPartialSpeechRecognition,
        "floatingOverlayEnabled" to settings.floatingOverlayEnabled,
        "overlayX" to settings.overlayX,
        "overlayY" to settings.overlayY,
        "hideOverlayDuringAutomation" to settings.hideOverlayDuringAutomation,
        "wakeWordProvider" to settings.wakeWordProvider.name,
        "picovoiceAccessKeyConfigured" to settings.picovoiceAccessKeyConfigured,
        "wakeWordModelAssetPathConfigured" to settings.wakeWordModelAssetPath.isNotBlank(),
        "wakeSensitivity" to settings.wakeSensitivity,
        "executionMode" to settings.executionMode.name,
        "autoAcceptSafePlans" to settings.autoAcceptSafePlans,
        "mobilerunApiKeyConfigured" to settings.mobilerunApiKeyConfigured,
        "mobilerunDeviceIdConfigured" to settings.mobilerunDeviceId.isNotBlank(),
        "mobilerunLlmModel" to settings.mobilerunLlmModel,
        "maxAutonomousSteps" to settings.maxAutonomousSteps,
        "maxAgentTurns" to settings.maxAgentTurns,
        "maxAgentToolCalls" to settings.maxAgentToolCalls,
        "requireRiskConfirmation" to settings.requireRiskConfirmation,
        "onDeviceOcrEnabled" to settings.onDeviceOcrEnabled,
        "cloudScreenshotAnalysisEnabled" to settings.cloudScreenshotAnalysisEnabled,
        "debugLoggingEnabled" to settings.debugLoggingEnabled,
        "onboardingCompletedVersion" to settings.onboardingCompletedVersion,
        "sensitiveDenylistEntryCount" to splitListCount(settings.sensitiveAppScreenshotDenylist),
        "sensitiveDenylistCustomized" to (settings.sensitiveAppScreenshotDenylist != DroidLmSettings.DEFAULT_SENSITIVE_DENYLIST),
        "packageAllowlistEntryCount" to splitListCount(settings.packageAllowlist),
        "packageDenylistEntryCount" to splitListCount(settings.packageDenylist)
    )

    private fun splitListCount(value: String): Int = value.split(',').count { it.trim().isNotBlank() }

    private fun isDebuggable(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

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
        const val LOG_TAG = "DroidLMSpeechDiag"
        const val MAX_LOG_BYTES = 1_000_000L
        const val MAX_LOG_LINES = 2_000
    }
}
