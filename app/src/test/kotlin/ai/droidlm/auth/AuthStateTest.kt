package ai.droidlm.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStateTest {
    @Test fun displayLabelPrefersDisplayName() {
        val user = AuthUser(uid = "uid-1", displayName = "Example User", email = "example.user@example.test")

        assertEquals("Example User", user.displayLabel)
    }

    @Test fun displayLabelFallsBackToEmailPrefix() {
        val user = AuthUser(uid = "uid-1", displayName = " ", email = "example.user@example.test")

        assertEquals("example.user", user.displayLabel)
    }

    @Test fun displayLabelUsesDefaultWhenNameAndEmailAreMissing() {
        val user = AuthUser(uid = "uid-1", displayName = null, email = null)

        assertEquals("DroidLM user", user.displayLabel)
    }

    @Test fun signedInReflectsCurrentUser() {
        assertFalse(AuthState(user = null).signedIn)
        assertTrue(AuthState(user = AuthUser(uid = "uid-1", displayName = null, email = null)).signedIn)
    }
}
