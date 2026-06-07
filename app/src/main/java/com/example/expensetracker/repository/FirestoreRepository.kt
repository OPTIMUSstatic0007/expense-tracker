package com.example.expensetracker.repository

import android.util.Log
import com.example.expensetracker.firebase.FirestorePaths
import com.example.expensetracker.model.Transaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

class FirestoreRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    suspend fun uploadTransaction(transaction: Transaction) {
        val uid = requireCurrentUserId()
        val transactionId = requireTransactionId(transaction.id)

        try {
            transactionsCollection(uid)
                .document(transactionId)
                .set(transaction)
                .await()

            Log.d(TAG, "Uploaded transaction $transactionId for user $uid")
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to upload transaction $transactionId for user $uid", exception)
            throw FirestoreRepositoryException(
                message = "Unable to upload transaction.",
                cause = exception
            )
        }
    }

    suspend fun fetchTransactions(): List<Transaction> {
        val uid = requireCurrentUserId()

        return try {
            val snapshot = transactionsCollection(uid)
                .orderBy(FIELD_TIMESTAMP, Query.Direction.DESCENDING)
                .get()
                .await()

            snapshot.documents.mapNotNull { document ->
                try {
                    document.toObject(Transaction::class.java)?.copy(
                        id = document.getString(FIELD_ID)?.takeIf { it.isNotBlank() } ?: document.id
                    )
                } catch (exception: Exception) {
                    Log.e(TAG, "Skipping malformed transaction document ${document.id}", exception)
                    null
                }
            }.also { transactions ->
                Log.d(TAG, "Fetched ${transactions.size} transactions for user $uid")
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to fetch transactions for user $uid", exception)
            throw FirestoreRepositoryException(
                message = "Unable to fetch transactions.",
                cause = exception
            )
        }
    }

    suspend fun deleteTransaction(transactionId: String) {
        val uid = requireCurrentUserId()
        val validTransactionId = requireTransactionId(transactionId)

        try {
            transactionsCollection(uid)
                .document(validTransactionId)
                .delete()
                .await()

            Log.d(TAG, "Deleted transaction $validTransactionId for user $uid")
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to delete transaction $validTransactionId for user $uid", exception)
            throw FirestoreRepositoryException(
                message = "Unable to delete transaction.",
                cause = exception
            )
        }
    }

    private fun transactionsCollection(uid: String): CollectionReference {
        return firestore
            .collection(FirestorePaths.USERS)
            .document(uid)
            .collection(FirestorePaths.TRANSACTIONS)
    }

    private fun requireCurrentUserId(): String {
        return auth.currentUser?.uid
            ?: throw FirestoreRepositoryException("A signed-in Firebase user is required.")
    }

    private fun requireTransactionId(transactionId: String): String {
        return transactionId.takeIf { it.isNotBlank() }
            ?: throw FirestoreRepositoryException("Transaction id must not be blank.")
    }

    private companion object {
        const val TAG = "FirestoreRepository"
        const val FIELD_ID = "id"
        const val FIELD_TIMESTAMP = "timestamp"
    }
}

class FirestoreRepositoryException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
