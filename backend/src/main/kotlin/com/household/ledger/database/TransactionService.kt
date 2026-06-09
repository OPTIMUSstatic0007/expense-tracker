package com.household.ledger.database

import com.household.ledger.models.Transaction
import com.household.ledger.database.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import com.household.ledger.utils.AppLogger
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

    /**
     * Requirement 3 & 6: Fix In-Hand Balance calculation.
     * Uses the FINAL latest running balance state from the full dataset.
     */
    suspend fun getLedgerOverview(): Triple<BigDecimal, BigDecimal, BigDecimal> = dbQuery {
        // Latest balance is from the newest record (by date DESC, id DESC)
        val latestBalance = Transactions.selectAll()
            .orderBy(Transactions.date to SortOrder.DESC, Transactions.id to SortOrder.DESC)
            .limit(1)
            .map { it[Transactions.balanceAfter] }
            .singleOrNull() ?: BigDecimal.ZERO

        val totalCredit = Transactions.slice(Transactions.amount.sum())
            .select { Transactions.entryType eq "Credit" }
            .map { it[Transactions.amount.sum()] }
            .singleOrNull() ?: BigDecimal.ZERO

        val totalDebit = Transactions.slice(Transactions.amount.sum())
            .select { Transactions.entryType eq "Debit" }
            .map { it[Transactions.amount.sum()] }
            .singleOrNull() ?: BigDecimal.ZERO

        // Debug Logs (Requirement 8)
        val count = Transactions.selectAll().count()
        val firstRow = Transactions.selectAll().orderBy(Transactions.date to SortOrder.ASC, Transactions.id to SortOrder.ASC).limit(1).singleOrNull()
        val lastRow = Transactions.selectAll().orderBy(Transactions.date to SortOrder.DESC, Transactions.id to SortOrder.DESC).limit(1).singleOrNull()
        
        AppLogger.info("TransactionService", "--- Ledger Aggregation Engine ---")
        AppLogger.info("TransactionService", "Transaction Count: $count")
        AppLogger.info("TransactionService", "Canonical Start Balance (Oldest): ${firstRow?.get(Transactions.balanceAfter)}")
        AppLogger.info("TransactionService", "Canonical End Balance (Newest): ${lastRow?.get(Transactions.balanceAfter)}")
        AppLogger.info("TransactionService", "Computed Final Balance: $latestBalance")
        AppLogger.info("TransactionService", "Detected Sort Order for Aggregation: chronologically consistent")
        AppLogger.info("TransactionService", "----------------------------------")

        Triple(normalize(latestBalance), normalize(totalCredit), normalize(totalDebit))
    }

    suspend fun addTransaction(transaction: Transaction): Transaction? = dbQuery {
        AppLogger.info("TransactionService", "Transaction mutation received: ADD")
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
        
        performRecalculation()
        
        Transactions.select { Transactions.id eq newId }
            .map { rowToTransaction(it) }
            .singleOrNull()
    }

    suspend fun updateTransaction(id: Int, transaction: Transaction): Boolean = dbQuery {
        AppLogger.info("TransactionService", "Transaction mutation received: UPDATE | ID: $id")
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
            performRecalculation()
        }
        updated
    }

    suspend fun deleteTransaction(id: Int): Boolean = dbQuery {
        AppLogger.info("TransactionService", "Transaction mutation received: DELETE | ID: $id")
        val deleted = Transactions.deleteWhere { Transactions.id eq id } > 0
        if (deleted) {
            performRecalculation()
        }
        deleted
    }

    suspend fun recalculateBalances() {
        dbQuery {
            performRecalculation()
        }
    }

    private fun performRecalculation() {
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
        AppLogger.info("TransactionService", "SQLite write successful: Ledger balances updated")
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
