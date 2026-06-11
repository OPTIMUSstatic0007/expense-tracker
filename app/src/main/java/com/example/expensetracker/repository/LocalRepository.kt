package com.example.expensetracker.repository

import android.content.Context
import com.example.expensetracker.local.ExpenseDatabase
import com.example.expensetracker.local.TransactionDao
import com.example.expensetracker.local.TransactionEntity
import kotlinx.coroutines.flow.Flow

class LocalRepository(private val context: Context) {

    private val transactionDao: TransactionDao
        get() = ExpenseDatabase.getInstance(context).transactionDao()

    suspend fun insertTransaction(transaction: TransactionEntity) {
        kotlinx.coroutines.Dispatchers.IO.let {
            kotlinx.coroutines.withContext(it) {
                val entityToInsert = if (transaction.sequenceId == 0L) {
                    val maxSeq = transactionDao.getMaxSequenceId() ?: 0L
                    transaction.copy(sequenceId = maxSeq + 1L)
                } else {
                    transaction
                }
                transactionDao.insertTransaction(entityToInsert)
            }
        }
    }

    suspend fun updateTransaction(transaction: TransactionEntity) {
        kotlinx.coroutines.Dispatchers.IO.let {
            kotlinx.coroutines.withContext(it) {
                transactionDao.updateTransaction(transaction)
            }
        }
    }

    suspend fun softDeleteTransaction(id: String) {
        kotlinx.coroutines.Dispatchers.IO.let {
            kotlinx.coroutines.withContext(it) {
                transactionDao.softDeleteTransaction(id = id, deleted = true, updatedAt = System.currentTimeMillis())
            }
        }
    }

    fun getAllTransactions(): Flow<List<TransactionEntity>> {
        return transactionDao.getAllTransactions()
    }

    fun getTransactionsByMonth(start: Long, end: Long): Flow<List<TransactionEntity>> {
        return transactionDao.getTransactionsByMonth(start, end)
    }
}
