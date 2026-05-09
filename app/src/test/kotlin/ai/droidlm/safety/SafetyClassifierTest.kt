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

    @Test fun appStoreListingRequiresMandatoryConfirmation() {
        val decision = classifier.classify(
            "open google sheets",
            DroidLmAction.OpenAppStoreListing("Google Sheets", "com.google.android.apps.docs.editors.sheets", "missing app")
        )

        assertTrue(decision.requiresConfirmation)
        assertTrue(decision.mandatoryConfirmation)
    }

    @Test fun screenshotAnalysisAlwaysRequiresMandatoryConfirmation() {
        val action = DroidLmAction.AnalyzeScreenshot("read screen", "vision")
        val decision = classifier.classify("analyze screenshot", action, sensitiveDenylist = "bank,password")

        assertTrue(decision.requiresConfirmation)
        assertTrue(decision.mandatoryConfirmation)
    }
}
