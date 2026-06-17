package com.example.expensetracker.backup

import org.json.JSONArray
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════════
// RETENTION CONFIGURATION
// ═══════════════════════════════════════════════════════════════════

/**
 * Retention limits per backup type.
 * -1 means unlimited (no pruning for that type).
 * Hardcoded defaults for Phase 3E — future phases may load from SharedPreferences.
 */
data class RetentionConfig(
    val autoLimit: Int = 10,
    val emergencyLimit: Int = 5,
    val manualLimit: Int = -1  // unlimited
) {
    fun limitForType(type: String): Int = when (type) {
        "auto" -> autoLimit
        "emergency" -> emergencyLimit
        "manual" -> manualLimit
        else -> -1
    }

    fun labelForType(type: String): String = when (type) {
        "auto" -> if (autoLimit > 0) "$autoLimit" else "Unlimited"
        "emergency" -> if (emergencyLimit > 0) "$emergencyLimit" else "Unlimited"
        "manual" -> if (manualLimit > 0) "$manualLimit" else "Unlimited"
        else -> "Unknown"
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("autoLimit", autoLimit)
        obj.put("emergencyLimit", emergencyLimit)
        obj.put("manualLimit", manualLimit)
        obj.put("autoLabel", labelForType("auto"))
        obj.put("emergencyLabel", labelForType("emergency"))
        obj.put("manualLabel", labelForType("manual"))
        return obj
    }
}

// ═══════════════════════════════════════════════════════════════════
// BACKUP HISTORY ENTRY
// ═══════════════════════════════════════════════════════════════════

/**
 * A single backup's metadata record, stored as part of
 * the extended backup_meta.json history array.
 */
