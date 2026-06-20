package com.example.expensetracker.cloud

import android.content.Context
import com.example.expensetracker.firebase.FirestoreConstants
import com.example.expensetracker.local.ExpenseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class PendingSyncRepository(
    context: Context
) {
    private val appContext = context.applicationContext

    private val pendingSyncDao: PendingSyncDao
        get() = ExpenseDatabase.getInstance(appContext).pendingSyncDao()

    suspend fun insert(operation: PendingSyncOperation) {
        withContext(Dispatchers.IO) {
            pendingSyncDao.insert(operation)
        }
        SyncLogger.info(
            "Queue insert: operation=${operation.operationType.name} transactionId=${operation.transactionId}"
        )
    }

    suspend fun delete(operation: PendingSyncOperation) {
        withContext(Dispatchers.IO) {
            pendingSyncDao.delete(operation.id)
        }
    }

    suspend fun markUploading(operation: PendingSyncOperation): PendingSyncOperation {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            pendingSyncDao.markUploading(operation.id, now)
        }
        return operation.copy(
            status = PendingSyncOperation.Status.UPLOADING,
            lastAttemptAt = now,
            updatedAt = now
        )
    }

    suspend fun markFailed(operation: PendingSyncOperation): PendingSyncOperation {
        val now = System.currentTimeMillis()
        val nextRetryCount = operation.retryCount + 1
        val nextStatus = if (nextRetryCount >= FirestoreConstants.SYNC_RETRY_MAX_ATTEMPTS) {
            PendingSyncOperation.Status.FAILED
        } else {
            PendingSyncOperation.Status.PENDING
        }
        withContext(Dispatchers.IO) {
            pendingSyncDao.markFailed(
                id = operation.id,
                retryCount = nextRetryCount,
                status = nextStatus,
                lastAttemptAt = now
            )
        }
        return operation.copy(
            retryCount = nextRetryCount,
            status = nextStatus,
            lastAttemptAt = now,
            updatedAt = now
        )
    }

    suspend fun markCompleted(operation: PendingSyncOperation): PendingSyncOperation {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            pendingSyncDao.markCompleted(operation.id, now)
        }
        return operation.copy(
            status = PendingSyncOperation.Status.COMPLETED,
            lastAttemptAt = now,
            updatedAt = now
        )
    }

    fun observePending(): Flow<List<PendingSyncOperation>> {
        return pendingSyncDao.observePending()
    }

    suspend fun getNextPending(ownerUid: String): PendingSyncOperation? {
        return withContext(Dispatchers.IO) {
            pendingSyncDao.getNextPending(
                ownerUid = ownerUid,
                maxAttempts = FirestoreConstants.SYNC_RETRY_MAX_ATTEMPTS
            )
        }
    }

    suspend fun countPending(ownerUid: String): Int {
        return withContext(Dispatchers.IO) {
            pendingSyncDao.countPending(
                ownerUid = ownerUid,
                maxAttempts = FirestoreConstants.SYNC_RETRY_MAX_ATTEMPTS
            )
        }
    }
}
