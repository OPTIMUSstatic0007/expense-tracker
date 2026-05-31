package com.household.ledger.database

import com.household.ledger.models.Transaction
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseFactory {
    fun init() {
        val databasePath = "backend/data"
        val databaseFile = File(databasePath)
        if (!databaseFile.exists()) {
            databaseFile.mkdirs()
        }
        
        val driverClassName = "org.sqlite.JDBC"
        val jdbcUrl = "jdbc:sqlite:$databasePath/ledger.db"
        val database = Database.connect(jdbcUrl, driverClassName)
        
        transaction(database) {
            SchemaUtils.create(Transactions)
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}

object Transactions : Table() {
    val id = integer("id").autoIncrement()
    val date = varchar("date", 50)
    val entryType = varchar("entry_type", 20)
    val amount = decimal("amount", precision = 20, scale = 4)
    val category = varchar("category", 100)
    val expenseType = varchar("expense_type", 100)
    val paidTo = varchar("paid_to", 100)
    val notes = text("notes")
    val balanceAfter = decimal("balance_after", precision = 20, scale = 4)

    override val primaryKey = PrimaryKey(id)
}
