package ai.droidlm.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowlistStateTest {
    @Test fun readyIsFalseWhileChecking() {
        assertFalse(AllowlistState(checking = true).ready)
    }

    @Test fun readyIsTrueWhenCheckFinished() {
        assertTrue(AllowlistState(checking = false).ready)
    }
}
