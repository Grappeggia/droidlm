package ai.droidlm.intent

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextNormalizerTest {
    @Test fun commaConvertsInDictatedContext() {
        assertEquals(", revised", SpeechTextNormalizer.normalizeDictatedText("comma revised"))
    }

    @Test fun periodConvertsInDictatedContext() {
        assertEquals("done.", SpeechTextNormalizer.normalizeDictatedText("done period"))
    }

    @Test fun newLineConvertsInDictatedContext() {
        assertEquals("hello\nworld", SpeechTextNormalizer.normalizeDictatedText("hello new line world"))
    }

    @Test fun typeTheWordCommaPreservesLiteral() {
        assertEquals("comma", SpeechTextNormalizer.normalizeDictatedText("the word comma"))
    }
}
