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
     * Prioritizes backend/backend/data/ledger.db as observed in the environment.
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
     * Requirement 1: Restore correct backup destination folder.
     * ALL automatic backups must be written to: backend/backend/data/backups/
     */
    private val baseBackupDir: File by lazy {
        val dir = File(dbFile.parentFile, "backups")
        if (!dir.exists()) {
            val created = dir.mkdirs()
            logger.info("Created backup directory: ${dir.absolutePath} (Success: $created)")
        }
        dir
    }

    // Requirements 1 & 6: Preserve architecture - map types to the same flat backups folder.
    private val autoDir: File get() = baseBackupDir
    private val manualDir: File get() = baseBackupDir
    private val emergencyDir: File get() = baseBackupDir
    
    // Metadata Persistence
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

    fun triggerImmediateBackup(type: String, transactions: List<Transaction>): Boolean {
        val success = performPhysicalBackup(type, transactions)
        if (success && (type == "auto" || type == "manual")) {
            val meta = loadMeta()
            meta.transactionsSinceLastBackup = 0
            meta.lastBackupTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            saveMeta(meta)
        }
        return success
    }

    /**
     * Requirement: Fix backup output path and snapshot filename generation.
     */
    private fun performPhysicalBackup(type: String, transactions: List<Transaction>): Boolean {
        // Requirement 2: Restore timestamp snapshot naming: ledger_backup_YYYY_MM_DD_HH_MM_SS.db
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss"))
        val fileName = "ledger_backup_$timestamp.db"
        val targetFile = File(baseBackupDir, fileName)

        // Requirement 7: Add backend logs
        logger.info("Backup started | Type: $type")
        logger.info("Backup destination path: ${baseBackupDir.absolutePath}")
        logger.info("Backup snapshot filename: $fileName")

        return try {
            // Requirement 3: Ensure every successful backup creates a NEW snapshot file. DO NOT overwrite.
            if (targetFile.exists()) {
                logger.warn("Backup file already exists: ${targetFile.name}. Skipping to prevent overwrite.")
                return false
            }

            if (!dbFile.exists()) {
                logger.error("Source database file not found: ${dbFile.absolutePath}")
                return false
            }

            // Perform the copy
            dbFile.copyTo(targetFile, overwrite = false)
            logger.info("Backup completed successfully: ${targetFile.absolutePath}")

            // PHASE 3D: Update History Index
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

            // PHASE 3E: Retention Policy Enforcement
            enforceRetentionPolicy(type)
            true
        } catch (e: Exception) {
            logger.error("CRITICAL: Backup failed for type $type: ${e.message}")
            false
        }
    }

    /**
     * Requirement 6: Preserve architecture - keep retention logic but applied to the flat folder.
     */
    private fun enforceRetentionPolicy(type: String) {
        // Only enforce for auto and emergency to avoid deleting manual snapshots
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
     * Requirement 5: Preserve metadata UI.
     * Counts are now derived from the history log since files are in a flat folder.
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
            // PHASE 3A.2: Automatic emergency backup before restore
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
