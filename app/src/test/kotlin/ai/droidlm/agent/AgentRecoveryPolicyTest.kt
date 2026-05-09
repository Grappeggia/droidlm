package ai.droidlm.agent

import ai.droidlm.intent.DroidLmAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRecoveryPolicyTest {
    private val policy = AgentRecoveryPolicy()

    @Test fun missingOpenAppValidationRecoversWithStoreListing() {
        val recovery = policy.recoverValidationFailure(
            AgentToolCall(
                id = "call_1",
                name = "OPEN_APP",
                args = JSONObject().put("appName", "Google Sheets").put("packageName", "com.google.android.apps.docs.editors.sheets")
            ),
            "Package is not installed"
        )

        assertTrue(recovery?.action is DroidLmAction.OpenAppStoreListing)
        recovery!!.action as DroidLmAction.OpenAppStoreListing
        assertEquals("com.google.android.apps.docs.editors.sheets", recovery.action.packageName)
    }

    @Test fun failedOpenAppVerificationRecoversWithWaitForPackage() {
        val recovery = policy.recoverVerificationFailure(
            DroidLmAction.OpenApp("Drive", "com.google.android.apps.docs", "open"),
            AgentVerificationResult.failed("not active")
        )

        assertTrue(recovery?.action is DroidLmAction.WaitForUi)
        recovery!!.action as DroidLmAction.WaitForUi
        assertEquals("com.google.android.apps.docs", recovery.action.packageName)
    }

    @Test fun failedTextVerificationRecoversWithFindText() {
        val recovery = policy.recoverVerificationFailure(
            DroidLmAction.VerifyTextChange("final", "verify"),
            AgentVerificationResult.failed("missing")
        )

        assertTrue(recovery?.action is DroidLmAction.FindTextOnScreen)
        recovery!!.action as DroidLmAction.FindTextOnScreen
        assertEquals("final", recovery.action.text)
    }
}
