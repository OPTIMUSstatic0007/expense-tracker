package com.example.expensetracker.cloud

data class CloudUserMetadata(
    val id: String = "",
    val ownerUid: String = "",
    val deviceId: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val version: Long = 1L,
    val syncStatus: String = SyncStatus.SYNCED.name,
    val email: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    val lastSignInAt: Long = 0L
)
