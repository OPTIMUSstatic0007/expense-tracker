package com.example.expensetracker.backup

import android.content.Context
import com.example.expensetracker.local.ExpenseDatabase
import java.io.File

data class BackupInfo(
    val fileName: String,
    val backupType: String,
    val timestamp: Long,
    val sizeBytes: Long
)

class RestoreManager(
    private val context: Context,
    private val database: ExpenseDatabase,
    private val backupManager: BackupManager
) {

    fun getAvailableBackups(): List<BackupInfo> {
        val backupsDir = backupManager.getBackupDirectory()
        val types = listOf("manual", "auto", "emergency")
        val backups = mutableListOf<BackupInfo>()

        for (type in types) {
            val typeDir = File(backupsDir, type)
            if (typeDir.exists() && typeDir.isDirectory) {
                typeDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".db")) {
                        try {
                            val nameParts = file.name.substringBeforeLast(".db").split("_")
                            if (nameParts.size >= 4 && nameParts[0] == "backup") {
                                // Expected format: backup_yyyy_MM_dd_HHmmss.db
                                val timestampStr = "${nameParts[1]}_${nameParts[2]}_${nameParts[3]}"
                                val sdf = java.text.SimpleDateFormat("yyyy_MM_dd_HHmmss", java.util.Locale.getDefault())
                                val date = sdf.parse(timestampStr)
                                val timestamp = date?.time ?: file.lastModified()

                                backups.add(BackupInfo(
                                    fileName = file.name,
                                    backupType = type,
                                    timestamp = timestamp,
                                    sizeBytes = file.length()
                                ))
                            } else {
                                backups.add(BackupInfo(
                                    fileName = file.name,
                                    backupType = type,
                                    timestamp = file.lastModified(),
                                    sizeBytes = file.length()
                                ))
                            }
                        } catch (e: Exception) {
                            // Fallback if parsing fails
                            backups.add(BackupInfo(
                                fileName = file.name,
                                backupType = type,
                                timestamp = file.lastModified(),
                                sizeBytes = file.length()
                            ))
                        }
                    }
                }
            }
        }

        return backups.sortedByDescending { it.timestamp }
    }

    fun restoreDatabase(fileName: String): Boolean {
        // 1. Create emergency backup
        val emergencyBackupResult = backupManager.createBackup(BackupType.EMERGENCY)
        if (emergencyBackupResult is BackupResult.Failure) {
            return false
        }

        // 2. Discover backup file
        val backupsDir = backupManager.getBackupDirectory()
        var targetBackup: File? = null

        for (type in listOf("manual", "auto", "emergency")) {
            val file = File(File(backupsDir, type), fileName)
            if (file.exists()) {
                targetBackup = file
                break
            }
        }

        if (targetBackup == null || !targetBackup.exists() || targetBackup.length() == 0L) {
            return false
        }

        // 3. Validate main DB exists and size > 0
        if (targetBackup.length() == 0L) return false

        // 4. Close database
        database.close()

        // 5. Replace files
        val dbFile = context.getDatabasePath("expense_database")

        try {
            targetBackup.copyTo(dbFile, overwrite = true)

            val backupBaseName = targetBackup.absolutePath.substringBeforeLast(".db")
            val walBackup = File("$backupBaseName.db-wal")
            val walFile = File("${dbFile.absolutePath}-wal")
            if (walBackup.exists()) {
                walBackup.copyTo(walFile, overwrite = true)
            } else {
                walFile.delete()
            }

            val shmBackup = File("$backupBaseName.db-shm")
            val shmFile = File("${dbFile.absolutePath}-shm")
            if (shmBackup.exists()) {
                shmBackup.copyTo(shmFile, overwrite = true)
            } else {
                shmFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        // 6. Reset instance and re-open to check integrity
        ExpenseDatabase.resetInstance()
        val newDb = ExpenseDatabase.getInstance(context)

        return try {
            val cursor = newDb.openHelper.readableDatabase.query("PRAGMA integrity_check;")
            var isOk = false
            if (cursor.moveToFirst()) {
                val result = cursor.getString(0)
                isOk = result.equals("ok", ignoreCase = true)
            }
            cursor.close()
            isOk
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
