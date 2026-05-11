package ai.droidlm.update

import ai.droidlm.BuildConfig

enum class DebugUpdatePhase {
    IDLE,
    CHECKING,
    DOWNLOADING,
    AWAITING_INSTALL_PERMISSION,
    OPENING_INSTALLER,
    ALREADY_LATEST,
    ERROR
}

data class DebugUpdateUiState(
    val visible: Boolean = BuildConfig.DEBUG,
    val phase: DebugUpdatePhase = DebugUpdatePhase.IDLE,
    val statusMessage: String? = null,
    val availableVersionName: String? = null
) {
    val isBusy: Boolean
        get() = phase == DebugUpdatePhase.CHECKING ||
            phase == DebugUpdatePhase.DOWNLOADING ||
            phase == DebugUpdatePhase.OPENING_INSTALLER

    val requiresInstallPermission: Boolean
        get() = phase == DebugUpdatePhase.AWAITING_INSTALL_PERMISSION
}
