package com.techdivas.stressanalyzer.data

import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.suspendCancellableCoroutine

data class FirebaseSession(val uid: String, val email: String, val idToken: String)

class FirebaseAuthRepository(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) {
    fun currentUser(): FirebaseUser? = auth.currentUser

    fun addAuthStateListener(listener: FirebaseAuth.AuthStateListener) = auth.addAuthStateListener(listener)

    fun removeAuthStateListener(listener: FirebaseAuth.AuthStateListener) = auth.removeAuthStateListener(listener)

    suspend fun register(email: String, password: String): FirebaseSession =
        authenticate { auth.createUserWithEmailAndPassword(email, password) }

    suspend fun login(email: String, password: String): FirebaseSession =
        authenticate { auth.signInWithEmailAndPassword(email, password) }

    fun signOut() = auth.signOut()

    suspend fun deleteCurrentAccount() {
        val user = auth.currentUser ?: return
        user.delete().awaitCompletion()
    }

    suspend fun getIdToken(user: FirebaseUser): String =
        user.getIdToken(false).awaitResult().token
            ?: throw IllegalStateException("Firebase did not return an ID token.")

    private suspend fun authenticate(
        start: () -> Task<com.google.firebase.auth.AuthResult>
    ): FirebaseSession {
        val result = start().awaitResult()
        val user = result.user ?: auth.currentUser
            ?: throw IllegalStateException("Firebase did not return an authenticated user.")
        val idToken = getIdToken(user)
        return FirebaseSession(uid = user.uid, email = user.email.orEmpty(), idToken = idToken)
    }
}

private suspend fun Task<Void>.awaitCompletion(): Unit = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resumeWith(Result.success(Unit))
        else continuation.resumeWith(Result.failure(task.exception ?: IllegalStateException("The request failed.")))
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) {
            val result = task.result
            if (result != null) continuation.resumeWith(Result.success(result))
            else continuation.resumeWith(Result.failure(IllegalStateException("The authentication request returned no result.")))
        } else {
            continuation.resumeWith(Result.failure(task.exception ?: IllegalStateException("Authentication failed.")))
        }
    }
}

object AuthErrorMapper {
    fun userMessage(error: Throwable): String {
        val code = (error as? FirebaseAuthException)?.errorCode.orEmpty()
        return messageForCode(
            errorCode = code,
            networkFailure = error is FirebaseNetworkException || code == "ERROR_NETWORK_REQUEST_FAILED",
            invalidInput = error is IllegalArgumentException
        )
    }

    internal fun messageForCode(
        errorCode: String?,
        networkFailure: Boolean = false,
        invalidInput: Boolean = false
    ): String {
        val code = errorCode.orEmpty()
        return when {
            networkFailure ->
                "RHYTHM could not reach Firebase. Check your internet connection and try again."
            code == "ERROR_INVALID_EMAIL" -> "Enter a valid email address."
            code == "ERROR_EMAIL_ALREADY_IN_USE" -> "An account already uses this email. Try signing in instead."
            code == "ERROR_WEAK_PASSWORD" -> "Choose a stronger password with at least 6 characters."
            code in setOf("ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_USER_NOT_FOUND") ->
                "That email and password do not match. Check them and try again."
            code == "ERROR_TOO_MANY_REQUESTS" -> "There have been too many attempts. Please wait a little and try again."
            code == "ERROR_USER_DISABLED" -> "This account is disabled. Contact support for help."
            code == "ERROR_REQUIRES_RECENT_LOGIN" -> "For your security, sign in again before deleting this account."
            invalidInput -> "Enter a valid email and password."
            else -> "Authentication could not be completed. Please try again."
        }
    }
}
