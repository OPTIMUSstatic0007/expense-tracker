package com.household.ledger.service

import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BackupService {
    private val dbPath = "backend/data/ledger.db"
    
    // External path: user.home/Documents/ExpenseTracker/Backups
    private val externalBackupDir: File by lazy {
        val userHome = System.getProperty("user.home")
        val dir = File(userHome, "Documents/ExpenseTracker/Backups")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    fun getDatabaseFile(): File = File(dbPath)

    fun generateBackupFileName(isAuto: Boolean = false): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss"))
        val prefix = if (isAuto) "auto_ledger_backup" else "ledger_backup"
        return "${prefix}_$timestamp.db"
    }

    /**
     * Creates a new backup and enforces the retention policy (latest 10)
     */
    fun createAutoBackup(): Boolean {
        try {
            val currentDb = File(dbPath)
            if (!currentDb.exists()) return false

            val backupFile = File(externalBackupDir, generateBackupFileName(isAuto = true))
            currentDb.copyTo(backupFile, overwrite = true)

            enforceRetentionPolicy()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun enforceRetentionPolicy() {
        val backups = externalBackupDir.listFiles { file -> 
            file.name.startsWith("auto_ledger_backup") && file.name.endsWith(".db") 
        } ?: return

        if (backups.size > 10) {
            val sortedBackups = backups.sortedByDescending { it.lastModified() }
            sortedBackups.drop(10).forEach { it.delete() }
        }
    }

    fun getBackupStatus(): Map<String, String> {
        val backups = externalBackupDir.listFiles { file -> 
            file.name.endsWith(".db") 
        } ?: emptyArray()
        
        val lastBackup = backups.maxByOrNull { it.lastModified() }
        val lastTime = if (lastBackup != null) {
            val instant = Instant.ofEpochMilli(lastBackup.lastModified())
            val ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        } else {
            "Never"
        }

        return mapOf(
            "lastBackupTime" to lastTime,
            "backupCount" to backups.size.toString(),
            "status" to "Active",
            "externalPath" to externalBackupDir.absolutePath
        )
    }

    fun restoreDatabase(tempFile: File): Boolean {
        val currentDb = File(dbPath)
        
        try {
            // Always create an emergency auto backup before restoring
            createAutoBackup()

            // Validate SQLite header
            val isValid = tempFile.inputStream().use { input ->
                val bytes = ByteArray(16)
                val read = input.read(bytes)
                read == 16 && bytes.decodeToString().startsWith("SQLite format 3")
            }
            if (!isValid) return false

            tempFile.copyTo(currentDb, overwrite = true)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
