package com.example.expensetracker.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey
    val id: String,
    val amount: Double,
    val type: String,
    val category: String,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val syncPending: Boolean
)
