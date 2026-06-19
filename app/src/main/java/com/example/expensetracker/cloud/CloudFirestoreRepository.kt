package com.example.expensetracker.cloud

import com.example.expensetracker.firebase.FirestoreConstants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class CloudFirestoreRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
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

    suspend fun createTransaction(transaction: CloudTransaction) {
        val user = requireUploadUser(transaction.ownerUid)
        transactionDocument(transaction.id)
            .set(transaction.toFirestoreMap(ownerUid = user.uid, deleted = false))
            .await()
    }

    suspend fun updateTransaction(transaction: CloudTransaction) {
        val user = requireUploadUser(transaction.ownerUid)
        transactionDocument(transaction.id)
            .update(transaction.toFirestoreMap(ownerUid = user.uid, deleted = false))
            .await()
    }

    suspend fun softDeleteTransaction(transaction: CloudTransaction) {
        val user = requireUploadUser(transaction.ownerUid)
        transactionDocument(transaction.id)
            .set(transaction.toFirestoreMap(ownerUid = user.uid, deleted = true))
            .await()
    }

    suspend fun downloadTransactions(): List<CloudTransaction> {
        val user = requireDownloadUser()
        val snapshot = transactionsCollection()
            .whereEqualTo(FirestoreConstants.FIELD_DELETED, false)
            .get()
            .await()

        return snapshot.documents.mapNotNull { document ->
            val transaction = document.toCloudTransaction()
            if (transaction.ownerUid != user.uid) {
                SyncLogger.warning("Download skipped owner mismatch for transactionId=${document.id}")
                null
            } else {
                transaction
            }
        }
    }

    private fun requireFirestore(): FirebaseFirestore {
        return firestore ?: error("CloudFirestoreRepository is not initialized")
    }

    private fun requireAuthenticatedUser(): FirebaseUser {
        return authenticatedUser ?: error("CloudFirestoreRepository requires an authenticated user")
    }

    private fun requireUploadUser(ownerUid: String): FirebaseUser {
        val currentUser = auth.currentUser
            ?: run {
                SyncLogger.warning("Authentication missing: Firebase user is required before upload")
                error("Authenticated Firebase user is required before upload")
            }

        if (currentUser.isAnonymous) {
            SyncLogger.warning("Authentication missing: anonymous Firebase user cannot upload")
            error("Anonymous Firebase users cannot upload transactions")
        }

        if (currentUser.uid.isBlank()) {
            SyncLogger.warning("Authentication missing: Firebase uid is blank")
            error("Authenticated Firebase uid must not be blank")
        }

        if (authenticatedUser?.uid != currentUser.uid) {
            SyncLogger.warning("Authentication missing: initialized user does not match current Firebase user")
            error("CloudFirestoreRepository user does not match current Firebase user")
        }

        if (ownerUid != currentUser.uid) {
            SyncLogger.warning("Authentication missing: ownerUid does not match authenticated Firebase user")
            error("Cloud transaction ownerUid must match authenticated Firebase user")
        }

        return currentUser
    }

    private fun requireDownloadUser(): FirebaseUser {
        val currentUser = auth.currentUser
            ?: run {
                SyncLogger.warning("Authentication missing: Firebase user is required before download")
                error("Authenticated Firebase user is required before download")
            }

        if (currentUser.isAnonymous) {
            SyncLogger.warning("Authentication missing: anonymous Firebase user cannot download")
            error("Anonymous Firebase users cannot download transactions")
        }

        if (currentUser.uid.isBlank()) {
            SyncLogger.warning("Authentication missing: Firebase uid is blank")
            error("Authenticated Firebase uid must not be blank")
        }

        if (authenticatedUser?.uid != currentUser.uid) {
            SyncLogger.warning("Authentication missing: initialized user does not match current Firebase user")
            error("CloudFirestoreRepository user does not match current Firebase user")
        }

        return currentUser
    }

    private fun DocumentSnapshot.toCloudTransaction(): CloudTransaction {
        return CloudTransaction(
            id = id,
            ownerUid = getString(FirestoreConstants.FIELD_OWNER_UID).orEmpty(),
            deviceId = getString(FirestoreConstants.FIELD_DEVICE_ID).orEmpty(),
            createdAt = getLong(FirestoreConstants.FIELD_CREATED_AT) ?: 0L,
            updatedAt = getLong(FirestoreConstants.FIELD_UPDATED_AT) ?: 0L,
            version = getLong(FirestoreConstants.FIELD_VERSION) ?: 1L,
            syncStatus = getString(FirestoreConstants.FIELD_SYNC_STATUS) ?: SyncStatus.SYNCED.name,
            amount = getDouble(FirestoreConstants.FIELD_AMOUNT) ?: 0.0,
            type = getString(FirestoreConstants.FIELD_TYPE).orEmpty(),
            category = getString(FirestoreConstants.FIELD_CATEGORY).orEmpty(),
            note = getString(FirestoreConstants.FIELD_NOTE).orEmpty(),
            deleted = getBoolean(FirestoreConstants.FIELD_DELETED) ?: false
        )
    }

    private fun CloudTransaction.toFirestoreMap(
        ownerUid: String,
        deleted: Boolean
    ): Map<String, Any> {
        return mapOf(
            FirestoreConstants.FIELD_ID to id,
            FirestoreConstants.FIELD_OWNER_UID to ownerUid,
            FirestoreConstants.FIELD_DEVICE_ID to deviceId,
            FirestoreConstants.FIELD_CREATED_AT to createdAt,
            FirestoreConstants.FIELD_UPDATED_AT to updatedAt,
            FirestoreConstants.FIELD_VERSION to version,
            FirestoreConstants.FIELD_SYNC_STATUS to syncStatus,
            FirestoreConstants.FIELD_AMOUNT to amount,
            FirestoreConstants.FIELD_TYPE to type,
            FirestoreConstants.FIELD_CATEGORY to category,
            FirestoreConstants.FIELD_NOTE to note,
            FirestoreConstants.FIELD_DELETED to deleted
        )
    }
}
