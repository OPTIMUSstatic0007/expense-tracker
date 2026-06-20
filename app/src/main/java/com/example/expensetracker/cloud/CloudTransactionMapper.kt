package com.example.expensetracker.cloud

import com.example.expensetracker.local.TransactionEntity

object CloudTransactionMapper {
    fun fromEntity(
        entity: TransactionEntity,
        ownerUid: String,
        deviceId: String
    ): CloudTransaction {
        return CloudTransaction(
            id = entity.id,
            ownerUid = ownerUid,
            deviceId = deviceId,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            version = entity.version,
            syncStatus = SyncStatus.SYNCED.name,
            amount = entity.amount,
            type = entity.type,
            category = entity.category,
            note = entity.note,
            deleted = entity.deleted
        )
    }

    fun toEntity(transaction: CloudTransaction): TransactionEntity {
        return TransactionEntity(
            id = transaction.id,
            amount = transaction.amount,
            type = transaction.type,
            category = transaction.category,
            note = transaction.note,
            createdAt = transaction.createdAt,
            updatedAt = transaction.updatedAt,
            version = transaction.version,
            deleted = transaction.deleted,
            syncPending = false
        )
    }
}
