package ai.droidlm.execution

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.intent.SpeechTextNormalizer
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

internal data class InstallMonitorTarget(
    val appName: String?,
    val packageName: String,
    val sourceTranscript: String,
    val openWhenInstalled: Boolean,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    val label: String get() = appName?.takeIf { it.isNotBlank() } ?: packageName
}

internal object InstallMonitorIntent {
    fun isInstallMonitorRequest(transcript: String): Boolean {
        val normalized = SpeechTextNormalizer.normalizeForRecognition(transcript)
        if (normalized.isBlank()) return false
        val asksToWait = listOf("wait", "monitor", "watch", "keep an eye", "keep checking").any { normalized.contains(it) }
        val installCompletion = listOf("finish", "finished", "complete", "completed", "done", "install", "installed", "download").any { normalized.contains(it) }
        val asksToOpenIt = Regex("\\b(open|launch|start) it\\b").containsMatchIn(normalized) || normalized.contains("then open")
        val asksToOpenAfter = normalized.contains("open after") || normalized.contains("open when")
        return (asksToWait && installCompletion) || asksToOpenIt || asksToOpenAfter
    }

    fun shouldOpenAfterInstall(transcript: String): Boolean {
        val normalized = SpeechTextNormalizer.normalizeForRecognition(transcript)
        return Regex("\\b(open|launch|start) it\\b").containsMatchIn(normalized) ||
            normalized.contains("then open") ||
            normalized.contains("open when") ||
            normalized.contains("open after")
    }

    fun textImpliesOpenAfterInstall(text: String): Boolean {
        val normalized = SpeechTextNormalizer.normalizeForRecognition(text)
        return listOf("open", "launch", "start").any { normalized.contains(it) }
    }
}

internal class ExecutionInstallMonitorRunner(
    private val appInventoryRepository: AppInventoryRepository,
    private val portalController: PortalController,
    private val logs: ActionLogRepository,
    private val uiState: MutableStateFlow<ExecutionUiState>,
    private val executionDiagnostics: ExecutionDiagnostics,
    private val cancellationResult: () -> ActionResult?,
    private val finish: (ActionResult) -> ActionResult,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val delayProvider: suspend (Long) -> Unit = { delay(it) },
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val activeAppTimeoutMs: Long = DEFAULT_ACTIVE_APP_TIMEOUT_MS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    suspend fun run(target: InstallMonitorTarget, transcript: String, diagnosticSessionId: String?): ActionResult {
        val monitorStartedAt = nowProvider()
        val openAfterInstall = target.openWhenInstalled || InstallMonitorIntent.shouldOpenAfterInstall(transcript)
        debugEvent(
            diagnosticSessionId,
            "install_monitor_started",
            mapOf(
                "packageName" to target.packageName,
                "appName" to target.appName,
                "openAfterInstall" to openAfterInstall,
                "timeoutMs" to timeoutMs
            )
        )
        logs.log(ActionLogType.ACTION_STARTED, "Monitoring install for ${target.label}", target.packageName)

        var attempts = 0
        while (nowProvider() - monitorStartedAt <= timeoutMs) {
            cancellationResult()?.let { return finish(it) }
            attempts += 1
            val elapsedMs = nowProvider() - monitorStartedAt
            uiState.value = uiState.value.copy(
                status = "Monitoring install for ${target.label}",
                parsedAction = "MONITOR_INSTALL ${target.packageName}",
                lastResult = "Waiting ${elapsedMs / 1000}s"
            )

            val installed = findInstalledPackage(target.packageName)
            debugEvent(
                diagnosticSessionId,
                "install_monitor_poll",
                mapOf(
                    "attempt" to attempts,
                    "elapsedMs" to elapsedMs,
                    "installed" to (installed != null),
                    "enabled" to installed?.enabled,
                    "launchable" to installed?.launchable
                )
            )

            if (installed != null) {
                if (installed.enabled == false) {
                    return finish(ActionResult.fail("${target.label} is installed but disabled", "APP_DISABLED_AFTER_INSTALL"))
                }
                if (installed.launchable != false) {
                    return onPackageLaunchable(target, openAfterInstall, diagnosticSessionId, monitorStartedAt)
                }
            }
            delayProvider(pollIntervalMs)
        }

        debugEvent(
            diagnosticSessionId,
            "install_monitor_timeout",
            mapOf("packageName" to target.packageName, "attempts" to attempts, "timeoutMs" to timeoutMs)
        )
        return finish(ActionResult.fail("Timed out waiting for ${target.label} to finish installing", "INSTALL_MONITOR_TIMEOUT"))
    }

    private suspend fun onPackageLaunchable(
        target: InstallMonitorTarget,
        openAfterInstall: Boolean,
        diagnosticSessionId: String?,
        monitorStartedAt: Long
    ): ActionResult {
        appInventoryRepository.invalidate()
        debugEvent(
            diagnosticSessionId,
            "install_monitor_package_ready",
            mapOf(
                "packageName" to target.packageName,
                "elapsedMs" to (nowProvider() - monitorStartedAt),
                "openAfterInstall" to openAfterInstall
            )
        )
        if (!openAfterInstall) {
            logs.log(ActionLogType.ACTION_RESULT, "${target.label} finished installing", target.packageName)
            return finish(ActionResult.ok("${target.label} finished installing"))
        }

        uiState.value = uiState.value.copy(status = "Opening ${target.label}", lastResult = "Install finished")
        val openResult = portalController.openApp(target.packageName)
        debugEvent(
            diagnosticSessionId,
            "install_monitor_open_result",
            mapOf(
                "packageName" to target.packageName,
                "success" to openResult.success,
                "message" to openResult.message,
                "errorCode" to openResult.errorCode
            )
        )
        if (!openResult.success) return finish(openResult)

        val activeResult = waitForActivePackage(target.packageName)
        debugEvent(
            diagnosticSessionId,
            "install_monitor_active_app_result",
            mapOf(
                "packageName" to target.packageName,
                "success" to activeResult.success,
                "message" to activeResult.message,
                "errorCode" to activeResult.errorCode
            )
        )
        if (!activeResult.success) return finish(activeResult)

        logs.log(ActionLogType.ACTION_RESULT, "Installed and opened ${target.label}", target.packageName)
        return finish(ActionResult.ok("Installed and opened ${target.label}"))
    }

    private suspend fun waitForActivePackage(packageName: String): ActionResult {
        val startedAt = nowProvider()
        while (nowProvider() - startedAt <= activeAppTimeoutMs) {
            cancellationResult()?.let { return it }
            val state = runCatching { portalController.getState() }.getOrNull()
            if (state?.packageName == packageName) {
                return ActionResult.ok("Opened $packageName")
            }
            delayProvider(ACTIVE_APP_POLL_INTERVAL_MS)
        }
        return ActionResult.fail("Installed app did not become active: $packageName", "APP_OPEN_VERIFICATION_FAILED")
    }

    private suspend fun findInstalledPackage(packageName: String): AppPackage? =
        appInventoryRepository.getInstalledApps(forceRefresh = true).firstOrNull { it.packageName == packageName }

    private fun debugEvent(sessionId: String?, event: String, fields: Map<String, Any?> = emptyMap()) {
        executionDiagnostics.debugEvent(sessionId, event, fields)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L
        const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        const val DEFAULT_ACTIVE_APP_TIMEOUT_MS = 8_000L
        private const val ACTIVE_APP_POLL_INTERVAL_MS = 250L
    }
}
