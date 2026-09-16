package com.viroreach.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkLabel = MutableStateFlow("UNKNOWN")
    val networkLabel: StateFlow<String> = _networkLabel.asStateFlow()

    /** Strict Android validation — can lag on mobile data even when browsing works. */
    private val _internetValidated = MutableStateFlow(false)
    val internetValidated: StateFlow<Boolean> = _internetValidated.asStateFlow()

    /**
     * Lenient reachability for calling/signaling: INTERNET on a usable transport.
     * Scans all networks — not only [ConnectivityManager.activeNetwork].
     */
    private val _hasInternet = MutableStateFlow(false)
    val hasInternet: StateFlow<Boolean> = _hasInternet.asStateFlow()

    /** Increments when the bound transport or selected network changes. */
    private val _networkGeneration = MutableStateFlow(0)
    val networkGeneration: StateFlow<Int> = _networkGeneration.asStateFlow()

    private var lastTransportLabel: String? = null
    private var lastBoundNetwork: Network? = null
    private var lastNetworkHandle: Int? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateState()
        override fun onLost(network: Network) = updateState()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateState()
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        updateState()
    }

    private fun updateState() {
        val selection = InternetNetworkSelector.evaluate(connectivityManager)

        _internetValidated.value = selection.validated
        _hasInternet.value = selection.hasInternet
        _networkLabel.value = selection.transport

        val networkHandle = selection.network?.hashCode()
        val networkChanged = lastNetworkHandle != null && lastNetworkHandle != networkHandle
        val transportChanged = lastTransportLabel != null && lastTransportLabel != selection.transport

        if (selection.network != lastBoundNetwork) {
            InternetNetworkSelector.bindProcess(appContext, selection.network)
            lastBoundNetwork = selection.network
        }

        if (networkChanged || transportChanged) {
            _networkGeneration.value = _networkGeneration.value + 1
        }
        lastTransportLabel = selection.transport
        lastNetworkHandle = networkHandle
    }

    fun unregister() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }
}
