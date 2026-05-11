package ai.droidlm.portal

import ai.droidlm.diagnostics.SpeechDiagnosticsLogger
import ai.droidlm.runtime.AccessibilityRuntime

data class AccessibilityServiceDeps(
    val speechDiagnosticsLogger: SpeechDiagnosticsLogger,
    val accessibilityRuntime: AccessibilityRuntime
)
