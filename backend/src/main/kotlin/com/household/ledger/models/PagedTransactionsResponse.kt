package com.household.ledger.models

import kotlinx.serialization.Serializable

@Serializable
data class PagedTransactionsResponse(
    val transactions: List<Transaction>,
    val page: Int,
    val limit: Int,
    val total: Long,
    val hasMore: Boolean
)
