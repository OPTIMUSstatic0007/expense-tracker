package com.example.expensetracker.cloud

data class CloudTransaction(
    val id: String = "",
    val ownerUid: String = "",
    val deviceId: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val version: Long = 1L,
    val syncStatus: String = SyncStatus.PENDING.name,
    val amount: Double = 0.0,
    val type: String = "",
    val category: String = "",
    val note: String = "",
    val deleted: Boolean = false
)
