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

    @Update
    fun updateTransaction(transaction: TransactionEntity)

    @Query("UPDATE transactions SET deleted = :deleted, updatedAt = :updatedAt, syncPending = 1 WHERE id = :id")
    fun softDeleteTransaction(id: String, deleted: Boolean = true, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY createdAt DESC, updatedAt DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deleted = 0 AND createdAt >= :start AND createdAt <= :end ORDER BY createdAt DESC, updatedAt DESC")
    fun getTransactionsByMonth(start: Long, end: Long): Flow<List<TransactionEntity>>
}
