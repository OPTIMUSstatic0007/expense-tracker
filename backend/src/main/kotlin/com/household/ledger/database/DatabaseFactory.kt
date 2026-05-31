package com.household.ledger.database

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.DriverManager

object DatabaseFactory {
    fun init() {
        val databasePath = "backend/data"
        val databaseFile = File(databasePath)
        if (!databaseFile.exists()) {
            databaseFile.mkdirs()
        }
        
        val driverClassName = "org.sqlite.JDBC"
        val jdbcUrl = "jdbc:sqlite:$databasePath/ledger.db"

        // 1. Connect Exposed foundation
        Database.connect(jdbcUrl, driverClassName)

        // 2. Enable WAL mode using raw JDBC connection BEFORE starting any transactions.
        // SQLite forbids changing journal_mode from within an Exposed transaction block.
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL;")
            }
        }
        
        // 3. Safe to perform schema initialization
        transaction {
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
