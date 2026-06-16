package com.example.expensetracker.auth

import com.example.expensetracker.R
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GoogleAuthManager(
    private val context: Context
) {

    private val firebaseAuth = FirebaseAuth.getInstance()

    private val gso = GoogleSignInOptions.Builder(
        GoogleSignInOptions.DEFAULT_SIGN_IN
    )
        .requestIdToken(
            context.getString(R.string.default_web_client_id)
        )
        .requestEmail()
        .build()

    private val googleSignInClient =
        GoogleSignIn.getClient(context, gso)

    // ═══ AUTH STATE MANAGEMENT ═══
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        _authState.value = if (user != null) {
            AuthState.Authenticated(user)
        } else {
            AuthState.Unauthenticated
        }
    }

    init {
        // Set initial state based on existing session
        val currentUser = firebaseAuth.currentUser
        _authState.value = if (currentUser != null) {
            AuthState.Authenticated(currentUser)
        } else {
            AuthState.Unauthenticated
        }
        // Listen for future auth state changes (sign in / sign out)
        firebaseAuth.addAuthStateListener(authStateListener)
    }

    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    fun handleSignInResult(
        data: Intent?,
        onSuccess: (String?) -> Unit,
        onError: (String) -> Unit
    ) {

        val task = GoogleSignIn.getSignedInAccountFromIntent(data)

        try {

            val account = task.getResult(ApiException::class.java)

            val credential = GoogleAuthProvider.getCredential(
                account.idToken,
                null
            )

            firebaseAuth.signInWithCredential(credential)
                .addOnCompleteListener {

                    if (it.isSuccessful) {

                        val user = firebaseAuth.currentUser

                        onSuccess(user?.email)

                    } else {

                        onError(
                            it.exception?.message ?: "Firebase auth failed"
                        )
                    }
                }

        } catch (e: Exception) {

            onError(e.message ?: "Google sign in failed")
        }
    }

    fun signOut() {

        firebaseAuth.signOut()

        googleSignInClient.signOut()
    }

    fun currentUser() = firebaseAuth.currentUser
}