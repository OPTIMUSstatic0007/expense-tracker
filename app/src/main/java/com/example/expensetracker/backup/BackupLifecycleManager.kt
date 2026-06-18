package com.example.expensetracker.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Backup Lifecycle Manager — Phase 3E
 *
 * Owns the complete lifecycle of backup artifacts:
 *   Creation Registration → Retention → Cleanup → Integrity → Statistics → Repair
 *
 * Does NOT replace BackupManager (creation) or RestoreManager (restore).
 * Instead, it coordinates lifecycle concerns that neither currently handles.
 *
 * ARCHITECTURE: The physical backup folders (auto/, manual/, emergency/) are
 * the SINGLE SOURCE OF TRUTH. backup_meta.json is a cache/index only.
 * If backup_meta.json is missing, corrupted, or inconsistent, the manager
 * automatically reconstructs it from the filesystem with full integrity
 * verification — no user interaction required.
 *
 * Per Change 1: extends existing metadata rather than duplicating.
 */
class BackupLifecycleManager(
    private val context: Context,
    private val backupManager: BackupManager
) {
    companion object {
        private const val TAG = "BackupLifecycleManager"
        private const val METADATA_FILE = "backup_meta.json"
        private const val VALID_BACKUP_TYPES = "auto,manual,emergency"
        private const val AUTO_THROTTLE_INTERVAL_MS = 60_000L  // 60 seconds — Change 3
    }

    private val backupDir: File get() = backupManager.getBackupDirectory()
    private val metadataDir: File get() = File(backupDir, "metadata")
    private val metadataFile: File get() = File(metadataDir, METADATA_FILE)
    private val retentionConfig = RetentionConfig()

    @Volatile
    private var hasRunStartupMaintenance = false

    /** Tracks the last auto-backup timestamp for throttling (Change 3) */
    @Volatile
    private var lastAutoBackupTime: Long = 0L

    // ═══════════════════════════════════════════════════════════════════
    // MODULE 3 — METADATA SYNCHRONIZATION (single source of truth)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Loads the unified backup metadata from backup_meta.json.
     *
     * backup_meta.json is a CACHE/INDEX — the filesystem is the source of truth.
     * If the file is missing, corrupted, unparseable, or inconsistent,
     * a full filesystem reconstruction is triggered automatically.
     */
    fun loadMetadata(): BackupLifecycleMetadata {
        return try {
            if (metadataFile.exists()) {
                val json = metadataFile.readText()
                if (json.isBlank()) {
                    Log.w(TAG, "Metadata file is empty, rebuilding from filesystem")
                    return rebuildMetadataFromFilesystem()
                }
                val obj = JSONObject(json)
                val metadata = BackupLifecycleMetadata.fromJson(obj)

                // Validate consistency: history size must match backupCount
                if (!isMetadataConsistent(metadata)) {
                    Log.w(TAG, "Metadata inconsistent (count=${metadata.backupCount}, " +
                            "history=${metadata.history.size}), rebuilding from filesystem")
                    return rebuildMetadataFromFilesystem()
                }

                metadata
            } else {
                Log.d(TAG, "No metadata file found, rebuilding from filesystem")
                rebuildMetadataFromFilesystem()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Metadata corrupted, rebuilding from filesystem: ${e.message}")
            rebuildMetadataFromFilesystem()
        }
    }

    /**
     * Validates that loaded metadata is internally consistent.
     * Returns false if the metadata is stale or structurally invalid.
     */
    private fun isMetadataConsistent(metadata: BackupLifecycleMetadata): Boolean {
        // backupCount must match actual history size
        if (metadata.backupCount != metadata.history.size) return false

        // Every history entry must have a non-empty fileName and valid type
        for (entry in metadata.history) {
            if (entry.fileName.isBlank()) return false
            if (entry.type !in listOf("auto", "manual", "emergency")) return false
        }

        return true
    }

    /**
     * Persists metadata to the single backup_meta.json file.
     */
    fun saveMetadata(metadata: BackupLifecycleMetadata) {
        try {
            if (!metadataDir.exists()) {
                metadataDir.mkdirs()
            }
            metadataFile.writeText(metadata.toJson().toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}", e)
        }
    }

    /**
     * Rebuilds metadata entirely from filesystem when JSON is missing, corrupted, or invalid.
     *
     * This is the core reconstruction algorithm:
     *   1. Scans files/backups/auto/, manual/, emergency/ directories
     *   2. Registers every *.db file (ignores .db-wal, .db-shm)
     *   3. Collects filename, type, timestamp, filesize, last modified
     *   4. Runs integrity verification on each backup
     *   5. Recalculates all counts, storage, and health metrics
     *   6. Writes a fresh backup_meta.json
     *
     * No user interaction required — fully automatic.
     */
    private fun rebuildMetadataFromFilesystem(): BackupLifecycleMetadata {
        Log.d(TAG, "Rebuilding metadata from filesystem scan (full reconstruction)")
        val history = mutableListOf<BackupHistoryEntry>()

        for (type in listOf("auto", "manual", "emergency")) {
            val typeDir = File(backupDir, type)
            if (typeDir.exists() && typeDir.isDirectory) {
                typeDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".db") }
                    ?.filterNot { it.name.endsWith(".db-wal") || it.name.endsWith(".db-shm") }
                    ?.forEach { file ->
                        val entry = createHistoryEntryFromFile(file, type)

                        // Run integrity verification during reconstruction
                        val health = verifyBackup(type, file.name)
                        val verifiedEntry = entry.copy(
                            integrityVerified = true,
                            lastVerifiedAt = System.currentTimeMillis(),
                            isCorrupted = health == BackupHealth.CORRUPTED || health == BackupHealth.EMPTY
                        )

                        history.add(verifiedEntry)
                        Log.d(TAG, "Reconstructed: ${file.name} ($type) — ${health.name}")
                    }
            }
        }

        val sorted = history.sortedByDescending { it.createdAt }

        // Calculate total storage from scanned files
        val totalStorage = sorted.sumOf { it.sizeBytes + (it.walSizeBytes ?: 0L) + (it.shmSizeBytes ?: 0L) }

        val metadata = BackupLifecycleMetadata(
            lastBackupTime = sorted.firstOrNull()?.createdAt ?: 0L,
            backupCount = sorted.size,
            lastBackupType = sorted.firstOrNull()?.type ?: "",
            mainDbSize = sorted.firstOrNull()?.sizeBytes ?: 0L,
            walSize = sorted.firstOrNull()?.walSizeBytes,
            shmSize = sorted.firstOrNull()?.shmSizeBytes,
            lastMaintenanceTime = System.currentTimeMillis(),
            history = sorted
        )

        saveMetadata(metadata)
        Log.d(TAG, "Rebuilt metadata with ${sorted.size} entries " +
                "(auto=${sorted.count { it.type == "auto" }}, " +
                "manual=${sorted.count { it.type == "manual" }}, " +
                "emergency=${sorted.count { it.type == "emergency" }}, " +
                "storage=${totalStorage} bytes, " +
                "healthy=${sorted.count { !it.isCorrupted }}, " +
                "corrupted=${sorted.count { it.isCorrupted }})")
        return metadata
    }

    /**
     * Creates a BackupHistoryEntry from a filesystem file.
     * Parses timestamp from filename with fallback to lastModified.
     */
    private fun createHistoryEntryFromFile(file: File, type: String): BackupHistoryEntry {
        val timestamp = parseTimestampFromFilename(file.name) ?: file.lastModified()
        val walFile = File(file.parent, "${file.name}-wal")
        val shmFile = File(file.parent, "${file.name}-shm")

        return BackupHistoryEntry(
            fileName = file.name,
            type = type,
            createdAt = timestamp,
            sizeBytes = file.length(),
            walSizeBytes = if (walFile.exists()) walFile.length() else null,
            shmSizeBytes = if (shmFile.exists()) shmFile.length() else null
        )
    }

    /**
     * Parses the timestamp from backup filenames like "backup_2026_06_17_120000.db".
     */
    private fun parseTimestampFromFilename(filename: String): Long? {
        return try {
            val name = filename.substringBeforeLast(".db")
            val parts = name.split("_")
            if (parts.size >= 5 && parts[0] == "backup") {
                val dateStr = "${parts[1]}_${parts[2]}_${parts[3]}_${parts[4]}"
                val sdf = SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.getDefault())
                sdf.parse(dateStr)?.time
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reconciles metadata against filesystem state.
     * Removes stale entries, imports orphan files, deduplicates.
     */
    fun reconcileMetadata(): ReconciliationReport {
        Log.d(TAG, "Reconciling metadata with filesystem")
        val metadata = loadMetadata()
        val history = metadata.history.toMutableList()

        var staleRemoved = 0
        var orphansImported = 0
        var duplicatesRemoved = 0

        // Build filesystem set: actual .db files on disk
        val filesOnDisk = mutableMapOf<String, Pair<File, String>>()  // fileName -> (file, type)
        for (type in listOf("auto", "manual", "emergency")) {
            val typeDir = File(backupDir, type)
            if (typeDir.exists() && typeDir.isDirectory) {
                typeDir.listFiles()?.filter { it.isFile && it.name.endsWith(".db") }?.forEach { file ->
                    filesOnDisk[file.name] = Pair(file, type)
                }
            }
        }

        // Remove stale entries (in metadata but not on filesystem)
        val iterator = history.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val file = File(File(backupDir, entry.type), entry.fileName)
            if (!file.exists()) {
                Log.d(TAG, "Removing stale metadata entry: ${entry.fileName}")
                iterator.remove()
                staleRemoved++
            }
        }

        // Deduplicate (keep first occurrence)
        val seen = mutableSetOf<String>()
        val dedupIterator = history.iterator()
        while (dedupIterator.hasNext()) {
            val entry = dedupIterator.next()
            if (!seen.add(entry.fileName)) {
                Log.d(TAG, "Removing duplicate metadata entry: ${entry.fileName}")
                dedupIterator.remove()
                duplicatesRemoved++
            }
        }

        // Import orphan files (on filesystem but not in metadata)
        val metadataFileNames = history.map { it.fileName }.toSet()
        for ((fileName, pair) in filesOnDisk) {
            if (fileName !in metadataFileNames) {
                val (file, type) = pair
                Log.d(TAG, "Importing orphan backup: $fileName ($type)")
                history.add(createHistoryEntryFromFile(file, type))
                orphansImported++
            }
        }

        // Save reconciled metadata
        val reconciledHistory = history.sortedByDescending { it.createdAt }
        val updatedMetadata = metadata.copy(
            history = reconciledHistory,
            backupCount = reconciledHistory.size,
            lastBackupTime = reconciledHistory.firstOrNull()?.createdAt ?: 0L,
            lastBackupType = reconciledHistory.firstOrNull()?.type ?: ""
        )
        saveMetadata(updatedMetadata)

        val report = ReconciliationReport(
            staleEntriesRemoved = staleRemoved,
            orphanFilesImported = orphansImported,
            duplicatesRemoved = duplicatesRemoved
        )
        Log.d(TAG, "Reconciliation complete: $report")
        return report
    }

    // ═══════════════════════════════════════════════════════════════════
    // BACKUP REGISTRATION (called after BackupManager.createBackup)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Registers a successful backup in the unified metadata.
     * Called by AndroidBridge after every BackupManager.createBackup() success.
     */
    fun onBackupCreated(result: BackupResult.Success, type: BackupType) {
        Log.d(TAG, "Registering backup: ${result.backupFileName} (${type.name})")

        val metadata = loadMetadata()
        val backupFile = File(result.backupPath)
        val walFile = File("${result.backupPath}-wal")
        val shmFile = File("${result.backupPath}-shm")

        val entry = BackupHistoryEntry(
            fileName = result.backupFileName,
            type = type.name.lowercase(Locale.getDefault()),
            createdAt = result.timestamp,
            sizeBytes = if (backupFile.exists()) backupFile.length() else 0L,
            walSizeBytes = if (walFile.exists()) walFile.length() else null,
            shmSizeBytes = if (shmFile.exists()) shmFile.length() else null
        )

        val updatedHistory = (listOf(entry) + metadata.history)
            .distinctBy { it.fileName }
            .sortedByDescending { it.createdAt }

        val updatedMetadata = metadata.copy(
            lastBackupTime = result.timestamp,
            backupCount = updatedHistory.size,
            lastBackupType = type.name.lowercase(Locale.getDefault()),
            mainDbSize = entry.sizeBytes,
            walSize = entry.walSizeBytes,
            shmSize = entry.shmSizeBytes,
            totalBackupsCreated = metadata.totalBackupsCreated + 1,
            history = updatedHistory
        )

        saveMetadata(updatedMetadata)

        // Update throttle timestamp for auto backups
        if (type == BackupType.AUTO) {
            lastAutoBackupTime = System.currentTimeMillis()
        }
    }

    /**
     * Called after a successful restore to reconcile metadata.
     * Emergency backup was created during restore — register it.
     */
    fun onRestoreCompleted() {
        Log.d(TAG, "Post-restore maintenance triggered")
        reconcileMetadata()
        enforceRetentionPolicy()
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTO-BACKUP THROTTLE (Change 3 — 60-second minimum interval)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Determines whether an auto backup should be allowed.
     * Returns true if >= 60 seconds have elapsed since the last auto backup.
     * Manual and emergency backups always return true.
     */
    fun shouldAllowBackup(type: BackupType): Boolean {
        if (type != BackupType.AUTO) return true

        val elapsed = System.currentTimeMillis() - lastAutoBackupTime
        if (elapsed < AUTO_THROTTLE_INTERVAL_MS) {
            Log.d(TAG, "Auto-backup throttled: ${elapsed}ms since last (need ${AUTO_THROTTLE_INTERVAL_MS}ms)")
            return false
        }
        return true
    }

    // ═══════════════════════════════════════════════════════════════════
    // MODULE 1 — RETENTION POLICIES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Enforces retention limits for all backup types.
     * Returns a CleanupReport describing what was removed.
     */
    fun enforceRetentionPolicy(): CleanupReport {
        Log.d(TAG, "Enforcing retention policy")

        // Reconcile first to ensure metadata matches filesystem
        val reconciliation = reconcileMetadata()

        var totalFilesRemoved = 0
        var totalStorageRecovered = 0L
        val errors = mutableListOf<String>()

        for (type in listOf("auto", "emergency", "manual")) {
            val limit = retentionConfig.limitForType(type)
            if (limit < 0) continue  // unlimited

            val metadata = loadMetadata()
            val typeEntries = metadata.history
                .filter { it.type == type }
                .sortedByDescending { it.createdAt }

            val excess = typeEntries.drop(limit)
            for (entry in excess) {
                val deleted = deleteBackupSafely(type, entry.fileName)
                if (deleted.first) {
                    totalFilesRemoved++
                    totalStorageRecovered += deleted.second
                } else {
                    errors.add("Failed to delete ${entry.fileName}")
                }
            }

            // Remove deleted entries from metadata
            if (excess.isNotEmpty()) {
                val currentMeta = loadMetadata()
                val deletedNames = excess.map { it.fileName }.toSet()
                val prunedHistory = currentMeta.history.filter { it.fileName !in deletedNames }
                saveMetadata(currentMeta.copy(
                    history = prunedHistory,
                    backupCount = prunedHistory.size,
                    totalBackupsDeleted = currentMeta.totalBackupsDeleted + excess.size,
                    totalStorageRecovered = currentMeta.totalStorageRecovered + totalStorageRecovered,
                    lastCleanupTime = System.currentTimeMillis()
                ))
            }
        }

        // Clean orphan WAL/SHM files
        val orphansRemoved = cleanOrphanFiles()

        val finalMetadata = loadMetadata()
        val report = CleanupReport(
            filesRemoved = totalFilesRemoved,
            storageRecoveredBytes = totalStorageRecovered,
            backupsRemaining = finalMetadata.history.size,
            metadataEntriesRepaired = reconciliation.staleEntriesRemoved + reconciliation.orphanFilesImported,
            orphanFilesRemoved = orphansRemoved,
            integrityIssuesFound = finalMetadata.history.count { it.isCorrupted },
            errors = errors
        )
        Log.d(TAG, "Retention enforcement complete: $report")
        return report
    }

    fun getRetentionConfig(): RetentionConfig = retentionConfig

    // ═══════════════════════════════════════════════════════════════════
    // MODULE 2 — PHYSICAL CLEANUP
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Safely deletes a backup file and its associated WAL/SHM files.
     * Returns Pair(success, bytesRecovered).
     * Validates path is within backups directory before deletion.
     */
    private fun deleteBackupSafely(type: String, fileName: String): Pair<Boolean, Long> {
        val typeDir = File(backupDir, type)
        val dbFile = File(typeDir, fileName)

        // Path safety check
        if (!isSafeBackupPath(dbFile)) {
            Log.w(TAG, "Skipped unsafe deletion path: ${dbFile.absolutePath}")
            return Pair(false, 0L)
        }

        if (!dbFile.exists()) {
            Log.d(TAG, "File already deleted: $fileName")
            return Pair(true, 0L)
        }

        var bytesRecovered = 0L

        // Delete main .db file
        val dbSize = dbFile.length()
        if (dbFile.delete()) {
            bytesRecovered += dbSize
            Log.d(TAG, "Deleted backup: $fileName ($dbSize bytes)")
        } else {
            Log.e(TAG, "Failed to delete: $fileName")
            return Pair(false, 0L)
        }

        // Delete associated -wal file
        val walFile = File(typeDir, "$fileName-wal")
        if (walFile.exists()) {
            val walSize = walFile.length()
            if (walFile.delete()) {
                bytesRecovered += walSize
                Log.d(TAG, "Deleted WAL: ${walFile.name}")
            }
        }

        // Delete associated -shm file
        val shmFile = File(typeDir, "$fileName-shm")
        if (shmFile.exists()) {
            val shmSize = shmFile.length()
            if (shmFile.delete()) {
                bytesRecovered += shmSize
                Log.d(TAG, "Deleted SHM: ${shmFile.name}")
            }
        }

        return Pair(true, bytesRecovered)
    }

    /**
     * Validates that a file path is within the backups directory.
     * Prevents path traversal attacks.
     */
    private fun isSafeBackupPath(file: File): Boolean {
        return try {
            val canonicalBackup = backupDir.canonicalPath
            val canonicalFile = file.canonicalPath
            canonicalFile.startsWith(canonicalBackup) &&
                file.name.endsWith(".db") || file.name.endsWith(".db-wal") || file.name.endsWith(".db-shm")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Scans all type directories for orphan WAL/SHM files
     * whose corresponding .db file does not exist.
     * Returns count of orphan files removed.
     */
    fun cleanOrphanFiles(): Int {
        var removed = 0

        for (type in listOf("auto", "manual", "emergency")) {
            val typeDir = File(backupDir, type)
            if (!typeDir.exists() || !typeDir.isDirectory) continue

            typeDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val isWal = file.name.endsWith(".db-wal")
                    val isShm = file.name.endsWith(".db-shm")
                    if (isWal || isShm) {
                        // Find the base .db filename
                        val baseName = if (isWal) {
                            file.name.removeSuffix("-wal")
                        } else {
                            file.name.removeSuffix("-shm")
                        }
                        val baseDbFile = File(typeDir, baseName)
                        if (!baseDbFile.exists()) {
                            val size = file.length()
                            if (file.delete()) {
                                Log.d(TAG, "Removed orphan file: ${file.name} ($size bytes)")
                                removed++
                            }
                        }
                    }
                }
            }
        }

        if (removed > 0) {
            Log.d(TAG, "Cleaned $removed orphan WAL/SHM files")
        }
        return removed
    }

    // ═══════════════════════════════════════════════════════════════════
    // MODULE 4 — INTEGRITY VERIFICATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Verifies the integrity of a single backup file.
     * Opens it as a read-only SQLite database and runs PRAGMA integrity_check.
     *
     * Per Change 4: corrupted backups are marked, NOT auto-deleted.
     */
    fun verifyBackup(type: String, fileName: String): BackupHealth {
        val typeDir = File(backupDir, type)
        val file = File(typeDir, fileName)

        return when {
            !file.exists() -> BackupHealth.MISSING
            file.length() == 0L -> BackupHealth.EMPTY
            else -> {
                try {
                    SQLiteDatabase.openDatabase(
                        file.absolutePath, null,
                        SQLiteDatabase.OPEN_READONLY
                    ).use { db ->
                        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                            if (cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                                BackupHealth.HEALTHY
                            } else {
                                BackupHealth.CORRUPTED
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Integrity check failed for $fileName: ${e.message}")
                    BackupHealth.CORRUPTED
                }
            }
        }
    }

    /**
     * Verifies all backups and updates metadata with integrity status.
     * Per Change 4: corrupted backups are marked in metadata, NOT deleted.
     */
    fun verifyAllBackups(): IntegrityReport {
        Log.d(TAG, "Running integrity verification on all backups")
        val metadata = loadMetadata()
        val details = mutableListOf<IntegrityDetail>()
        val updatedHistory = mutableListOf<BackupHistoryEntry>()
        val now = System.currentTimeMillis()

        for (entry in metadata.history) {
            val health = verifyBackup(entry.type, entry.fileName)
            details.add(IntegrityDetail(entry.fileName, entry.type, health))

            // Update entry with verification result (mark, don't delete — Change 4)
            updatedHistory.add(entry.copy(
                integrityVerified = true,
                lastVerifiedAt = now,
                isCorrupted = health == BackupHealth.CORRUPTED || health == BackupHealth.EMPTY
            ))
        }

        // Save updated verification status
        saveMetadata(metadata.copy(history = updatedHistory))

        val report = IntegrityReport(
            totalChecked = details.size,
            healthyCount = details.count { it.health == BackupHealth.HEALTHY },
            corruptedCount = details.count { it.health == BackupHealth.CORRUPTED },
            missingCount = details.count { it.health == BackupHealth.MISSING },
            emptyCount = details.count { it.health == BackupHealth.EMPTY },
            details = details
        )
        Log.d(TAG, "Integrity verification complete: ${report.healthyCount} healthy, ${report.corruptedCount} corrupted, ${report.missingCount} missing, ${report.emptyCount} empty")
        return report
    }

    // ═══════════════════════════════════════════════════════════════════
    // MODULE 5 — STORAGE STATISTICS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Computes comprehensive backup lifecycle statistics from metadata.
     */
    fun getLifecycleStats(): LifecycleStats {
        val metadata = loadMetadata()
        val history = metadata.history

        if (history.isEmpty()) {
            return LifecycleStats(retentionConfig = retentionConfig)
        }

        val autoEntries = history.filter { it.type == "auto" }
        val manualEntries = history.filter { it.type == "manual" }
        val emergencyEntries = history.filter { it.type == "emergency" }

        val totalStorage = history.sumOf { it.sizeBytes + (it.walSizeBytes ?: 0L) + (it.shmSizeBytes ?: 0L) }
        val autoStorage = autoEntries.sumOf { it.sizeBytes + (it.walSizeBytes ?: 0L) + (it.shmSizeBytes ?: 0L) }
        val manualStorage = manualEntries.sumOf { it.sizeBytes + (it.walSizeBytes ?: 0L) + (it.shmSizeBytes ?: 0L) }
        val emergencyStorage = emergencyEntries.sumOf { it.sizeBytes + (it.walSizeBytes ?: 0L) + (it.shmSizeBytes ?: 0L) }

        val largest = history.maxByOrNull { it.sizeBytes }
        val oldest = history.minByOrNull { it.createdAt }
        val newest = history.maxByOrNull { it.createdAt }

        return LifecycleStats(
            totalBackups = history.size,
            autoBackupCount = autoEntries.size,
            manualBackupCount = manualEntries.size,
            emergencyBackupCount = emergencyEntries.size,

            totalBackupStorageBytes = totalStorage,
            autoStorageBytes = autoStorage,
            manualStorageBytes = manualStorage,
            emergencyStorageBytes = emergencyStorage,
            averageBackupSizeBytes = if (history.isNotEmpty()) totalStorage / history.size else 0L,
            largestBackupSizeBytes = largest?.sizeBytes ?: 0L,
            largestBackupName = largest?.fileName,

            oldestBackupTime = oldest?.createdAt,
            newestBackupTime = newest?.createdAt,
            oldestBackupName = oldest?.fileName,
            newestBackupName = newest?.fileName,

            healthyCount = history.count { it.integrityVerified && !it.isCorrupted },
            corruptedCount = history.count { it.isCorrupted },
            lastMaintenanceTime = metadata.lastMaintenanceTime.takeIf { it > 0 },
            lastCleanupTime = metadata.lastCleanupTime.takeIf { it > 0 },

            retentionConfig = retentionConfig
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // MODULE 8 — STARTUP MAINTENANCE (Change 2 — async after Room init)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Runs startup maintenance asynchronously.
     * Per Change 2: called after Room initialization, never blocks UI.
     *
     * Flow:
     *   1. Reconcile metadata with filesystem
     *   2. Clean orphan WAL/SHM files
     *   3. Enforce retention policy
     *   4. Update maintenance timestamp
     *
     * Safe to call multiple times — protected by hasRunStartupMaintenance flag.
     */
    fun runStartupMaintenance(): MaintenanceReport {
        if (hasRunStartupMaintenance) {
            Log.d(TAG, "Startup maintenance already completed, skipping")
            return MaintenanceReport()
        }

        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Running startup maintenance")

        try {
            // 1. Reconcile metadata with filesystem
            val reconciliation = reconcileMetadata()

            // 2. Clean orphan files
            val orphansRemoved = cleanOrphanFiles()

            // 3. Enforce retention policies
            val cleanup = enforceRetentionPolicy()

            // 4. Update maintenance timestamp
            val metadata = loadMetadata()
            saveMetadata(metadata.copy(lastMaintenanceTime = System.currentTimeMillis()))

            hasRunStartupMaintenance = true

            // Initialize throttle timestamp from metadata
            if (lastAutoBackupTime == 0L && metadata.lastBackupTime > 0L) {
                lastAutoBackupTime = metadata.lastBackupTime
            }

            val durationMs = System.currentTimeMillis() - startTime
            val report = MaintenanceReport(
                reconciliation = reconciliation,
                cleanup = cleanup,
                orphansRemoved = orphansRemoved,
                durationMs = durationMs
            )
            Log.d(TAG, "Startup maintenance completed in ${durationMs}ms: $report")
            return report
        } catch (e: Exception) {
            Log.e(TAG, "Startup maintenance failed: ${e.message}", e)
            hasRunStartupMaintenance = true  // Don't retry on next call
            return MaintenanceReport()
        }
    }
}
