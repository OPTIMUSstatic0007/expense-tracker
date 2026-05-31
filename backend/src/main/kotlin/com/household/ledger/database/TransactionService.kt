package com.household.ledger.database

import com.household.ledger.models.Transaction
import com.household.ledger.database.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.math.BigDecimal

class TransactionService {
    suspend fun getAllTransactions(): List<Transaction> = dbQuery {
        Transactions.selectAll()
            .orderBy(Transactions.date to SortOrder.ASC, Transactions.id to SortOrder.ASC)
            .map { rowToTransaction(it) }
    }

    suspend fun addTransaction(transaction: Transaction): Transaction? = dbQuery {
        val insertStatement = Transactions.insert {
            it[date] = transaction.date
            it[entryType] = transaction.entryType
            it[amount] = transaction.amount
            it[category] = transaction.category
            it[expenseType] = transaction.expenseType
            it[paidTo] = transaction.paidTo
            it[notes] = transaction.notes
            it[balanceAfter] = BigDecimal.ZERO 
        }
        
        recalculateBalances()
        
        insertStatement.resultedValues?.singleOrNull()?.let { rowToTransaction(it) }
    }

    suspend fun updateTransaction(id: Int, transaction: Transaction): Boolean = dbQuery {
        val updated = Transactions.update({ Transactions.id eq id }) {
            it[date] = transaction.date
            it[entryType] = transaction.entryType
            it[amount] = transaction.amount
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
        
        var currentBalance = BigDecimal.ZERO
        all.forEach { row ->
            val type = row[Transactions.entryType]
            val amount = row[Transactions.amount]
            if (type == "Credit") {
                currentBalance = currentBalance.add(amount)
            } else {
                currentBalance = currentBalance.subtract(amount)
            }
            
            Transactions.update({ Transactions.id eq row[Transactions.id] }) {
                it[balanceAfter] = currentBalance
            }
        }
    }

    private fun rowToTransaction(row: ResultRow) = Transaction(
        id = row[Transactions.id],
        date = row[Transactions.date],
        entryType = row[Transactions.entryType],
        amount = row[Transactions.amount],
        category = row[Transactions.category],
        expenseType = row[Transactions.expenseType],
        paidTo = row[Transactions.paidTo],
        notes = row[Transactions.notes],
        balanceAfter = row[Transactions.balanceAfter]
    )
}
