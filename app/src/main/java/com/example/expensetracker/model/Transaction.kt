package com.example.expensetracker.model

data class Transaction(
    var id: String = "",
    var amount: Double = 0.0,
    var type: String = "",
    var category: String = "",
    var note: String = "",
    var timestamp: Long = 0L,
    var updatedAt: Long = 0L,
    var deviceId: String = "",
    var syncStatus: String = ""
)
