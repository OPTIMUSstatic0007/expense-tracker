package com.example.expensetracker.backup

sealed class BackupResult {
    data class Success(
        val backupFileName: String,
        val backupPath: String,
        val timestamp: Long
    ) : BackupResult()

    data class Failure(
        val errorMessage: String
    ) : BackupResult()
}
