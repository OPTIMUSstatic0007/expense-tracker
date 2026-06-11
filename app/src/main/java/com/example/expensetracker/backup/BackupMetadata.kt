package com.example.expensetracker.backup

data class BackupMetadata(
    val lastBackupTime: Long,
    val backupCount: Int,
    val lastBackupType: BackupType,
    val databaseVersion: Int
)
