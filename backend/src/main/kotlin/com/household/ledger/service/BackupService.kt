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
    var pendingMutationCount: Int = 0,
    var totalMutations: Long = 0,
    var lastActivityTime: String = "Never",
    var lastMutationType: String = "NONE",
    var lastBackupTime: String = "Never",
    var autoBackupCount: Int = 0,
    var manualRestorePointCount: Int = 0,
    var isDirty: Boolean = false
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

    // Configuration
    private val MAX_AUTO_BACKUPS = 10

    init {
        logger.info("Smart Backup Engine initialized | Threshold: 10 | Retention Limit: 10")
    }

    /**
     * Aligned with DatabaseFactory.kt path resolution.
     */
    private val dbFile: File by lazy {
        File("backend/data/ledger.db")
    }

    /**
     * Aligned with DatabaseFactory.kt path resolution.
     * Backups are stored in backend/data/backups/ relative to the working directory.
     */
    private val baseBackupDir: File by lazy {
        val dir = File("backend/data/backups")
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
        if (metaFile.exists()) {
            json.decodeFromString<BackupMeta>(metaFile.readText()).also {
                // Sync counts from history on load for accuracy
                val history = loadHistory()
                it.autoBackupCount = history.count { h -> h.type == "auto" }
                it.manualRestorePointCount = history.count { h -> h.type == "manual" }
            }
        } else BackupMeta()
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
     * PHASE 1C-1: Continuous Smart Backup Metadata Sync
     * Updates backup_meta.json immediately on every ledger mutation.
     */
    fun onTransactionEvent(mutationType: String, transactions: List<Transaction>) {
        val meta = loadMeta()
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        
        // 1. Update mutation tracking state
        meta.pendingMutationCount++
        meta.totalMutations++
        meta.lastActivityTime = now
        meta.lastMutationType = mutationType
        meta.isDirty = true
        
        logger.info("Ledger Mutation: $mutationType | Pending: ${meta.pendingMutationCount} | Total: ${meta.totalMutations}")

        // 2. Persistent metadata write (instant sync)
        saveMeta(meta)

        // 3. Smart Threshold Engine check
        if (meta.pendingMutationCount >= 10) {
            logger.info("Smart Backup: Threshold (10) reached. Triggering physical snapshot...")
            if (performPhysicalBackup("auto", transactions)) {
                // Refresh meta to get updated counts and reset pending
                val updatedMeta = loadMeta()
                updatedMeta.pendingMutationCount = 0
                updatedMeta.lastBackupTime = now
                updatedMeta.isDirty = false
                saveMeta(updatedMeta)
            }
        }
    }

    /**
     * Manual Snapshots with hard validation.
     */
    fun triggerImmediateBackup(type: String, transactions: List<Transaction>): Boolean {
        val success = performPhysicalBackup(type, transactions)
        if (success) {
            val meta = loadMeta()
            val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            
            if (type == "auto") {
                meta.pendingMutationCount = 0
                meta.isDirty = false
            }
            meta.lastBackupTime = now
            // Update counts in meta
            val history = loadHistory()
            meta.autoBackupCount = history.count { it.type == "auto" }
            meta.manualRestorePointCount = history.count { it.type == "manual" }
            
            saveMeta(meta)
            return true
        }
        return false
    }

    private fun performPhysicalBackup(type: String, transactions: List<Transaction>): Boolean {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss"))
        val prefix = when(type) {
            "manual" -> "manual_restore_point"
            "emergency" -> "emergency_pre_restore"
            else -> "ledger_backup"
        }
        val fileName = "${prefix}_$timestamp.db"
        val targetFile = File(baseBackupDir, fileName)

        logger.info("Initiating snapshot | Type: $type | Target: ${targetFile.absolutePath}")

        return try {
            if (!dbFile.exists()) {
                logger.error("Snapshot failed: Source database not found at ${dbFile.absolutePath}")
                return false
            }

            dbFile.copyTo(targetFile, overwrite = false)

            if (!targetFile.exists() || targetFile.length() == 0L) {
                logger.error("Snapshot validation failed")
                if (targetFile.exists()) targetFile.delete()
                return false
            }

            logger.info("Snapshot verified: ${targetFile.name}")

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

            // Requirement 4: Cleanup happens automatically immediately AFTER successful snapshot creation.
            enforceRetentionPolicy(type)
            
            true
        } catch (e: Exception) {
            logger.error("CRITICAL: Snapshot creation failed: ${e.message}")
            if (targetFile.exists()) targetFile.delete()
            false
        }
    }

    /**
     * Requirement: Intelligent Retention Cleanup System.
     * Implementation of Requirement 1, 2, 3, 5, 6, 7.
     */
    private fun enforceRetentionPolicy(type: String) {
        // Requirement 3: Manual restore points must NEVER be deleted.
        if (type != "auto") return 
        
        logger.info("Retention cleanup triggered")
        
        val history = loadHistory()
        // Filter only auto backups and sort by oldest first
        val autoEntries = history.filter { it.type == "auto" }.sortedBy { it.timestamp }
        
        if (autoEntries.size > MAX_AUTO_BACKUPS) {
            val toDeleteCount = autoEntries.size - MAX_AUTO_BACKUPS
            val itemsToDelete = autoEntries.take(toDeleteCount)
            
            itemsToDelete.forEach { entry ->
                val file = File(baseBackupDir, entry.filename)
                // Requirement 7: filesystem-safe verification before deletion
                if (file.exists()) {
                    logger.info("Deleting oldest auto-backup: ${entry.filename}")
                    file.delete()
                }
                history.remove(entry)
            }
            
            // Requirement 5: persist updated state
            saveHistory(history)
            
            // Requirement 5: backup_meta.json counters must resync
            val meta = loadMeta() // loadMeta automatically resyncs counts from history
            saveMeta(meta)
            
            logger.info("Retention sync completed")
        }
    }

    fun getBackupStatus(): Map<String, String> {
        val meta = loadMeta()
        return mapOf(
            "lastBackupTime" to meta.lastBackupTime,
            "transactionsSinceLast" to meta.pendingMutationCount.toString(),
            "autoCount" to meta.autoBackupCount.toString(),
            "manualCount" to meta.manualRestorePointCount.toString(),
            "totalMutations" to meta.totalMutations.toString(),
            "lastActivity" to meta.lastActivityTime,
            "lastMutationType" to meta.lastMutationType,
            "status" to if (meta.isDirty) "Pending Sync" else "Synced"
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
            
            // Reset metadata on restore
            val meta = BackupMeta()
            val history = loadHistory()
            meta.autoBackupCount = history.count { it.type == "auto" }
            meta.manualRestorePointCount = history.count { it.type == "manual" }
            saveMeta(meta)

            return true
        } catch (e: Exception) { 
            logger.error("Restore failed: ${e.message}")
            return false 
        }
    }
}
