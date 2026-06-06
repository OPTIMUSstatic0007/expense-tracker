package com.household.ledger.database

import com.household.ledger.storage.StoragePaths
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager

object DatabaseFactory {
    private lateinit var database: Database

    fun init() {
        val databaseFile = StoragePaths.databaseFile
        databaseFile.parentFile.mkdirs()
        
        val driverClassName = "org.sqlite.JDBC"
        val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"

        // 1. Connect Exposed foundation
        database = Database.connect(jdbcUrl, driverClassName)

        // 2. Enable WAL mode using raw JDBC connection BEFORE starting any transactions.
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL;")
            }
        }
        
        // 3. Safe to perform schema initialization
        transaction(database) {
            SchemaUtils.create(Transactions)
        }
    }

    /**
     * Safely closes the current connection and triggers re-init.
     * This is used during database restore operations.
     */
    fun resetConnection() {
        // Exposed doesn't have a direct "close all" for SQLite easily without reaching into internals
        // but we can ensure next operations use a fresh initialization.
        init()
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
