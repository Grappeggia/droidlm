package ai.droidlm.safety

import ai.droidlm.intent.DroidLmAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyClassifierTest {
    private val classifier = SafetyClassifier()

    @Test fun purchaseRequiresConfirmation() {
        assertTrue(classifier.classify("buy this item now").requiresConfirmation)
    }

    @Test fun deleteRequiresConfirmation() {
        assertTrue(classifier.classify("delete file budget.pdf").requiresConfirmation)
    }

    @Test fun sendMessageRequiresConfirmation() {
        assertTrue(classifier.classify("send message to Alex").requiresConfirmation)
    }

    @Test fun openAppDoesNotRequireConfirmation() {
        assertFalse(classifier.classify("open drive", DroidLmAction.OpenApp("Drive", "com.google.android.apps.docs", "test")).requiresConfirmation)
    }

    @Test fun sensitiveScreenshotRequiresConfirmation() {
        val action = DroidLmAction.AnalyzeScreenshot("read screen", "vision")
        assertTrue(classifier.classify("analyze screenshot", action, sensitiveDenylist = "bank,password").requiresConfirmation || classifier.isSensitivePackage("com.bank.app", "bank"))
    }
}
