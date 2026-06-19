package com.example.expensetracker.cloud

sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
    data class Error(val message: String) : SyncState()
    data class Disabled(val reason: String) : SyncState()
}
