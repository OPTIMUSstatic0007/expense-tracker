package com.household.ledger.models

import kotlinx.serialization.Serializable

@Serializable
data class Transaction(
    val id: Int? = null,
    val date: String,
    val entryType: String, // "Credit" or "Debit"
    val amount: Double,
    val category: String,
    val expenseType: String,
    val paidTo: String,
    val notes: String,
    val balanceAfter: Double? = null
)
