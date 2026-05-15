package ai.droidlm.execution

import ai.droidlm.appinventory.AppInventoryRepository
import ai.droidlm.intent.DroidLmAction
import ai.droidlm.intent.SpeechTextNormalizer
import ai.droidlm.intent.displayName
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import ai.droidlm.portal.ActionResult
import ai.droidlm.portal.AppPackage
import ai.droidlm.portal.PortalController
import ai.droidlm.portal.PortalState
import kotlinx.coroutines.flow.MutableStateFlow

internal sealed interface ExecutionGoal {
    val label: String

    data class OpenApp(
        override val label: String,
        val packageName: String,
        val allowInstallRecovery: Boolean
    ) : ExecutionGoal
}

internal data class ExecutionGoalVerification(
    val complete: Boolean,
    val message: String,
    val expected: String,
    val actual: String
)

internal class ExecutionGoalVerifier {
    fun verify(goal: ExecutionGoal, state: PortalState?, packages: List<AppPackage>): ExecutionGoalVerification = when (goal) {
        is ExecutionGoal.OpenApp -> verifyOpenApp(goal, state, packages)
    }

    private fun verifyOpenApp(
        goal: ExecutionGoal.OpenApp,
        state: PortalState?,
        packages: List<AppPackage>
    ): ExecutionGoalVerification {
        val activePackage = state?.packageName
        if (activePackage == goal.packageName) {
            return ExecutionGoalVerification(
                complete = true,
                message = "${goal.label} is open",
                expected = goal.packageName,
                actual = activePackage
            )
        }
        val installed = packages.firstOrNull { it.packageName == goal.packageName }
        val actual = when {
            activePackage != null -> activePackage
            installed == null -> "not installed"
            installed.enabled == false -> "installed but disabled"
            installed.launchable == false -> "installed but not launchable"
            else -> "inactive"
        }
        return ExecutionGoalVerification(
            complete = false,
            message = "${goal.label} is not open",
            expected = goal.packageName,
            actual = actual
        )
    }
}

