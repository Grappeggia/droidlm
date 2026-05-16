package ai.droidlm.settings

enum class WakeWordProvider {
    MANUAL_PUSH_TO_TALK,
    PORCUPINE
}

enum class ExecutionMode {
    LOCAL_RULE_FIRST,
    LOCAL_LLM_LOOP,
    AGENT_LOOP,
    MOBILERUN_CLOUD_TASK
}

enum class TranscriptionProvider {
    ANDROID_SPEECH_RECOGNIZER,
    OPENAI_DIRECT
}

data class DroidLmSettings(
    val openAiApiKeyConfigured: Boolean = false,
    val openAiModel: String = "gpt-5.4-nano",
    val wakePhrase: String = "DroidLM",
    val transcriptionProvider: TranscriptionProvider = TranscriptionProvider.ANDROID_SPEECH_RECOGNIZER,
    val preferOfflineSpeechRecognition: Boolean = true,
    val showPartialSpeechRecognition: Boolean = true,
    val floatingOverlayEnabled: Boolean = false,
    val overlayX: Int = 24,
    val overlayY: Int = 250,
    val hideOverlayDuringAutomation: Boolean = true,
    val wakeWordProvider: WakeWordProvider = WakeWordProvider.MANUAL_PUSH_TO_TALK,
    val picovoiceAccessKeyConfigured: Boolean = false,
    val wakeWordModelAssetPath: String = "",
    val wakeSensitivity: Float = 0.65f,
    val executionMode: ExecutionMode = ExecutionMode.LOCAL_RULE_FIRST,
    val autoAcceptSafePlans: Boolean = false,
    val mobilerunApiKeyConfigured: Boolean = false,
    val mobilerunDeviceId: String = "",
    val mobilerunLlmModel: String = "",
    val maxAutonomousSteps: Int = 12,
    val maxAgentTurns: Int = 6,
    val maxAgentToolCalls: Int = 12,
    val requireRiskConfirmation: Boolean = true,
    val onDeviceOcrEnabled: Boolean = true,
    val cloudScreenshotAnalysisEnabled: Boolean = false,
    val debugLoggingEnabled: Boolean = false,
    val onboardingCompletedVersion: Int = 0,
    val sensitiveAppScreenshotDenylist: String = DEFAULT_SENSITIVE_DENYLIST,
    val packageAllowlist: String = "",
    val packageDenylist: String = ""
) {
    companion object {
        const val DEFAULT_SENSITIVE_DENYLIST =
            "bank,password,authenticator,health,gmail,mail,message,signal,whatsapp,telegram,pay,crypto,wallet,docs"
    }
}
