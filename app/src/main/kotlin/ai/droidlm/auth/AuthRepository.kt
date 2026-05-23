package ai.droidlm.auth

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

data class AuthUser(
    val uid: String,
    val displayName: String?,
    val email: String?,
    val emailVerified: Boolean = false
) {
    val displayLabel: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: "DroidLM user"
}

data class AuthState(
    val configured: Boolean = false,
    val ready: Boolean = false,
    val loading: Boolean = true,
    val user: AuthUser? = null,
    val message: String? = null
) {
    val signedIn: Boolean
        get() = user != null
}

sealed class AuthTokenResult {
    data class Success(val token: String) : AuthTokenResult()
    data object NoCurrentUser : AuthTokenResult()
    data object AuthNotConfigured : AuthTokenResult()
    data class Failure(
        val message: String,
        val errorClass: String,
        val errorCode: String? = null
    ) : AuthTokenResult()
}


interface AuthRepository {
    val authState: StateFlow<AuthState>

    suspend fun signInWithGoogle(context: Context)
    suspend fun signInWithEmail(email: String, password: String)
    suspend fun createAccountWithEmail(email: String, password: String)
    suspend fun sendPasswordReset(email: String)
    suspend fun currentIdTokenResult(forceRefresh: Boolean = false): AuthTokenResult
    suspend fun currentIdToken(forceRefresh: Boolean = false): String? = when (val result = currentIdTokenResult(forceRefresh)) {
        is AuthTokenResult.Success -> result.token
        else -> null
    }
    suspend fun reloadCurrentUser()

    fun signOut()
}
