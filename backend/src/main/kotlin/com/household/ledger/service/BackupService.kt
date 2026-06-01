package com.household.ledger.service

import com.household.ledger.models.Transaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.nio.charset.StandardCharsets

@Serializable
data class BackupMeta(
    var transactionsSinceLastBackup: Int = 0,
    var lastBackupTime: String = "Never"
)

@Serializable
data class BackupHistoryItem(
    val filename: String,
    val type: String, // "auto", "manual", "emergency"
    val timestamp: String,
    val transactionCount: Int,
    val balanceAtBackup: String
)

class BackupService {
    private val logger = LoggerFactory.getLogger(BackupService::class.java)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Requirement: Resolve database file location reliably.
     */
    private val dbFile: File by lazy {
        val userDir = System.getProperty("user.dir")
        val possiblePaths = listOf(
            File(userDir, "backend/backend/data/ledger.db"),
            File(userDir, "backend/data/ledger.db"),
            File(userDir, "data/ledger.db"),
            File(userDir, "ledger.db")
        )
        possiblePaths.firstOrNull { it.exists() } ?: File(userDir, "backend/backend/data/ledger.db")
    }

    /**
     * Requirement: Store backups in backend/backend/data/backups/
     */
    private val baseBackupDir: File by lazy {
        val dir = File(dbFile.parentFile, "backups")
        if (!dir.exists()) {
            val created = dir.mkdirs()
            logger.info("Created backup directory: ${dir.absolutePath} (Success: $created)")
        }
        dir
    }

    private val metaFile: File by lazy { File(baseBackupDir, "backup_meta.json") }
    private val historyFile: File by lazy { File(baseBackupDir, "backup_history.json") }

    fun getDatabaseFile(): File = dbFile

    private fun loadMeta(): BackupMeta = try {
        if (metaFile.exists()) json.decodeFromString(metaFile.readText()) else BackupMeta()
    } catch (e: Exception) { BackupMeta() }

    private fun saveMeta(meta: BackupMeta) { metaFile.writeText(json.encodeToString(meta)) }

    private fun loadHistory(): MutableList<BackupHistoryItem> = try {
        if (historyFile.exists()) json.decodeFromString<List<BackupHistoryItem>>(historyFile.readText()).toMutableList() 
        else mutableListOf()
    } catch (e: Exception) { mutableListOf() }

    private fun saveHistory(history: List<BackupHistoryItem>) { historyFile.writeText(json.encodeToString(history)) }

    fun createAutoBackup() {
        triggerImmediateBackup("auto", emptyList())
    }

    /**
     * PHASE 3A: Smart Auto Backups
     */
    fun onTransactionEvent(transactions: List<Transaction>) {
        val meta = loadMeta()
        meta.transactionsSinceLastBackup++
        
        if (meta.transactionsSinceLastBackup >= 10) {
            logger.info("Smart Backup: Threshold (10) reached. Auto-backing up...")
            performPhysicalBackup("auto", transactions)
            meta.transactionsSinceLastBackup = 0
            meta.lastBackupTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        }
        saveMeta(meta)
    }

    /**
     * Requirement 4: Manual restore points must NOT reset pending auto-backup counters.
     * Requirement 5: Update Last Sync (lastBackupTime).
     */
    fun triggerImmediateBackup(type: String, transactions: List<Transaction>): Boolean {
        val success = performPhysicalBackup(type, transactions)
        if (success) {
            val meta = loadMeta()
            // Only reset counter for auto backups. Manual snapshots remain independent.
            if (type == "auto") {
                meta.transactionsSinceLastBackup = 0
            }
            meta.lastBackupTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            saveMeta(meta)
        }
        return success
    }

    /**
     * Requirement: Implement REAL Manual Restore Point snapshots.
     */
    private fun performPhysicalBackup(type: String, transactions: List<Transaction>): Boolean {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss"))
        
        // Requirement 3: Dedicated filename pattern for manual restore points.
        val prefix = if (type == "manual") "manual_restore_point" else "ledger_backup"
        val fileName = "${prefix}_$timestamp.db"
        val targetFile = File(baseBackupDir, fileName)

        // Requirement 6: Add backend logs for manual/auto snapshots.
        if (type == "manual") {
            logger.info("Manual restore point started")
        } else {
            logger.info("Backup started | Type: $type")
        }
        logger.info("Snapshot filename: $fileName")
        logger.info("Destination: ${baseBackupDir.absolutePath}")

        return try {
            // Requirement 4: NEVER overwrite existing files.
            if (targetFile.exists()) {
                logger.warn("Snapshot file already exists: ${targetFile.name}. Skipping.")
                return false
            }

            if (!dbFile.exists()) {
                val reason = "Source database file not found: ${dbFile.absolutePath}"
                logger.error("Snapshot failed: $reason")
                return false
            }

            dbFile.copyTo(targetFile, overwrite = false)
            logger.info("Snapshot completed successfully: ${targetFile.absolutePath}")

            // Update History Index
            val history = loadHistory()
            val balance = if (transactions.isNotEmpty()) transactions.last().balanceAfter?.toString() ?: "0.00" else "0.00"
            history.add(BackupHistoryItem(
                filename = fileName,
                type = type,
                timestamp = LocalDateTime.now().toString(),
                transactionCount = transactions.size,
                balanceAtBackup = balance
            ))
            saveHistory(history)

            // Retention Policy (Manual points are persistent/independent)
            if (type != "manual") {
                enforceRetentionPolicy(type)
            }
            true
        } catch (e: Exception) {
            logger.error("CRITICAL: Snapshot failed for type $type. Reason: ${e.message}")
            false
        }
    }

    private fun enforceRetentionPolicy(type: String) {
        if (type != "auto" && type != "emergency") return
        
        val limit = if (type == "auto") 10 else 5
        val files = baseBackupDir.listFiles { f -> 
            f.name.startsWith("ledger_backup_") && f.name.endsWith(".db") 
        } ?: return
        
        if (files.size > limit) {
            val toDelete = files.sortedByDescending { it.lastModified() }.drop(limit)
            toDelete.forEach { 
                logger.info("Retention Policy: Deleting old backup ${it.name}")
                it.delete() 
            }
        }
    }

    /**
     * Requirement 5: Update UI (Manual Pts increments).
     */
    fun getBackupStatus(): Map<String, String> {
        val meta = loadMeta()
        val history = loadHistory()
        return mapOf(
            "lastBackupTime" to meta.lastBackupTime,
            "transactionsSinceLast" to meta.transactionsSinceLastBackup.toString(),
            "autoCount" to history.count { it.type == "auto" }.toString(),
            "manualCount" to history.count { it.type == "manual" }.toString(),
            "status" to "Optimized"
        )
    }

    fun restoreDatabase(tempFile: File, currentTransactions: List<Transaction>): Boolean {
        try {
            performPhysicalBackup("emergency", currentTransactions)

            val isValid = tempFile.inputStream().use { input ->
                val bytes = ByteArray(16)
                val readCount = input.read(bytes)
                readCount == 16 && String(bytes, StandardCharsets.US_ASCII).startsWith("SQLite format 3")
            }
            if (!isValid) return false

            tempFile.copyTo(dbFile, overwrite = true)
            return true
        } catch (e: Exception) { 
            logger.error("Restore failed: ${e.message}")
            return false 
        }
    }
}
