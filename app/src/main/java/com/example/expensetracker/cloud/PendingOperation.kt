package com.example.expensetracker.cloud

data class PendingOperation(
    val id: String = "",
    val ownerUid: String = "",
    val deviceId: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val version: Long = 1L,
    val syncStatus: String = SyncStatus.PENDING.name,
    val transactionId: String = "",
    val operationType: OperationType = OperationType.CREATE,
    val attemptedAt: Long = 0L,
    val attemptCount: Int = 0
) {
    enum class OperationType {
        CREATE,
        UPDATE,
        DELETE
    }
}
