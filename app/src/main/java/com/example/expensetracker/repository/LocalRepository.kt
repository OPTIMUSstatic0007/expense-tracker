package com.example.expensetracker.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.expensetracker.cloud.PendingSyncOperation
import com.example.expensetracker.cloud.SyncManager
import com.example.expensetracker.local.ExpenseDatabase
import com.example.expensetracker.local.TransactionDao
import com.example.expensetracker.local.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class LocalRepository(
    private val context: Context,
    private val syncManager: SyncManager? = null
) {
    private val database: ExpenseDatabase
        get() = ExpenseDatabase.getInstance(context)

    private val transactionDao: TransactionDao
        get() = database.transactionDao()

    suspend fun insertTransaction(transaction: TransactionEntity) {
        val insertedEntity = withContext(Dispatchers.IO) {
            database.withTransaction {
                val entityToInsert = if (transaction.sequenceId == 0L) {
                    val maxSeq = transactionDao.getMaxSequenceId() ?: 0L
                    transaction.copy(sequenceId = maxSeq + 1L, version = maxOf(1L, transaction.version))
                } else {
                    transaction.copy(version = maxOf(1L, transaction.version))
                }
                transactionDao.insertTransaction(entityToInsert)
                entityToInsert
            }
        }
        syncManager?.onTransactionChanged(insertedEntity, PendingSyncOperation.OperationType.CREATE)
    }

    suspend fun updateTransaction(transaction: TransactionEntity) {
        val updatedEntity = withContext(Dispatchers.IO) {
            database.withTransaction {
                val existing = transactionDao.getTransactionById(transaction.id)
                val entityToUpdate = transaction.copy(
                    version = (existing?.version ?: transaction.version) + 1L,
                    syncPending = true
                )
                transactionDao.updateTransaction(entityToUpdate)
                entityToUpdate
            }
        }
        syncManager?.onTransactionChanged(updatedEntity, PendingSyncOperation.OperationType.UPDATE)
    }

    suspend fun softDeleteTransaction(id: String) {
        val deletedEntity = withContext(Dispatchers.IO) {
            database.withTransaction {
                transactionDao.softDeleteTransaction(id = id, deleted = true, updatedAt = System.currentTimeMillis())
                transactionDao.getTransactionById(id)
            }
        }
        if (deletedEntity != null) {
            syncManager?.onTransactionChanged(deletedEntity, PendingSyncOperation.OperationType.DELETE)
        }
    }

    suspend fun insertMissingTransactionsFromCloud(transactions: List<TransactionEntity>): Int {
        if (transactions.isEmpty()) {
            return 0
        }

        return withContext(Dispatchers.IO) {
            database.withTransaction {
                var nextSequenceId = (transactionDao.getMaxSequenceId() ?: 0L) + 1L
                val entitiesToInsert = transactions
                    .sortedWith(compareBy<TransactionEntity> { it.createdAt }.thenBy { it.updatedAt })
                    .map { transaction ->
                        if (transaction.sequenceId == 0L) {
                            transaction.copy(sequenceId = nextSequenceId++)
                        } else {
                            transaction
                        }
                    }
                transactionDao.insertTransactionsIgnoringDuplicates(entitiesToInsert)
                    .count { rowId -> rowId != -1L }
            }
        }
    }

    suspend fun insertTransactionFromCloud(transaction: TransactionEntity): Boolean {
        return withContext(Dispatchers.IO) {
            database.withTransaction {
                val entityToInsert = if (transaction.sequenceId == 0L) {
                    val maxSeq = transactionDao.getMaxSequenceId() ?: 0L
                    transaction.copy(sequenceId = maxSeq + 1L, syncPending = false)
                } else {
                    transaction.copy(syncPending = false)
                }
                transactionDao.insertTransactionsIgnoringDuplicates(listOf(entityToInsert))
                    .firstOrNull() != -1L
            }
        }
    }

    suspend fun updateTransactionFromCloud(transaction: TransactionEntity) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val existing = transactionDao.getTransactionById(transaction.id) ?: return@withTransaction
                transactionDao.updateTransaction(
                    transaction.copy(
                        sequenceId = existing.sequenceId,
                        syncPending = false
                    )
                )
            }
        }
    }

    suspend fun softDeleteTransactionFromCloud(id: String, updatedAt: Long, version: Long) {
        withContext(Dispatchers.IO) {
            transactionDao.softDeleteTransactionFromCloud(
                id = id,
                deleted = true,
                updatedAt = updatedAt,
                version = version
            )
        }
    }

    suspend fun getTransactionById(id: String): TransactionEntity? {
        return withContext(Dispatchers.IO) {
            transactionDao.getTransactionById(id)
        }
    }

    suspend fun getTransactionCount(): Int {
        return withContext(Dispatchers.IO) {
            transactionDao.getTransactionCount()
        }
    }

    fun getAllTransactions(): Flow<List<TransactionEntity>> {
        return transactionDao.getAllTransactions()
    }

    fun getTransactionsByMonth(start: Long, end: Long): Flow<List<TransactionEntity>> {
        return transactionDao.getTransactionsByMonth(start, end)
    }
}
