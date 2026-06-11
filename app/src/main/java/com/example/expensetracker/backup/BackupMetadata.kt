package com.example.expensetracker.backup

data class BackupMetadata(
    val lastBackupTime: Long,
    val backupCount: Int,
    val lastBackupType: BackupType,
    val databaseVersion: Int,
    val mainDbSize: Long = 0L,
    val walSize: Long? = null,
    val shmSize: Long? = null
)
