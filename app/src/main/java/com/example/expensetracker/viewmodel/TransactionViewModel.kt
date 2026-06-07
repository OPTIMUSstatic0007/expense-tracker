package com.example.expensetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.local.TransactionEntity
import com.example.expensetracker.repository.LocalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TransactionViewModel(private val repository: LocalRepository) : ViewModel() {

    val allTransactions: StateFlow<List<TransactionEntity>> = repository.getAllTransactions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val totalIncome: StateFlow<Double> = allTransactions.map { transactions ->
        transactions.filter { it.type.equals("Credit", ignoreCase = true) && !it.deleted }
            .sumOf { it.amount }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0.0
    )

    val totalExpenses: StateFlow<Double> = allTransactions.map { transactions ->
        transactions.filter { it.type.equals("Debit", ignoreCase = true) && !it.deleted }
            .sumOf { it.amount }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0.0
    )

    val totalBalance: StateFlow<Double> = allTransactions.map { transactions ->
        transactions.filter { !it.deleted }.sumOf {
            if (it.type.equals("Credit", ignoreCase = true)) it.amount else -it.amount
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0.0
    )

    fun getTransactionsByMonth(start: Long, end: Long): Flow<List<TransactionEntity>> {
        return repository.getTransactionsByMonth(start, end)
    }

    fun insertTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            try {
                repository.insertTransaction(transaction)
            } catch (e: Exception) {
                // Log or handle error appropriately in a production app
                e.printStackTrace()
            }
        }
    }

    fun updateTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            try {
                repository.updateTransaction(transaction)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun softDeleteTransaction(id: String) {
        viewModelScope.launch {
            try {
                repository.softDeleteTransaction(id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    class Factory(private val repository: LocalRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TransactionViewModel::class.java)) {
                return TransactionViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
