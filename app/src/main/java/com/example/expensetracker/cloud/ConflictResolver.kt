package com.example.expensetracker.cloud

import com.example.expensetracker.local.TransactionEntity

object ConflictResolver {
    enum class Decision {
        USE_LOCAL,
        USE_REMOTE,
        NO_CHANGE
    }

    fun resolve(
        local: TransactionEntity,
        remote: CloudTransaction
    ): Decision {
        return when {
            local.version > remote.version -> Decision.USE_LOCAL
            local.version < remote.version -> Decision.USE_REMOTE
            local.updatedAt > remote.updatedAt -> Decision.USE_LOCAL
            local.updatedAt < remote.updatedAt -> Decision.USE_REMOTE
            hasSameTransactionData(local, remote) -> Decision.NO_CHANGE
            else -> Decision.USE_REMOTE
        }
    }

    private fun hasSameTransactionData(
        local: TransactionEntity,
        remote: CloudTransaction
    ): Boolean {
        return local.id == remote.id &&
                local.amount == remote.amount &&
                local.type == remote.type &&
                local.category == remote.category &&
                local.note == remote.note &&
                local.createdAt == remote.createdAt &&
                local.updatedAt == remote.updatedAt &&
                local.version == remote.version &&
                local.deleted == remote.deleted
    }
}
