package com.example.expensetracker.backup

import android.content.Context
import com.example.expensetracker.local.ExpenseDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupManager(
    private val context: Context,
    private val database: ExpenseDatabase
) {

    companion object {
        private const val DB_NAME = "expense_database"
        private const val BACKUPS_DIR = "backups"
    }

    fun getBackupDirectory(): File {
        return File(context.filesDir, BACKUPS_DIR)
    }

    fun createBackup(type: BackupType): BackupResult {
        return try {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) {
                return BackupResult.Failure("Database file not found at ${dbFile.absolutePath}")
            }

            val backupsDir = File(context.filesDir, BACKUPS_DIR)
            val typeDir = File(backupsDir, type.name.lowercase(Locale.getDefault()))

            if (!typeDir.exists() && !typeDir.mkdirs()) {
                 return BackupResult.Failure("Failed to create backup directory ${typeDir.absolutePath}")
            }

            val metadataDir = File(backupsDir, "metadata")
            if (!metadataDir.exists()) {
                metadataDir.mkdirs()
            }

            val timestamp = System.currentTimeMillis()
            val dateFormat = SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.getDefault())
            val dateString = dateFormat.format(Date(timestamp))
            val baseBackupName = "backup_$dateString.db"

            // 1. Checkpoint Database
            checkpointDatabase()

            // 2. Backup Files
            val (mainDbCopy, walCopy, shmCopy) = backupDatabaseFiles(dbFile, typeDir, baseBackupName)

            // 3. Validate
            if (!validateBackup(dbFile, mainDbCopy, walCopy, shmCopy)) {
                return BackupResult.Failure("Backup validation failed")
            }

            // 4. Prepare Metadata (For future persistence)
            val metadata = BackupMetadata(
                lastBackupTime = timestamp,
                backupCount = 1, // Placeholder
                lastBackupType = type,
                databaseVersion = database.openHelper.readableDatabase.version,
                mainDbSize = mainDbCopy.length(),
                walSize = walCopy?.length(),
                shmSize = shmCopy?.length()
            )

            BackupResult.Success(
                backupFileName = baseBackupName,
                backupPath = mainDbCopy.absolutePath,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            BackupResult.Failure("Backup failed: ${e.message}")
        }
    }

    private fun checkpointDatabase() {
        try {
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToNext() }
        } catch (e: Exception) {
            // Checkpointing failed, but we still try to backup
            e.printStackTrace()
        }
    }

    private fun backupDatabaseFiles(
        sourceDb: File,
        targetDir: File,
        baseBackupName: String
    ): Triple<File, File?, File?> {
        val mainDbCopy = File(targetDir, baseBackupName)
        copyFile(sourceDb, mainDbCopy)

        val sourceWal = File("${sourceDb.absolutePath}-wal")
        var walCopy: File? = null
        if (sourceWal.exists()) {
            walCopy = File(targetDir, "$baseBackupName-wal")
            copyFile(sourceWal, walCopy)
        }

        val sourceShm = File("${sourceDb.absolutePath}-shm")
        var shmCopy: File? = null
        if (sourceShm.exists()) {
            shmCopy = File(targetDir, "$baseBackupName-shm")
            copyFile(sourceShm, shmCopy)
        }

        return Triple(mainDbCopy, walCopy, shmCopy)
    }

    private fun validateBackup(
        sourceDb: File,
        mainDbCopy: File,
        walCopy: File?,
        shmCopy: File?
    ): Boolean {
        if (!mainDbCopy.exists() || mainDbCopy.length() == 0L) return false
        if (sourceDb.length() != mainDbCopy.length()) return false

        if (walCopy != null && (!walCopy.exists() || walCopy.length() == 0L)) return false
        if (shmCopy != null && (!shmCopy.exists() || shmCopy.length() == 0L)) return false

        return true
    }

    private fun copyFile(source: File, destination: File) {
        source.copyTo(destination, overwrite = true)
    }
}
