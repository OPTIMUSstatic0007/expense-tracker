package com.example.expensetracker.repository

import com.example.expensetracker.local.TransactionDao
import com.example.expensetracker.local.TransactionEntity
import kotlinx.coroutines.flow.Flow

class LocalRepository(private val transactionDao: TransactionDao) {

    suspend fun insertTransaction(transaction: TransactionEntity) {
        kotlinx.coroutines.Dispatchers.IO.let {
            kotlinx.coroutines.withContext(it) {
                transactionDao.insertTransaction(transaction)
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
