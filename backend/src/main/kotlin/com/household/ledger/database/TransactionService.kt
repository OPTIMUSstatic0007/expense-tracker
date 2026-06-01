package com.household.ledger.database

import com.household.ledger.models.Transaction
import com.household.ledger.database.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.math.BigDecimal
import java.math.RoundingMode

class TransactionService {
    private fun normalize(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)

    suspend fun getAllTransactions(): List<Transaction> = dbQuery {
        Transactions.selectAll()
            .orderBy(Transactions.date to SortOrder.DESC, Transactions.id to SortOrder.DESC)
            .map { rowToTransaction(it) }
    }

    suspend fun getPagedTransactions(page: Int, limit: Int): List<Transaction> = dbQuery {
        val offset = ((page - 1) * limit).toLong()
        Transactions.selectAll()
            .orderBy(Transactions.date to SortOrder.DESC, Transactions.id to SortOrder.DESC)
            .limit(limit, offset = offset)
            .map { rowToTransaction(it) }
    }

    suspend fun getTotalCount(): Long = dbQuery {
        Transactions.selectAll().count()
    }

    suspend fun addTransaction(transaction: Transaction): Transaction? = dbQuery {
        val amountNormalized = normalize(transaction.amount)
        val insertStatement = Transactions.insert {
            it[date] = transaction.date
            it[entryType] = transaction.entryType
            it[amount] = amountNormalized
            it[category] = transaction.category
            it[expenseType] = transaction.expenseType
            it[paidTo] = transaction.paidTo
            it[notes] = transaction.notes
            it[balanceAfter] = normalize(BigDecimal.ZERO)
        }
        
        val newId = insertStatement[Transactions.id]
        
        recalculateBalances()
        
        // Re-fetch to ensure we return the record with its calculated balanceAfter
        Transactions.select { Transactions.id eq newId }
            .map { rowToTransaction(it) }
            .singleOrNull()
    }

    suspend fun updateTransaction(id: Int, transaction: Transaction): Boolean = dbQuery {
        val amountNormalized = normalize(transaction.amount)
        val updated = Transactions.update({ Transactions.id eq id }) {
            it[date] = transaction.date
            it[entryType] = transaction.entryType
            it[amount] = amountNormalized
            it[category] = transaction.category
            it[expenseType] = transaction.expenseType
            it[paidTo] = transaction.paidTo
            it[notes] = transaction.notes
        } > 0
        
        if (updated) {
            recalculateBalances()
        }
        updated
    }

    suspend fun deleteTransaction(id: Int): Boolean = dbQuery {
        val deleted = Transactions.deleteWhere { Transactions.id eq id } > 0
        if (deleted) {
            recalculateBalances()
        }
        deleted
    }

    private fun recalculateBalances() {
        val all = Transactions.selectAll()
            .orderBy(Transactions.date to SortOrder.ASC, Transactions.id to SortOrder.ASC)
            .toList()
        
        var currentBalance = normalize(BigDecimal.ZERO)
        all.forEach { row ->
            val type = row[Transactions.entryType]
            val amount = normalize(row[Transactions.amount])
            
            currentBalance = if (type == "Credit") {
                currentBalance.add(amount)
            } else {
                currentBalance.subtract(amount)
            }
            
            Transactions.update({ Transactions.id eq row[Transactions.id] }) {
                it[balanceAfter] = normalize(currentBalance)
            }
        }
    }

    private fun rowToTransaction(row: ResultRow) = Transaction(
        id = row[Transactions.id],
        date = row[Transactions.date],
        entryType = row[Transactions.entryType],
        amount = normalize(row[Transactions.amount]),
        category = row[Transactions.category],
        expenseType = row[Transactions.expenseType],
        paidTo = row[Transactions.paidTo],
        notes = row[Transactions.notes],
        balanceAfter = normalize(row[Transactions.balanceAfter])
    )
}
