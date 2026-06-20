package com.example.expensetracker.cloud

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_sync_operations",
    indices = [
        Index("ownerUid"),
        Index("status"),
        Index("createdAt"),
        Index("transactionId")
    ]
)
data class PendingSyncOperation(
    @PrimaryKey
    val id: String,
    val transactionId: String,
    val operationType: OperationType,
    val ownerUid: String,
    val deviceId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Long,
    val retryCount: Int = 0,
    val status: Status = Status.PENDING,
    val lastAttemptAt: Long = 0L
) {
    enum class OperationType {
        CREATE,
        UPDATE,
        DELETE
    }

    enum class Status {
        PENDING,
        UPLOADING,
        FAILED,
        COMPLETED
    }
}
