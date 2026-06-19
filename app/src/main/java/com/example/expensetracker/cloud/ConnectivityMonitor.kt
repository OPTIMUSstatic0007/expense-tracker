package com.example.expensetracker.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnectivityMonitor(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _connectivityState = MutableStateFlow(currentConnectivityState())
    val connectivityState: StateFlow<ConnectivityState> = _connectivityState.asStateFlow()

    private var isStarted = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateState(ConnectivityState.Online)
        }

        override fun onLost(network: Network) {
            updateState(currentConnectivityState())
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            updateState(stateFromCapabilities(networkCapabilities))
        }
    }

    fun start() {
        if (isStarted) {
            return
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            isStarted = true
            updateState(currentConnectivityState())
            SyncLogger.info("Connectivity monitoring started")
        } catch (exception: Exception) {
            SyncLogger.error("Connectivity monitoring failed to start", exception)
        }
    }

    fun stop() {
        if (!isStarted) {
            return
        }

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            SyncLogger.info("Connectivity monitoring stopped")
        } catch (exception: Exception) {
            SyncLogger.warning("Connectivity monitoring failed to stop cleanly", exception)
        } finally {
            isStarted = false
        }
    }

    private fun updateState(state: ConnectivityState) {
        if (_connectivityState.value::class != state::class) {
            _connectivityState.value = state
            SyncLogger.info("Connectivity changed: ${state.javaClass.simpleName}")
        } else {
            _connectivityState.value = state
        }
    }

    private fun currentConnectivityState(): ConnectivityState {
        val network = connectivityManager.activeNetwork ?: return ConnectivityState.Offline
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return ConnectivityState.Offline
        return stateFromCapabilities(capabilities)
    }

    private fun stateFromCapabilities(capabilities: NetworkCapabilities): ConnectivityState {
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            ConnectivityState.Online
        } else {
            ConnectivityState.Offline
        }
    }
}
