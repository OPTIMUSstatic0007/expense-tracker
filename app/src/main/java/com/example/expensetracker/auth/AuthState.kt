package com.example.expensetracker.auth

import com.google.firebase.auth.FirebaseUser

/**
 * Sealed class representing the possible authentication states.
 */
sealed class AuthState {
    /** Checking for an existing session at startup. */
    object Loading : AuthState()

    /** User is signed in with a valid Firebase session. */
    data class Authenticated(val user: FirebaseUser) : AuthState()

    /** No active session — user must sign in. */
    object Unauthenticated : AuthState()
}
