package com.example.expensetracker.backup

import android.content.Context
import com.example.expensetracker.local.ExpenseDatabase
import java.io.File
import android.util.Log

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
        Log.d("RestoreManager", "[RESTORE] getAvailableBackups called")
        val backupsDir = backupManager.getBackupDirectory()
        Log.d("RestoreManager", "[RESTORE] backupsDir=${backupsDir.absolutePath} exists=${backupsDir.exists()}")
        val types = listOf("manual", "auto", "emergency")
        val backups = mutableListOf<BackupInfo>()

        for (type in types) {
            val typeDir = File(backupsDir, type)
            Log.d("RestoreManager", "[RESTORE] scanning $type dir: ${typeDir.absolutePath} exists=${typeDir.exists()}")
            if (typeDir.exists() && typeDir.isDirectory) {
                val files = typeDir.listFiles()
                Log.d("RestoreManager", "[RESTORE] $type dir has ${files?.size ?: 0} total files")
                typeDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".db")) {
                        Log.d("RestoreManager", "[RESTORE] found .db file: ${file.name} size=${file.length()}")
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
                            Log.e("RestoreManager", "[RESTORE] error parsing backup file ${file.name}", e)
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

        Log.d("RestoreManager", "[RESTORE] total backups found: ${backups.size}")
        return backups.sortedByDescending { it.timestamp }
    }

    fun restoreDatabase(fileName: String): Boolean {
        Log.d("RestoreManager", "[RESTORE] restore requested")
        Log.d("RestoreManager", "[RESTORE] initiating restore sequence for: $fileName")

        // 1. Create emergency backup
        Log.d("RestoreManager", "[RESTORE] emergency snapshot started")
        val emergencyBackupResult = backupManager.createBackup(BackupType.EMERGENCY)
        if (emergencyBackupResult is BackupResult.Failure) {
            Log.e("RestoreManager", "[RESTORE] ABORTED: emergency backup failed: ${emergencyBackupResult.errorMessage}")
            return false
        }
        if (emergencyBackupResult is BackupResult.Success) {
            Log.d("RestoreManager", "[RESTORE] emergency snapshot completed: ${emergencyBackupResult.backupPath}")
        }

        // 2. Discover backup file
        val backupsDir = backupManager.getBackupDirectory()
        var targetBackup: File? = null

        for (type in listOf("manual", "auto", "emergency")) {
            val file = File(File(backupsDir, type), fileName)
            Log.d("RestoreManager", "[RESTORE] checking ${type}/${fileName} exists=${file.exists()}")
            if (file.exists()) {
                targetBackup = file
                Log.d("RestoreManager", "[RESTORE] target backup discovered at: ${file.absolutePath}")
                break
            }
        }

        if (targetBackup == null || !targetBackup.exists()) {
            Log.e("RestoreManager", "[RESTORE] ABORTED: backup file not found: $fileName")
            return false
        }

        // 3. Validate main DB exists and size > 0
        if (targetBackup.length() == 0L) {
            Log.e("RestoreManager", "[RESTORE] ABORTED: backup file is empty (0 bytes)")
            return false
        }

        Log.d("RestoreManager", "[RESTORE] backup validated, size=${targetBackup.length()} bytes")

        // 4. Close database
        Log.d("RestoreManager", "[RESTORE] database close started")
        database.close()
        Log.d("RestoreManager", "[RESTORE] database closed")

        // 5. Replace files
        val dbFile = context.getDatabasePath("expense_database")
        Log.d("RestoreManager", "[RESTORE] database replacement started at: ${dbFile.absolutePath}")

        try {
            targetBackup.copyTo(dbFile, overwrite = true)
            Log.d("RestoreManager", "[RESTORE] main .db file copied")

            val backupBaseName = targetBackup.absolutePath.substringBeforeLast(".db")
            val walBackup = File("$backupBaseName.db-wal")
            val walFile = File("${dbFile.absolutePath}-wal")
            if (walBackup.exists()) {
                walBackup.copyTo(walFile, overwrite = true)
                Log.d("RestoreManager", "[RESTORE] .db-wal file restored")
            } else {
                walFile.delete()
                Log.d("RestoreManager", "[RESTORE] no .db-wal in backup, deleted existing")
            }

            val shmBackup = File("$backupBaseName.db-shm")
            val shmFile = File("${dbFile.absolutePath}-shm")
            if (shmBackup.exists()) {
                shmBackup.copyTo(shmFile, overwrite = true)
                Log.d("RestoreManager", "[RESTORE] .db-shm file restored")
            } else {
                shmFile.delete()
                Log.d("RestoreManager", "[RESTORE] no .db-shm in backup, deleted existing")
            }
            Log.d("RestoreManager", "[RESTORE] database replacement completed")
        } catch (e: Exception) {
            Log.e("RestoreManager", "[RESTORE] database replacement FAILED: ${e.message}", e)
            return false
        }

        // 6. Reset instance and re-open to check integrity
        Log.d("RestoreManager", "[RESTORE] integrity check started")
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

            if (isOk) {
                Log.d("RestoreManager", "[RESTORE] integrity check PASSED — restore completed")
            } else {
                Log.e("RestoreManager", "[RESTORE] integrity check FAILED")
            }
            isOk
        } catch (e: Exception) {
            Log.e("RestoreManager", "[RESTORE] integrity check crashed: ${e.message}", e)
            false
        }
    }
}
