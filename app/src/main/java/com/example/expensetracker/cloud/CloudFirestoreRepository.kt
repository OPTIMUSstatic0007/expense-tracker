package com.example.expensetracker.cloud

import com.example.expensetracker.firebase.FirestoreConstants
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore

class CloudFirestoreRepository {
    private var firestore: FirebaseFirestore? = null
    private var authenticatedUser: FirebaseUser? = null

    val isInitialized: Boolean
        get() = firestore != null && authenticatedUser != null

    fun initialize(user: FirebaseUser): Boolean {
        if (user.uid.isBlank()) {
            SyncLogger.warning("CloudFirestoreRepository initialization skipped: authenticated uid is blank")
            return false
        }

        if (isInitialized && authenticatedUser?.uid == user.uid) {
            SyncLogger.info("CloudFirestoreRepository already initialized for uid=${user.uid}")
            return true
        }

        return try {
            firestore = FirebaseFirestore.getInstance()
            authenticatedUser = user
            SyncLogger.info("CloudFirestoreRepository initialized for uid=${user.uid}")
            true
        } catch (exception: Exception) {
            firestore = null
            authenticatedUser = null
            SyncLogger.error("CloudFirestoreRepository initialization failed", exception)
            false
        }
    }

    fun clear() {
        firestore = null
        authenticatedUser = null
        SyncLogger.info("CloudFirestoreRepository cleared")
    }

    fun userDocument(): DocumentReference {
        val user = requireAuthenticatedUser()
        return requireFirestore()
            .collection(FirestoreConstants.COLLECTION_USERS)
            .document(user.uid)
    }

    fun transactionsCollection(): CollectionReference {
        return userDocument().collection(FirestoreConstants.COLLECTION_TRANSACTIONS)
    }

    fun transactionDocument(transactionId: String): DocumentReference {
        return transactionsCollection().document(transactionId)
    }

    fun profileDocument(): DocumentReference {
        return userDocument()
            .collection(FirestoreConstants.COLLECTION_METADATA)
            .document(FirestoreConstants.DOC_PROFILE)
    }

    fun settingsDocument(): DocumentReference {
        return userDocument()
            .collection(FirestoreConstants.COLLECTION_METADATA)
            .document(FirestoreConstants.DOC_SETTINGS)
    }

    fun syncDocument(): DocumentReference {
        return userDocument()
            .collection(FirestoreConstants.COLLECTION_METADATA)
            .document(FirestoreConstants.DOC_SYNC)
    }

    fun createTransaction(@Suppress("UNUSED_PARAMETER") transaction: CloudTransaction): Nothing {
        throw NotImplementedError("Transaction uploads are intentionally out of scope for Sprint 1")
    }

    fun updateTransaction(@Suppress("UNUSED_PARAMETER") transaction: CloudTransaction): Nothing {
        throw NotImplementedError("Transaction updates are intentionally out of scope for Sprint 1")
    }

    fun deleteTransaction(@Suppress("UNUSED_PARAMETER") transactionId: String): Nothing {
        throw NotImplementedError("Transaction deletes are intentionally out of scope for Sprint 1")
    }

    private fun requireFirestore(): FirebaseFirestore {
        return firestore ?: error("CloudFirestoreRepository is not initialized")
    }

    private fun requireAuthenticatedUser(): FirebaseUser {
        return authenticatedUser ?: error("CloudFirestoreRepository requires an authenticated user")
    }
}