data class BackupHistoryEntry(
    val fileName: String,
    val type: String,              // "auto", "manual", "emergency"
    val createdAt: Long,           // epoch millis
    val sizeBytes: Long,
    val databaseVersion: Int = 0,
    val walSizeBytes: Long? = null,
    val shmSizeBytes: Long? = null,
    val integrityVerified: Boolean = false,
    val lastVerifiedAt: Long? = null,
    val isCorrupted: Boolean = false
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("fileName", fileName)
        obj.put("type", type)
        obj.put("createdAt", createdAt)
        obj.put("sizeBytes", sizeBytes)
        obj.put("databaseVersion", databaseVersion)
        if (walSizeBytes != null) obj.put("walSizeBytes", walSizeBytes)
        if (shmSizeBytes != null) obj.put("shmSizeBytes", shmSizeBytes)
        obj.put("integrityVerified", integrityVerified)
        if (lastVerifiedAt != null) obj.put("lastVerifiedAt", lastVerifiedAt)
        obj.put("isCorrupted", isCorrupted)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): BackupHistoryEntry {
            return BackupHistoryEntry(
                fileName = obj.optString("fileName", ""),
                type = obj.optString("type", ""),
                createdAt = obj.optLong("createdAt", 0L),
                sizeBytes = obj.optLong("sizeBytes", 0L),
                databaseVersion = obj.optInt("databaseVersion", 0),
                walSizeBytes = if (obj.has("walSizeBytes")) obj.optLong("walSizeBytes") else null,
                shmSizeBytes = if (obj.has("shmSizeBytes")) obj.optLong("shmSizeBytes") else null,
                integrityVerified = obj.optBoolean("integrityVerified", false),
                lastVerifiedAt = if (obj.has("lastVerifiedAt")) obj.optLong("lastVerifiedAt") else null,
                isCorrupted = obj.optBoolean("isCorrupted", false)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// EXTENDED BACKUP METADATA
// ═══════════════════════════════════════════════════════════════════

/**
 * Extended metadata that augments the existing BackupMetadata model.
 * Persisted as the single backup_meta.json in the metadata/ directory.
 *
 * Combines the original BackupMetadata fields (lastBackupTime, backupCount,
 * lastBackupType, databaseVersion, sizes) with lifecycle-specific tracking
 * (history, maintenance, retention, statistics).
 *
 * One metadata source — no separate lifecycle_meta.json.
 */
data class BackupLifecycleMetadata(
    // Original BackupMetadata fields
    val lastBackupTime: Long = 0L,
    val backupCount: Int = 0,
    val lastBackupType: String = "",
    val databaseVersion: Int = 0,
    val mainDbSize: Long = 0L,
    val walSize: Long? = null,
    val shmSize: Long? = null,

    // Lifecycle tracking fields
    val lastMaintenanceTime: Long = 0L,
    val lastCleanupTime: Long = 0L,
    val totalBackupsCreated: Long = 0L,
    val totalBackupsDeleted: Long = 0L,
    val totalStorageRecovered: Long = 0L,

    // Backup history
    val history: List<BackupHistoryEntry> = emptyList()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("lastBackupTime", lastBackupTime)
        obj.put("backupCount", backupCount)
        obj.put("lastBackupType", lastBackupType)
        obj.put("databaseVersion", databaseVersion)
        obj.put("mainDbSize", mainDbSize)
        if (walSize != null) obj.put("walSize", walSize)
        if (shmSize != null) obj.put("shmSize", shmSize)
        obj.put("lastMaintenanceTime", lastMaintenanceTime)
        obj.put("lastCleanupTime", lastCleanupTime)
        obj.put("totalBackupsCreated", totalBackupsCreated)
        obj.put("totalBackupsDeleted", totalBackupsDeleted)
        obj.put("totalStorageRecovered", totalStorageRecovered)

        val historyArray = JSONArray()
        for (entry in history) {
            historyArray.put(entry.toJson())
        }
        obj.put("history", historyArray)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): BackupLifecycleMetadata {
            val historyArray = obj.optJSONArray("history") ?: JSONArray()
            val historyList = mutableListOf<BackupHistoryEntry>()
            for (i in 0 until historyArray.length()) {
                historyList.add(BackupHistoryEntry.fromJson(historyArray.getJSONObject(i)))
            }

            return BackupLifecycleMetadata(
                lastBackupTime = obj.optLong("lastBackupTime", 0L),
                backupCount = obj.optInt("backupCount", 0),
                lastBackupType = obj.optString("lastBackupType", ""),
                databaseVersion = obj.optInt("databaseVersion", 0),
                mainDbSize = obj.optLong("mainDbSize", 0L),
                walSize = if (obj.has("walSize")) obj.optLong("walSize") else null,
                shmSize = if (obj.has("shmSize")) obj.optLong("shmSize") else null,
                lastMaintenanceTime = obj.optLong("lastMaintenanceTime", 0L),
                lastCleanupTime = obj.optLong("lastCleanupTime", 0L),
                totalBackupsCreated = obj.optLong("totalBackupsCreated", 0L),
                totalBackupsDeleted = obj.optLong("totalBackupsDeleted", 0L),
                totalStorageRecovered = obj.optLong("totalStorageRecovered", 0L),
                history = historyList
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// BACKUP HEALTH
// ═══════════════════════════════════════════════════════════════════

enum class BackupHealth {
    HEALTHY,
    MISSING,
    EMPTY,
    CORRUPTED
}

// ═══════════════════════════════════════════════════════════════════
// LIFECYCLE STATISTICS
// ═══════════════════════════════════════════════════════════════════

data class LifecycleStats(
    // Counts
    val totalBackups: Int = 0,
    val autoBackupCount: Int = 0,
    val manualBackupCount: Int = 0,
    val emergencyBackupCount: Int = 0,

    // Storage
    val totalBackupStorageBytes: Long = 0L,
    val autoStorageBytes: Long = 0L,
    val manualStorageBytes: Long = 0L,
    val emergencyStorageBytes: Long = 0L,
    val averageBackupSizeBytes: Long = 0L,
    val largestBackupSizeBytes: Long = 0L,
    val largestBackupName: String? = null,

    // Timestamps
    val oldestBackupTime: Long? = null,
    val newestBackupTime: Long? = null,
    val oldestBackupName: String? = null,
    val newestBackupName: String? = null,

    // Health
    val healthyCount: Int = 0,
    val corruptedCount: Int = 0,
    val lastMaintenanceTime: Long? = null,
    val lastCleanupTime: Long? = null,

    // Retention
    val retentionConfig: RetentionConfig = RetentionConfig()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("totalBackups", totalBackups)
        obj.put("autoBackupCount", autoBackupCount)
        obj.put("manualBackupCount", manualBackupCount)
        obj.put("emergencyBackupCount", emergencyBackupCount)

        obj.put("totalBackupStorageBytes", totalBackupStorageBytes)
        obj.put("autoStorageBytes", autoStorageBytes)
        obj.put("manualStorageBytes", manualStorageBytes)
        obj.put("emergencyStorageBytes", emergencyStorageBytes)
        obj.put("averageBackupSizeBytes", averageBackupSizeBytes)
        obj.put("largestBackupSizeBytes", largestBackupSizeBytes)
        if (largestBackupName != null) obj.put("largestBackupName", largestBackupName)

        if (oldestBackupTime != null) obj.put("oldestBackupTime", oldestBackupTime)
        if (newestBackupTime != null) obj.put("newestBackupTime", newestBackupTime)
        if (oldestBackupName != null) obj.put("oldestBackupName", oldestBackupName)
        if (newestBackupName != null) obj.put("newestBackupName", newestBackupName)

        obj.put("healthyCount", healthyCount)
        obj.put("corruptedCount", corruptedCount)
        if (lastMaintenanceTime != null) obj.put("lastMaintenanceTime", lastMaintenanceTime)
        if (lastCleanupTime != null) obj.put("lastCleanupTime", lastCleanupTime)

        obj.put("retentionConfig", retentionConfig.toJson())

        // Computed health label
        val healthLabel = when {
            corruptedCount > 0 -> "Issues Found ($corruptedCount)"
            totalBackups == 0 -> "No Backups"
            else -> "Healthy"
        }
        obj.put("healthLabel", healthLabel)
        val healthStatus = when {
            corruptedCount > 0 -> "warning"
            totalBackups == 0 -> "neutral"
            else -> "ok"
        }
        obj.put("healthStatus", healthStatus)

        return obj
    }
}

// ═══════════════════════════════════════════════════════════════════
// CLEANUP REPORT
// ═══════════════════════════════════════════════════════════════════

data class CleanupReport(
    val filesRemoved: Int = 0,
    val storageRecoveredBytes: Long = 0L,
    val backupsRemaining: Int = 0,
    val metadataEntriesRepaired: Int = 0,
    val orphanFilesRemoved: Int = 0,
    val integrityIssuesFound: Int = 0,
    val errors: List<String> = emptyList()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("filesRemoved", filesRemoved)
        obj.put("storageRecoveredBytes", storageRecoveredBytes)

        // Human-readable storage recovered
        val recoveredMb = storageRecoveredBytes / (1024.0 * 1024.0)
        val recoveredKb = storageRecoveredBytes / 1024.0
        val storageLabel = when {
            recoveredMb >= 1.0 -> String.format("%.1f MB", recoveredMb)
            recoveredKb >= 1.0 -> String.format("%.0f KB", recoveredKb)
            else -> "$storageRecoveredBytes B"
        }
        obj.put("storageRecovered", storageLabel)

        obj.put("backupsRemaining", backupsRemaining)
        obj.put("metadataRepaired", metadataEntriesRepaired)
        obj.put("orphansRemoved", orphanFilesRemoved)
        obj.put("integrityIssues", integrityIssuesFound)

        val errArray = JSONArray()
        for (err in errors) errArray.put(err)
        obj.put("errors", errArray)
        return obj
    }
}

// ═══════════════════════════════════════════════════════════════════
// MAINTENANCE REPORT
// ═══════════════════════════════════════════════════════════════════

data class MaintenanceReport(
    val reconciliation: ReconciliationReport = ReconciliationReport(),
    val cleanup: CleanupReport = CleanupReport(),
    val orphansRemoved: Int = 0,
    val durationMs: Long = 0L
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("reconciliation", reconciliation.toJson())
        obj.put("cleanup", cleanup.toJson())
        obj.put("orphansRemoved", orphansRemoved)
        obj.put("durationMs", durationMs)
        return obj
    }
}

// ═══════════════════════════════════════════════════════════════════
// RECONCILIATION REPORT
// ═══════════════════════════════════════════════════════════════════

data class ReconciliationReport(
    val staleEntriesRemoved: Int = 0,
    val orphanFilesImported: Int = 0,
    val duplicatesRemoved: Int = 0
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("staleEntriesRemoved", staleEntriesRemoved)
        obj.put("orphanFilesImported", orphanFilesImported)
        obj.put("duplicatesRemoved", duplicatesRemoved)
        return obj
    }
}

// ═══════════════════════════════════════════════════════════════════
// INTEGRITY REPORT
// ═══════════════════════════════════════════════════════════════════

data class IntegrityReport(
    val totalChecked: Int = 0,
    val healthyCount: Int = 0,
    val corruptedCount: Int = 0,
    val missingCount: Int = 0,
    val emptyCount: Int = 0,
    val details: List<IntegrityDetail> = emptyList()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("totalChecked", totalChecked)
        obj.put("healthyCount", healthyCount)
        obj.put("corruptedCount", corruptedCount)
        obj.put("missingCount", missingCount)
        obj.put("emptyCount", emptyCount)

        val detailsArray = JSONArray()
        for (detail in details) detailsArray.put(detail.toJson())
        obj.put("details", detailsArray)
        return obj
    }
}

data class IntegrityDetail(
    val fileName: String,
    val type: String,
    val health: BackupHealth
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("fileName", fileName)
        obj.put("type", type)
        obj.put("health", health.name)
        return obj
    }
}
