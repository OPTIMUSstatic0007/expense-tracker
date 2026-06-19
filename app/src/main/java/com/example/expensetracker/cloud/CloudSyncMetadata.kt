package com.example.expensetracker.cloud

data class CloudSyncMetadata(
    val id: String = "",
    val ownerUid: String = "",
    val deviceId: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val version: Long = 1L,
    val syncStatus: String = SyncStatus.SYNCED.name,
    val lastSyncedAt: Long = 0L,
    val lastUploadAt: Long = 0L,
    val lastDownloadAt: Long = 0L
)
