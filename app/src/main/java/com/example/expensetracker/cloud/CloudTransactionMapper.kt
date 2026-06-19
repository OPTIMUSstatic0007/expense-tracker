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
            version = maxOf(1L, entity.updatedAt),
            syncStatus = SyncStatus.SYNCED.name,
            amount = entity.amount,
            type = entity.type,
            category = entity.category,
            note = entity.note,
            deleted = entity.deleted
        )
    }
}
