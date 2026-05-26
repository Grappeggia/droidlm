package ai.droidlm.update

import ai.droidlm.BuildConfig
import ai.droidlm.download.formatDownloadProgress

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
    val availableVersionName: String? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val progressFraction: Float? = null
) {
    val isBusy: Boolean
        get() = phase == DebugUpdatePhase.CHECKING ||
            phase == DebugUpdatePhase.DOWNLOADING ||
            phase == DebugUpdatePhase.OPENING_INSTALLER

    val requiresInstallPermission: Boolean
        get() = phase == DebugUpdatePhase.AWAITING_INSTALL_PERMISSION

    val progressLabel: String?
        get() = if (downloadedBytes != null || totalBytes != null) {
            formatDownloadProgress(downloadedBytes = downloadedBytes ?: 0L, totalBytes = totalBytes)
        } else {
            null
        }
}
