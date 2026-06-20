package com.example.expensetracker.cloud

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(operation: PendingSyncOperation)

    @Query("DELETE FROM pending_sync_operations WHERE id = :id")
    fun delete(id: String)

    @Query(
        """
        UPDATE pending_sync_operations
        SET status = 'UPLOADING',
            lastAttemptAt = :lastAttemptAt,
            updatedAt = :lastAttemptAt
        WHERE id = :id
        """
    )
    fun markUploading(id: String, lastAttemptAt: Long)

    @Query(
        """
        UPDATE pending_sync_operations
        SET status = :status,
            retryCount = :retryCount,
            lastAttemptAt = :lastAttemptAt,
            updatedAt = :lastAttemptAt
        WHERE id = :id
        """
    )
    fun markFailed(
        id: String,
        retryCount: Int,
        status: PendingSyncOperation.Status,
        lastAttemptAt: Long
    )

    @Query(
        """
        UPDATE pending_sync_operations
        SET status = 'COMPLETED',
            lastAttemptAt = :completedAt,
            updatedAt = :completedAt
        WHERE id = :id
        """
    )
    fun markCompleted(id: String, completedAt: Long)

    @Query(
        """
        SELECT *
        FROM pending_sync_operations
        WHERE status IN ('PENDING', 'UPLOADING')
        ORDER BY createdAt ASC
        """
    )
    fun observePending(): Flow<List<PendingSyncOperation>>

    @Query(
        """
        SELECT *
        FROM pending_sync_operations
        WHERE ownerUid = :ownerUid
          AND status IN ('PENDING', 'UPLOADING')
          AND retryCount < :maxAttempts
        ORDER BY createdAt ASC
        LIMIT 1
        """
    )
    fun getNextPending(
        ownerUid: String,
        maxAttempts: Int
    ): PendingSyncOperation?

    @Query(
        """
        SELECT COUNT(*)
        FROM pending_sync_operations
        WHERE ownerUid = :ownerUid
          AND status IN ('PENDING', 'UPLOADING')
          AND retryCount < :maxAttempts
        """
    )
    fun countPending(
        ownerUid: String,
        maxAttempts: Int
    ): Int
}
