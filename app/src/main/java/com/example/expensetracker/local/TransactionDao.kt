package com.example.expensetracker.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTransaction(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTransactionsIgnoringDuplicates(transactions: List<TransactionEntity>): List<Long>

    @Update
    fun updateTransaction(transaction: TransactionEntity)

    @Query("UPDATE transactions SET deleted = :deleted, updatedAt = :updatedAt, version = version + 1, syncPending = 1 WHERE id = :id")
    fun softDeleteTransaction(id: String, deleted: Boolean = true, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transactions SET deleted = :deleted, updatedAt = :updatedAt, version = :version, syncPending = 0 WHERE id = :id")
    fun softDeleteTransactionFromCloud(
        id: String,
        version: Long,
        deleted: Boolean = true,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    fun getTransactionById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY sequenceId DESC, createdAt DESC, updatedAt DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deleted = 0 AND createdAt >= :start AND createdAt <= :end ORDER BY sequenceId DESC, createdAt DESC, updatedAt DESC")
    fun getTransactionsByMonth(start: Long, end: Long): Flow<List<TransactionEntity>>

    @Query("SELECT MAX(sequenceId) FROM transactions")
    fun getMaxSequenceId(): Long?

    @Query("SELECT COUNT(*) FROM transactions")
    fun getTransactionCount(): Int
}