internal class ExecutionAdaptiveGoalRunner(
    private val appInventoryRepository: AppInventoryRepository,
    private val portalController: PortalController,
    private val logs: ActionLogRepository,
    private val uiState: MutableStateFlow<ExecutionUiState>,
    private val executionDiagnostics: ExecutionDiagnostics,
    private val goalVerifier: ExecutionGoalVerifier,
    private val cancellationResult: () -> ActionResult?,
    private val finish: (ActionResult) -> ActionResult,
    private val executeAction: suspend (DroidLmAction, String, Boolean, String?) -> ActionResult,
    private val requestConfirmation: suspend (String, DroidLmAction, String, String?, String?) -> Boolean,
    private val runInstallMonitor: suspend (InstallMonitorTarget, String, String?) -> ActionResult
) {
    suspend fun runIfSupported(action: DroidLmAction, transcript: String, diagnosticSessionId: String?): ActionResult? {
        val goal = goalFor(action, transcript) ?: return null
        return when (goal) {
            is ExecutionGoal.OpenApp -> runOpenAppGoal(goal, action, transcript, diagnosticSessionId)
        }
    }

    private suspend fun runOpenAppGoal(
        goal: ExecutionGoal.OpenApp,
        initialAction: DroidLmAction,
        transcript: String,
        diagnosticSessionId: String?
    ): ActionResult {
        debugEvent(
            diagnosticSessionId,
            "adaptive_goal_started",
            mapOf("goal" to "open_app", "appName" to goal.label, "packageName" to goal.packageName, "initialAction" to initialAction.displayName())
        )
        logs.log(ActionLogType.ACTION_STARTED, "Adaptive goal: open ${goal.label}", goal.packageName)
        uiState.value = uiState.value.copy(status = "Opening ${goal.label}", parsedAction = "GOAL OPEN_APP ${goal.packageName}")

        var lastResult = executeAction(initialAction, transcript, false, diagnosticSessionId)
        var observation = observe(goal, diagnosticSessionId, "after_initial_action")
        if (observation.verification.complete) {
            return finish(ActionResult.ok(observation.verification.message))
        }

        if (lastResult.success && initialAction !is DroidLmAction.OpenAppStoreListing && !observation.isPlayStoreActive) {
            if (observation.state?.packageName == null && observation.targetLaunchable) {
                debugEvent(
                    diagnosticSessionId,
                    "adaptive_goal_verification_unavailable",
                    mapOf("goal" to "open_app", "packageName" to goal.packageName, "message" to lastResult.message)
                )
                return finish(lastResult)
            }
            val waitAction = DroidLmAction.WaitForUi(
                packageName = goal.packageName,
                timeoutMs = OPEN_APP_VERIFY_TIMEOUT_MS,
                reason = "Wait for ${goal.label} to become active"
            )
            lastResult = executeAction(waitAction, transcript, false, diagnosticSessionId)
            observation = observe(goal, diagnosticSessionId, "after_open_wait")
            if (observation.verification.complete) {
                return finish(ActionResult.ok(observation.verification.message))
            }
        }

        if (goal.allowInstallRecovery && shouldAttemptInstallRecovery(initialAction, lastResult, observation)) {
            return recoverByInstalling(goal, initialAction, transcript, observation, diagnosticSessionId)
        }

        debugEvent(
            diagnosticSessionId,
            "adaptive_goal_failed",
            mapOf(
                "goal" to "open_app",
                "packageName" to goal.packageName,
                "lastSuccess" to lastResult.success,
                "lastErrorCode" to lastResult.errorCode,
                "verificationMessage" to observation.verification.message,
                "verificationActual" to observation.verification.actual
            )
        )
        return finish(
            if (lastResult.success) {
                ActionResult.fail("Could not complete goal: ${observation.verification.message}", "GOAL_NOT_REACHED")
            } else {
                lastResult
            }
        )
    }

    private suspend fun recoverByInstalling(
        goal: ExecutionGoal.OpenApp,
        initialAction: DroidLmAction,
        transcript: String,
        observation: GoalObservation,
        diagnosticSessionId: String?
    ): ActionResult {
        cancellationResult()?.let { return finish(it) }
        debugEvent(
            diagnosticSessionId,
            "adaptive_install_recovery_started",
            mapOf("packageName" to goal.packageName, "appName" to goal.label, "activePackage" to observation.state?.packageName)
        )
        var installApproved = false
        if (!observation.isPlayStoreActive) {
            val storeAction = DroidLmAction.OpenAppStoreListing(
                appName = goal.label,
                packageName = goal.packageName,
                reason = "${goal.label} is not installed or launchable; open Play Store before installing"
            )
            installApproved = requestConfirmation(
                transcript,
                storeAction,
                "${goal.label} is not installed or cannot be launched on this device.",
                diagnosticSessionId,
                "${goal.label} is not installed. Open Play Store, install it, and open it when ready?"
            )
            if (!installApproved) {
                return finish(ActionResult.fail("Install recovery was cancelled because confirmation was not accepted", "CONFIRMATION_REJECTED"))
            }
            val storeResult = executeAction(storeAction, transcript, false, diagnosticSessionId)
            if (!storeResult.success) return finish(storeResult)
        }

        val installAction = DroidLmAction.TapText(
            text = "Install",
            reason = "Install ${goal.label} from Play Store"
        )
        if (!installApproved) {
            val accepted = requestConfirmation(
                transcript,
                installAction,
                "Installing ${goal.label} from Play Store needs confirmation.",
                diagnosticSessionId,
                "Install ${goal.label} from Play Store and open it when it finishes?"
            )
            if (!accepted) {
                return finish(ActionResult.fail("Install recovery was cancelled because confirmation was not accepted", "CONFIRMATION_REJECTED"))
            }
        }

        val waitInstallButton = DroidLmAction.WaitForUi(
            text = "Install",
            packageName = PLAY_STORE_PACKAGE,
            timeoutMs = INSTALL_BUTTON_TIMEOUT_MS,
            reason = "Wait for Play Store install button"
        )
        val waitResult = executeAction(waitInstallButton, transcript, false, diagnosticSessionId)
        if (!waitResult.success && !isInstallAlreadyInProgress()) {
            debugEvent(
                diagnosticSessionId,
                "adaptive_install_button_missing",
                mapOf("packageName" to goal.packageName, "message" to waitResult.message, "errorCode" to waitResult.errorCode)
            )
            return finish(ActionResult.fail("Could not find the Play Store Install button for ${goal.label}", "INSTALL_BUTTON_NOT_VISIBLE"))
        }

        if (waitResult.success) {
            val tapResult = executeAction(installAction, transcript, false, diagnosticSessionId)
            if (!tapResult.success && !isInstallAlreadyInProgress()) return finish(tapResult)
        }

        return runInstallMonitor(
            InstallMonitorTarget(
                appName = goal.label,
                packageName = goal.packageName,
                sourceTranscript = transcript,
                openWhenInstalled = true
            ),
            transcript,
            diagnosticSessionId
        )
    }

    private fun shouldAttemptInstallRecovery(
        initialAction: DroidLmAction,
        lastResult: ActionResult,
        observation: GoalObservation
    ): Boolean {
        if (observation.targetLaunchable) return false
        if (initialAction is DroidLmAction.OpenAppStoreListing) return true
        if (observation.isPlayStoreActive) return true
        if (lastResult.errorCode in ExecutionDiagnostics.MISSING_OR_UNLAUNCHABLE_APP_ERRORS) return true
        return lastResult.message.contains("Play Store listing", ignoreCase = true)
    }

    private suspend fun observe(goal: ExecutionGoal.OpenApp, diagnosticSessionId: String?, stage: String): GoalObservation {
        val state = runCatching { portalController.getState() }.getOrNull()
        val packages = runCatching { appInventoryRepository.getInstalledApps(forceRefresh = true) }.getOrDefault(emptyList())
        val verification = goalVerifier.verify(goal, state, packages)
        val target = packages.firstOrNull { it.packageName == goal.packageName }
        debugEvent(
            diagnosticSessionId,
            "adaptive_goal_observed",
            mapOf(
                "stage" to stage,
                "packageName" to goal.packageName,
                "activePackage" to state?.packageName,
                "complete" to verification.complete,
                "verificationMessage" to verification.message,
                "verificationActual" to verification.actual,
                "targetInstalled" to (target != null),
                "targetLaunchable" to (target?.launchable == true)
            )
        )
        return GoalObservation(state, packages, verification, target)
    }

    private suspend fun isInstallAlreadyInProgress(): Boolean {
        val state = runCatching { portalController.getState() }.getOrNull() ?: return false
        return state.hasAnyVisibleText("Installing", "Pending", "Downloading", "Cancel")
    }

    private fun goalFor(action: DroidLmAction, transcript: String): ExecutionGoal? = when (action) {
        is DroidLmAction.OpenApp -> ExecutionGoal.OpenApp(
            label = action.appName?.takeIf { it.isNotBlank() } ?: action.packageName,
            packageName = action.packageName,
            allowInstallRecovery = true
        )
        is DroidLmAction.OpenAppStoreListing -> if (transcriptImpliesOpenGoal(transcript)) {
            ExecutionGoal.OpenApp(
                label = action.appName?.takeIf { it.isNotBlank() } ?: action.packageName,
                packageName = action.packageName,
                allowInstallRecovery = true
            )
        } else {
            null
        }
        else -> null
    }

    private fun transcriptImpliesOpenGoal(transcript: String): Boolean {
        val normalized = SpeechTextNormalizer.normalizeForRecognition(transcript)
        return normalized.startsWith("open ") ||
            normalized.startsWith("launch ") ||
            normalized.startsWith("start ") ||
            normalized.contains(" then open") ||
            normalized.contains("open it") ||
            normalized.contains("open after") ||
            normalized.contains("open when")
    }

    private fun PortalState.hasAnyVisibleText(vararg expectedTexts: String): Boolean = nodes.any { node ->
        val candidates = listOfNotNull(node.text, node.contentDescription, node.hintText, node.stateDescription)
            .map { it.lowercase() }
        expectedTexts.any { expected -> candidates.any { it.contains(expected.lowercase()) } }
    }

    private fun debugEvent(sessionId: String?, event: String, fields: Map<String, Any?> = emptyMap()) {
        executionDiagnostics.debugEvent(sessionId, event, fields)
    }

    private data class GoalObservation(
        val state: PortalState?,
        val packages: List<AppPackage>,
        val verification: ExecutionGoalVerification,
        val targetPackage: AppPackage?
    ) {
        val isPlayStoreActive: Boolean get() = state?.packageName == PLAY_STORE_PACKAGE
        val targetLaunchable: Boolean get() = targetPackage?.enabled != false && targetPackage?.launchable == true
    }

    companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val OPEN_APP_VERIFY_TIMEOUT_MS = 4_000
        private const val INSTALL_BUTTON_TIMEOUT_MS = 12_000
    }
}
