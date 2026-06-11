package com.example.expensetracker.backup

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupManager(private val context: Context) {

    companion object {
        private const val DB_NAME = "expense_database"
        private const val BACKUPS_DIR = "backups"
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

            // Also ensure metadata directory exists for future use
            val metadataDir = File(backupsDir, "metadata")
            if (!metadataDir.exists()) {
                metadataDir.mkdirs()
            }

            val timestamp = System.currentTimeMillis()
            val dateFormat = SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.getDefault())
            val dateString = dateFormat.format(Date(timestamp))
            val backupFileName = "backup_$dateString.db"
            val backupFile = File(typeDir, backupFileName)

            copyFile(dbFile, backupFile)

            BackupResult.Success(
                backupFileName = backupFileName,
                backupPath = backupFile.absolutePath,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            BackupResult.Failure("Backup failed: ${e.message}")
        }
    }

    private fun copyFile(source: File, destination: File) {
        FileInputStream(source).use { inputStream ->
            FileOutputStream(destination).use { outputStream ->
                val buffer = ByteArray(1024)
                var length: Int
                while (inputStream.read(buffer).also { length = it } > 0) {
                    outputStream.write(buffer, 0, length)
                }
            }
        }
    }
}
