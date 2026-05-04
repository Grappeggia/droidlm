package ai.droidlm.voice

import android.speech.SpeechRecognizer

object SpeechRecognitionErrorMapper {
    fun messageFor(errorCode: Int, preferOffline: Boolean = false, languageTag: String? = null): String = when (errorCode) {
        SpeechRecognizer.ERROR_AUDIO -> "The speech recognizer could not access microphone audio."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was cancelled or interrupted."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for speech recognition."
        SpeechRecognizer.ERROR_NETWORK -> "Speech recognition needs network access on this device. Try again or disable offline preference."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out while waiting for the speech service."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized. Try speaking again after tapping Push to Talk."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Wait a moment and try again."
        SpeechRecognizer.ERROR_SERVER -> "The device speech service returned an error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard before the timeout."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> languageMessage("not supported", preferOffline, languageTag)
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> languageMessage("not available", preferOffline, languageTag)
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "This device cannot report speech recognition support."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech recognition is temporarily rate limited on this device."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "The device speech service disconnected."
        else -> "Speech recognition failed with Android error $errorCode."
    }

    private fun languageMessage(status: String, preferOffline: Boolean, languageTag: String?): String {
        val language = languageTag?.takeIf { it.isNotBlank() } ?: "the selected language"
        return if (preferOffline) {
            "Offline Android speech recognition for $language is $status on this device. Install that offline speech language in Android voice input settings, or choose another on-device speech language."
        } else {
            "Android speech recognition for $language is $status on this device. Choose another speech language or enable/install the device speech recognizer."
        }
    }
}
