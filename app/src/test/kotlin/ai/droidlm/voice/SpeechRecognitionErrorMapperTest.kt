package ai.droidlm.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeechRecognitionErrorMapperTest {
    @Test fun noMatchIsUserActionable() {
        val message = SpeechRecognitionErrorMapper.messageFor(SpeechRecognizer.ERROR_NO_MATCH)
        assertTrue(message.contains("No speech", ignoreCase = true))
        assertTrue(message.contains("Push to Talk", ignoreCase = true))
    }

    @Test fun missingPermissionMentionsMicrophone() {
        val message = SpeechRecognitionErrorMapper.messageFor(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertTrue(message.contains("Microphone", ignoreCase = true))
    }
}
