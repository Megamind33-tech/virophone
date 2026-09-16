package com.viroreach.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Picks the best network for internet traffic when [ConnectivityManager.activeNetwork]
 * is stale (e.g. connected Wi-Fi with no route while cellular data is available).
 */
object InternetNetworkSelector {
    private const val TAG = "InternetNetwork"

    data class Selection(
        val network: Network?,
        val transport: String,
        val hasInternet: Boolean,
        val validated: Boolean,
    )

    fun evaluate(cm: ConnectivityManager): Selection {
        var bestValidated: Network? = null
        var bestValidatedTransport = "OTHER"
        var cellularInternet: Network? = null
        var wifiInternet: Network? = null
        var ethernetInternet: Network? = null

        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val transport = transportOf(caps)

            when {
                validated -> {
                    if (bestValidated == null ||
                        (transport == "CELLULAR" && bestValidatedTransport != "CELLULAR")
                    ) {
                        bestValidated = network
                        bestValidatedTransport = transport
                    }
                }
                transport == "CELLULAR" -> cellularInternet = network
                transport == "WIFI" -> wifiInternet = network
                transport == "ETHERNET" -> ethernetInternet = network
            }
        }

        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        val activeWifiUnvalidated = activeCaps?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true

        val chosen = when {
            bestValidated != null -> bestValidated
            activeWifiUnvalidated && cellularInternet != null -> cellularInternet
            cellularInternet != null -> cellularInternet
            wifiInternet != null -> wifiInternet
            ethernetInternet != null -> ethernetInternet
            else -> null
        }

        val transport = when (chosen) {
            bestValidated -> bestValidatedTransport
            cellularInternet -> "CELLULAR"
            wifiInternet -> "WIFI"
            ethernetInternet -> "ETHERNET"
            else -> "NO NETWORK"
        }
        val caps = chosen?.let { cm.getNetworkCapabilities(it) }
        return Selection(
            network = chosen,
            transport = transport,
            hasInternet = chosen != null,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }

    fun bindProcess(context: Context, network: Network?): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            val bound = cm.bindProcessToNetwork(network)
            Log.i(TAG, "bindProcessToNetwork=${network != null} ok=$bound")
            bound
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed", e)
            false
        }
    }

    private fun transportOf(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        else -> "OTHER"
    }
}
