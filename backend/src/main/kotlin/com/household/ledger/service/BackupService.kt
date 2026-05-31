package com.household.ledger.service

import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BackupService {
    private val logger = LoggerFactory.getLogger(BackupService::class.java)

    // Robust Database File Location
    private val dbFile: File by lazy {
        val userDir = System.getProperty("user.dir")
        val possiblePaths = listOf(
            File(userDir, "backend/data/ledger.db"),
            File(userDir, "data/ledger.db"),
            File(userDir, "ledger.db")
        )
        val found = possiblePaths.firstOrNull { it.exists() }
        if (found == null) {
            logger.warn("Database file not found in standard locations. Defaulting to backend/data/ledger.db")
            File(userDir, "backend/data/ledger.db")
        } else {
            logger.info("Database found at: ${found.absolutePath}")
            found
        }
    }

    // Local Project Backup Directory
    private val projectBackupDir: File by lazy {
        val userDir = System.getProperty("user.dir")
        val dir = File(userDir, "backend/data/backups")
        if (!dir.exists()) {
            dir.mkdirs()
            logger.info("Created local backup directory: ${dir.absolutePath}")
        }
        dir
    }

    // External Safety Backup Directory (Documents)
    private val externalBackupDir: File by lazy {
        val userHome = System.getProperty("user.home")
        val dir = File(userHome, "Documents/ExpenseTracker/Backups")
        if (!dir.exists()) {
            dir.mkdirs()
            logger.info("Created external backup directory: ${dir.absolutePath}")
        }
        dir
    }

    fun getDatabaseFile(): File = dbFile

    /**
     * Generates a consistent filename for database backups.
     * Publicly accessible for route responses.
     */
    fun generateBackupFileName(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss")
        val timestamp = LocalDateTime.now().format(formatter)
        return "ledger_backup_$timestamp.db"
    }

    /**
     * Physically creates backup files and enforces retention.
     */
    fun createAutoBackup(): Boolean {
        logger.info("--- Starting Automatic Backup ---")
        try {
            if (!dbFile.exists()) {
                logger.error("Source database missing: ${dbFile.absolutePath}")
                return false
            }

            val fileName = generateBackupFileName()
            
            // 1. Write to Project folder
            val projectTarget = File(projectBackupDir, fileName)
            dbFile.copyTo(projectTarget, overwrite = true)
            logger.info("SUCCESS: Project backup created -> ${projectTarget.absolutePath}")

            // 2. Write to Documents folder
            val externalTarget = File(externalBackupDir, fileName)
            dbFile.copyTo(externalTarget, overwrite = true)
            logger.info("SUCCESS: External backup created -> ${externalTarget.absolutePath}")

            enforceRetentionPolicy()
            return true
        } catch (e: Exception) {
            logger.error("CRITICAL: Backup process failed: ${e.message}", e)
            return false
        }
    }

    private fun enforceRetentionPolicy() {
        listOf(projectBackupDir, externalBackupDir).forEach { dir ->
            val backups = dir.listFiles { f -> f.name.startsWith("ledger_backup") && f.name.endsWith(".db") }
            if (backups != null && backups.size > 10) {
                val toDelete = backups.sortedByDescending { it.lastModified() }.drop(10)
                toDelete.forEach { 
                    if (it.delete()) logger.info("Retention: Purged old backup ${it.name}")
                }
            }
        }
    }

    fun getBackupStatus(): Map<String, String> {
        // Read directly from the REAL project backup folder
        val backups = projectBackupDir.listFiles { f -> f.name.endsWith(".db") } ?: emptyArray()
        val lastBackup = backups.maxByOrNull { it.lastModified() }
        val lastTime = lastBackup?.let {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it.lastModified()), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        } ?: "Never"

        return mapOf(
            "lastBackupTime" to lastTime,
            "backupCount" to backups.size.toString(),
            "status" to "Active",
            "activePath" to projectBackupDir.absolutePath
        )
    }

    fun restoreDatabase(tempFile: File): Boolean {
        logger.info("RESTORE: Replacing live database with uploaded file...")
        try {
            // Safety snapshot before destructive change
            createAutoBackup()

            val isValid = tempFile.inputStream().use { input ->
                val bytes = ByteArray(16)
                val read = input.read(bytes)
                read == 16 && String(bytes).startsWith("SQLite format 3")
            }
            
            if (!isValid) {
                logger.error("RESTORE FAILED: Uploaded file is not a valid SQLite database.")
                return false
            }

            tempFile.copyTo(dbFile, overwrite = true)
            logger.info("RESTORE SUCCESS: Live database updated.")
            return true
        } catch (e: Exception) {
            logger.error("RESTORE ERROR: ${e.message}")
            return false
        }
    }
}
