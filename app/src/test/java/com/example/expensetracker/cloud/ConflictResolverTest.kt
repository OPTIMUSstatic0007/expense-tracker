package com.example.expensetracker.cloud

import com.example.expensetracker.local.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ConflictResolverTest {
    @Test
    fun higherLocalVersionWins() {
        val decision = ConflictResolver.resolve(
            local = localTransaction(version = 3L, updatedAt = 100L),
            remote = cloudTransaction(version = 2L, updatedAt = 200L)
        )

        assertEquals(ConflictResolver.Decision.USE_LOCAL, decision)
    }

    @Test
    fun higherRemoteVersionWins() {
        val decision = ConflictResolver.resolve(
            local = localTransaction(version = 2L, updatedAt = 200L),
            remote = cloudTransaction(version = 3L, updatedAt = 100L)
        )

        assertEquals(ConflictResolver.Decision.USE_REMOTE, decision)
    }

    @Test
    fun higherUpdatedAtWinsWhenVersionsMatch() {
        val localWins = ConflictResolver.resolve(
            local = localTransaction(version = 2L, updatedAt = 300L),
            remote = cloudTransaction(version = 2L, updatedAt = 200L)
        )
        val remoteWins = ConflictResolver.resolve(
            local = localTransaction(version = 2L, updatedAt = 200L),
            remote = cloudTransaction(version = 2L, updatedAt = 300L)
        )

        assertEquals(ConflictResolver.Decision.USE_LOCAL, localWins)
        assertEquals(ConflictResolver.Decision.USE_REMOTE, remoteWins)
    }

    @Test
    fun cloudWinsWhenVersionAndUpdatedAtTieButPayloadDiffers() {
        val decision = ConflictResolver.resolve(
            local = localTransaction(version = 2L, updatedAt = 300L, note = "local"),
            remote = cloudTransaction(version = 2L, updatedAt = 300L, note = "remote")
        )

        assertEquals(ConflictResolver.Decision.USE_REMOTE, decision)
    }

    @Test
    fun identicalPayloadReturnsNoChange() {
        val decision = ConflictResolver.resolve(
            local = localTransaction(version = 2L, updatedAt = 300L, deleted = true),
            remote = cloudTransaction(version = 2L, updatedAt = 300L, deleted = true)
        )

        assertEquals(ConflictResolver.Decision.NO_CHANGE, decision)
    }

    private fun localTransaction(
        version: Long,
        updatedAt: Long,
        note: String = "note",
        deleted: Boolean = false
    ): TransactionEntity {
        return TransactionEntity(
            id = "transaction-id",
            amount = 10.0,
            type = "Debit",
            category = "General",
            note = note,
            createdAt = 50L,
            updatedAt = updatedAt,
            deleted = deleted,
            syncPending = true,
            version = version
        )
    }

    private fun cloudTransaction(
        version: Long,
        updatedAt: Long,
        note: String = "note",
        deleted: Boolean = false
    ): CloudTransaction {
        return CloudTransaction(
            id = "transaction-id",
            ownerUid = "owner",
            deviceId = "device",
            createdAt = 50L,
            updatedAt = updatedAt,
            version = version,
            amount = 10.0,
            type = "Debit",
            category = "General",
            note = note,
            deleted = deleted
        )
    }
}
