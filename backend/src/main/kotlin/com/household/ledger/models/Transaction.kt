package com.household.ledger.models

import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class Transaction(
    val id: Int? = null,
    val date: String,
    val entryType: String, // "Credit" or "Debit"
    @Serializable(with = BigDecimalSerializer::class)
    val amount: BigDecimal,
    val category: String,
    val expenseType: String,
    val paidTo: String,
    val notes: String,
    @Serializable(with = BigDecimalSerializer::class)
    val balanceAfter: BigDecimal? = null
)
