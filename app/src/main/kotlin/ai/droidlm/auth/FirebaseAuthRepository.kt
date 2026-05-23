package ai.droidlm.auth

import ai.droidlm.BuildConfig
import ai.droidlm.logs.ActionLogRepository
import ai.droidlm.logs.ActionLogType
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

class FirebaseAuthRepository(
    private val appContext: Context,
    private val logs: ActionLogRepository
) : AuthRepository {
    private val firebaseAuth: FirebaseAuth? = resolveFirebaseAuth()
    private val credentialManager by lazy { CredentialManager.create(appContext) }
    private val _authState = MutableStateFlow(initialAuthState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        updateUser(auth.currentUser, message = null)
    }

    init {
        firebaseAuth?.addAuthStateListener(authListener)
    }

    override suspend fun signInWithGoogle(context: Context) {
        val auth = configuredAuthOrNull() ?: return
        val webClientId = context.stringResourceOrBlank("default_web_client_id")
        if (webClientId.isBlank()) {
            setMessage("Google sign-in is missing the Firebase web client ID. Add a valid google-services.json.")
            logs.log(ActionLogType.ERROR, "Google sign-in is missing Firebase web client configuration")
            return
        }

        setLoading("Opening Google sign-in...")
        runCatching {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(true)
                .setServerClientId(webClientId)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val credential = credentialManager.getCredential(context, request).credential
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
            auth.signInWithCredential(firebaseCredential).await()
        }.onSuccess {
            updateUser(auth.currentUser, "Signed in with Google.")
            logs.log(ActionLogType.ACTION_RESULT, "Signed in with Google")
        }.onFailure { error ->
            val message = error.friendlyAuthMessage("Could not sign in with Google")
            setError(message)
            logs.log(ActionLogType.ERROR, "Google sign-in failed: $message")
        }
    }

    override suspend fun signInWithEmail(email: String, password: String) {
        val auth = configuredAuthOrNull() ?: return
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank() || password.isBlank()) {
            setMessage("Enter both email and password.")
            return
        }

        setLoading("Signing in...")
        runCatching {
            auth.signInWithEmailAndPassword(normalizedEmail, password).await()
        }.onSuccess {
            updateUser(auth.currentUser, "Signed in.")
            logs.log(ActionLogType.ACTION_RESULT, "Signed in with email")
        }.onFailure { error ->
            val message = error.friendlyAuthMessage("Could not sign in")
            setError(message)
            logs.log(ActionLogType.ERROR, "Email sign-in failed: $message")
        }
    }

    override suspend fun createAccountWithEmail(email: String, password: String) {
        val auth = configuredAuthOrNull() ?: return
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank() || password.isBlank()) {
            setMessage("Enter both email and password.")
            return
        }

        setLoading("Creating account...")
        runCatching {
            auth.createUserWithEmailAndPassword(normalizedEmail, password).await()
            auth.currentUser?.sendEmailVerification()?.await()
        }.onSuccess {
            updateUser(auth.currentUser, "Account created. Check your email to verify the account before continuing.")
            logs.log(ActionLogType.ACTION_RESULT, "Created Firebase email account and sent verification email")
        }.onFailure { error ->
            val message = error.friendlyAuthMessage("Could not create account")
            setError(message)
            logs.log(ActionLogType.ERROR, "Email account creation failed: $message")
        }
    }

    override suspend fun sendPasswordReset(email: String) {
        val auth = configuredAuthOrNull() ?: return
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank()) {
            setMessage("Enter your email address first.")
            return
        }

        setLoading("Sending reset email...")
        runCatching {
            auth.sendPasswordResetEmail(normalizedEmail).await()
        }.onSuccess {
            updateUser(auth.currentUser, "Password reset email sent.")
            logs.log(ActionLogType.ACTION_RESULT, "Sent password reset email")
        }.onFailure { error ->
            val message = error.friendlyAuthMessage("Could not send reset email")
            setError(message)
            logs.log(ActionLogType.ERROR, "Password reset failed: $message")
        }
    }

    override suspend fun currentIdTokenResult(forceRefresh: Boolean): AuthTokenResult {
        val auth = firebaseAuth ?: return AuthTokenResult.AuthNotConfigured
        val user = auth.currentUser ?: return AuthTokenResult.NoCurrentUser
        return runCatching { user.getIdToken(forceRefresh).await().token?.takeIf { it.isNotBlank() } }
            .fold(
                onSuccess = { token ->
                    token?.let(AuthTokenResult::Success)
                        ?: AuthTokenResult.Failure(
                            message = "Firebase returned an empty ID token",
                            errorClass = "EmptyFirebaseIdToken",
                            errorCode = "AUTH_TOKEN_EMPTY"
                        )
                },
                onFailure = { error ->
                    logs.log(
                        ActionLogType.ERROR,
                        "Firebase ID token refresh failed: ${error::class.java.simpleName}",
                        error.message
                    )
                    AuthTokenResult.Failure(
                        message = error.localizedMessage ?: error::class.java.simpleName,
                        errorClass = error::class.java.name,
                        errorCode = "AUTH_TOKEN_REFRESH_FAILED"
                    )
                }
            )
    }

    override suspend fun reloadCurrentUser() {
        val user = firebaseAuth?.currentUser ?: return
        runCatching { user.reload().await() }
        updateUser(firebaseAuth.currentUser, message = null)
    }

    override fun signOut() {
        val auth = firebaseAuth
        if (auth == null) {
            setNotConfigured()
            return
        }
        auth.signOut()
        updateUser(null, "Signed out.")
        logs.log(ActionLogType.ACTION_RESULT, "Signed out")
    }

    private fun configuredAuthOrNull(): FirebaseAuth? {
        val auth = firebaseAuth
        if (auth == null) setNotConfigured()
        return auth
    }

    private fun initialAuthState(): AuthState = firebaseAuth?.currentUser
        ?.let { user -> AuthState(configured = true, ready = true, loading = false, user = user.toAuthUser()) }
        ?: if (firebaseAuth == null) {
            AuthState(
                configured = false,
                ready = true,
                loading = false,
                message = "Firebase Auth is not configured. Add app/google-services.json to enable sign-in."
            )
        } else {
            AuthState(configured = true, ready = true, loading = false)
        }

    private fun resolveFirebaseAuth(): FirebaseAuth? {
        if (!BuildConfig.FIREBASE_AUTH_CONFIGURED) return null
        return runCatching {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseApp.initializeApp(appContext)
            }
            FirebaseAuth.getInstance()
        }.getOrNull()
    }

    private fun updateUser(user: FirebaseUser?, message: String?) {
        _authState.value = AuthState(
            configured = true,
            ready = true,
            loading = false,
            user = user?.toAuthUser(),
            message = message
        )
    }

    private fun setLoading(message: String) {
        val current = _authState.value
        _authState.value = current.copy(loading = true, message = message)
    }

    private fun setMessage(message: String) {
        val current = _authState.value
        _authState.value = current.copy(loading = false, message = message)
    }

    private fun setError(message: String) {
        val current = _authState.value
        _authState.value = current.copy(loading = false, message = message)
    }

    private fun setNotConfigured() {
        _authState.value = AuthState(
            configured = false,
            ready = true,
            loading = false,
            message = "Firebase Auth is not configured. Add app/google-services.json to enable sign-in."
        )
    }

    private fun FirebaseUser.toAuthUser(): AuthUser = AuthUser(
        uid = uid,
        displayName = displayName,
        email = email,
        emailVerified = isEmailVerified
    )

    private fun Context.stringResourceOrBlank(name: String): String {
        val id = resources.getIdentifier(name, "string", packageName)
        return if (id == 0) "" else runCatching { resources.getString(id) }.getOrDefault("")
    }

    private fun Throwable.friendlyAuthMessage(prefix: String): String = when (this) {
        is GetCredentialCancellationException -> "Google sign-in was canceled."
        is NoCredentialException -> "No Google account is available on this device."
        is GetCredentialException -> "$prefix: ${message ?: javaClass.simpleName}"
        is FirebaseAuthInvalidCredentialsException -> "$prefix: check the email and password."
        is FirebaseAuthInvalidUserException -> "$prefix: no matching account was found."
        is FirebaseAuthUserCollisionException -> "$prefix: that email is already in use."
        is FirebaseAuthWeakPasswordException -> "$prefix: choose a stronger password."
        is FirebaseTooManyRequestsException -> "$prefix: too many attempts. Try again later."
        else -> "$prefix: ${localizedMessage ?: javaClass.simpleName}"
    }
}
