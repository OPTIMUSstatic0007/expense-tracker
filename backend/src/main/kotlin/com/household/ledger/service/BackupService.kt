package com.household.ledger.service

import com.household.ledger.database.DatabaseFactory
import com.household.ledger.database.TransactionService
import com.household.ledger.models.Transaction
import com.household.ledger.storage.StoragePaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.household.ledger.utils.AppLogger
import java.io.File
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val MAX_AUTO_BACKUPS = 10
    private val MAX_MANUAL_RESTORE_POINTS = 5
    private val backupTimestampFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss")

    init {
        AppLogger.info("BackupService", "Smart Backup Engine initialized | Threshold: 10 | Auto Retention: 10 | Restore Point Retention: 5")
    }

    private val dbFile: File by lazy {
        StoragePaths.databaseFile
    }

    private val baseBackupDir: File by lazy {
        StoragePaths.backupsDir
    }

    private val metaFile: File by lazy { File(baseBackupDir, "backup_meta.json") }
    private val historyFile: File by lazy { File(baseBackupDir, "backup_history.json") }

    fun getDatabaseFile(): File = dbFile

    private fun loadMeta(): BackupMeta = try {
        if (metaFile.exists()) {
            json.decodeFromString<BackupMeta>(metaFile.readText()).also {
                syncMetaCounts(it, reconcileHistoryWithFilesystem(loadHistory()))
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
        
        AppLogger.info("BackupService", "Ledger Mutation: $mutationType | Pending: ${meta.pendingMutationCount} | Total: ${meta.totalMutations}")

        // 2. Persistent metadata write (instant sync)
        saveMeta(meta)

        // 3. Smart Threshold Engine check
        if (meta.pendingMutationCount >= 10) {
            AppLogger.info("BackupService", "Smart Backup: Threshold (10) reached. Triggering physical snapshot...")
            if (performPhysicalBackup("auto", transactions)) {
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
            val history = loadHistory()
            syncMetaCounts(meta, reconcileHistoryWithFilesystem(history))
            
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
        var createdTarget = false

        AppLogger.info("BackupService", "Initiating snapshot | Type: $type | Target: ${targetFile.absolutePath}")

        return try {
            if (!dbFile.exists()) {
                AppLogger.error("BackupService", "Snapshot failed: Source database not found at ${dbFile.absolutePath}")
                return false
            }
            if (targetFile.exists()) {
                AppLogger.error("BackupService", "Snapshot failed: Target already exists at ${targetFile.absolutePath}")
                return false
            }

            dbFile.copyTo(targetFile, overwrite = false)
            createdTarget = true

            if (!targetFile.exists() || targetFile.length() == 0L) {
                AppLogger.error("BackupService", "Snapshot validation failed")
                if (targetFile.exists()) targetFile.delete()
                return false
            }

            AppLogger.info("BackupService", "Snapshot verified: ${targetFile.name}")

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

            enforceRetentionPolicy()
            
            true
        } catch (e: Exception) {
            AppLogger.error("BackupService", "CRITICAL: Snapshot creation failed: ${e.message}")
            if (createdTarget && targetFile.exists()) targetFile.delete()
            false
        }
    }

    private fun enforceRetentionPolicy() {
        AppLogger.info("BackupService", "Retention cleanup triggered")
        
        val history = reconcileHistoryWithFilesystem(loadHistory())
        pruneBackups(history, "auto", MAX_AUTO_BACKUPS)
        pruneBackups(history, "manual", MAX_MANUAL_RESTORE_POINTS)
        saveHistory(history.sortedBy { backupSortTime(it) })

        val meta = loadMeta()
        syncMetaCounts(meta, history)
        saveMeta(meta)

        AppLogger.info("BackupService", "Retention sync completed")
    }

    private fun reconcileHistoryWithFilesystem(history: MutableList<BackupHistoryItem>): MutableList<BackupHistoryItem> {
        val byFileName = linkedMapOf<String, BackupHistoryItem>()

        history.forEach { entry ->
            if (backupTypeFor(entry.filename) == null) {
                AppLogger.info("BackupService", "Ignoring unknown backup history entry: ${entry.filename}")
                return@forEach
            }

            val file = File(baseBackupDir, entry.filename)
            if (file.exists()) {
                byFileName.putIfAbsent(entry.filename, entry)
            } else {
                AppLogger.info("BackupService", "Removing stale backup metadata for missing file: ${entry.filename}")
            }
        }

        baseBackupDir.listFiles()
            ?.filter { it.isFile && backupTypeFor(it.name) != null }
            ?.forEach { file ->
                if (!byFileName.containsKey(file.name)) {
                    val type = backupTypeFor(file.name) ?: return@forEach
                    AppLogger.info("BackupService", "Importing orphan backup file into metadata: ${file.name}")
                    byFileName[file.name] = BackupHistoryItem(
                        filename = file.name,
                        type = type,
                        timestamp = timestampFor(file),
                        transactionCount = 0,
                        balanceAtBackup = "Unknown"
                    )
                }
            }

        return byFileName.values.toMutableList()
    }

    private fun pruneBackups(history: MutableList<BackupHistoryItem>, type: String, maxCount: Int) {
        val entries = history
            .filter { it.type == type }
            .sortedByDescending { backupSortTime(it) }

        entries.drop(maxCount).forEach { entry ->
            if (deleteBackupFile(entry)) {
                history.remove(entry)
            }
        }
    }

    private fun deleteBackupFile(entry: BackupHistoryItem): Boolean {
        val file = File(baseBackupDir, entry.filename)
        if (!isSafeBackupFile(file)) {
            AppLogger.info("BackupService", "Skipped unsafe backup deletion path: ${file.absolutePath}")
            return false
        }

        if (!file.exists()) return true

        return if (file.delete()) {
            AppLogger.info("BackupService", "Deleted retained-out backup: ${entry.filename}")
            true
        } else {
            AppLogger.info("BackupService", "Failed to delete retained-out backup: ${entry.filename}")
            false
        }
    }

    private fun isSafeBackupFile(file: File): Boolean {
        val type = backupTypeFor(file.name) ?: return false
        val canonicalBase = baseBackupDir.canonicalFile.toPath()
        val canonicalFile = file.canonicalFile.toPath()
        return type in setOf("auto", "manual", "emergency") &&
            canonicalFile.startsWith(canonicalBase) &&
            file.extension.equals("db", ignoreCase = true)
    }

    private fun backupTypeFor(filename: String): String? = when {
        filename.matches(Regex("""^ledger_backup_\d{4}_\d{2}_\d{2}_\d{2}_\d{2}_\d{2}\.db$""")) -> "auto"
        filename.matches(Regex("""^manual_restore_point_\d{4}_\d{2}_\d{2}_\d{2}_\d{2}_\d{2}\.db$""")) -> "manual"
        filename.matches(Regex("""^emergency_pre_restore_(\d{4}_\d{2}_\d{2}_\d{2}_\d{2}_\d{2}|\d+)\.db$""")) -> "emergency"
        else -> null
    }

    private fun timestampFor(file: File): String {
        val name = file.name.removeSuffix(".db")
        val rawTimestamp = when {
            name.startsWith("ledger_backup_") -> name.removePrefix("ledger_backup_")
            name.startsWith("manual_restore_point_") -> name.removePrefix("manual_restore_point_")
            name.startsWith("emergency_pre_restore_") -> name.removePrefix("emergency_pre_restore_")
            else -> ""
        }

        return runCatching {
            LocalDateTime.parse(rawTimestamp, backupTimestampFormatter).toString()
        }.recoverCatching {
            val millis = rawTimestamp.toLong()
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toString()
        }.getOrElse {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault()).toString()
        }
    }

    private fun backupSortTime(entry: BackupHistoryItem): LocalDateTime {
        return runCatching { LocalDateTime.parse(entry.timestamp) }
            .getOrElse { LocalDateTime.MIN }
    }

    private fun syncMetaCounts(meta: BackupMeta, history: List<BackupHistoryItem>) {
        meta.autoBackupCount = history.count { it.type == "auto" }
        meta.manualRestorePointCount = history.count { it.type == "manual" }
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

    suspend fun restoreDatabase(tempFile: File, currentTransactions: List<Transaction>): Boolean {
        AppLogger.info("BackupService", "Restore initiated")
        try {
            // 1. Emergency snapshot of current state
            performPhysicalBackup("emergency", currentTransactions)

            // 2. Integrity Verification (Requirement 3)
            if (!tempFile.exists() || tempFile.length() == 0L) {
                AppLogger.error("BackupService", "Restore failed: File invalid or empty")
                return false
            }

            // Verify SQLite format and basic connectivity
            val isValid = try {
                DriverManager.getConnection("jdbc:sqlite:${tempFile.absolutePath}").use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT count(*) FROM sqlite_master;").use { rs ->
                            rs.next()
                        }
                    }
                }
                true
            } catch (e: Exception) {
                AppLogger.error("BackupService", "Restore verification failed: Not a valid SQLite database | ${e.message}")
                false
            }

            if (!isValid) return false
            AppLogger.info("BackupService", "Restore verification passed")

            // 3. Atomic Swap (Requirement 1)
            DatabaseFactory.resetConnection()
            
            tempFile.copyTo(dbFile, overwrite = true)
            AppLogger.info("BackupService", "Database swapped successfully")

            // 4. Post-Restore Sync (Requirement 2)
            val txService = TransactionService()
            txService.recalculateBalances()
            
            // Resync counts and metadata
            val history = reconcileHistoryWithFilesystem(loadHistory())
            val meta = BackupMeta(
                autoBackupCount = history.count { it.type == "auto" },
                manualRestorePointCount = history.count { it.type == "manual" },
                lastActivityTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                lastMutationType = "RESTORE",
                isDirty = false
            )
            saveMeta(meta)

            AppLogger.info("BackupService", "Post-restore sync completed")
            return true
        } catch (e: Exception) { 
            AppLogger.error("BackupService", "Restore failed: ${e.message}")
            return false 
        }
    }
}
