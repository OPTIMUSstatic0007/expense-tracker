package com.example.expensetracker.cloud

sealed class ConnectivityState {
    object Online : ConnectivityState()
    object Offline : ConnectivityState()
}
