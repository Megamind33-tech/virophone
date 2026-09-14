package com.viroreach.feature.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Looper
import com.viroreach.core.model.CallRouteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wi-Fi Direct DNS-SD discovery — advertises ephemeral ID only (no PII).
 */
class WifiDirectDiscovery(
    private val context: Context,
    private val ephemeralIdGenerator: EphemeralIdGenerator,
    private val onPeerDiscovered: suspend (ephemeralId: String, transport: CallRouteType, bindingTag: String?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(context, Looper.getMainLooper(), null)
    private var receiverRegistered = false
    private val discovered = mutableSetOf<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> { /* peers updated */ }
            }
        }
    }

    fun isAvailable(): Boolean = manager != null && channel != null

    fun start() {
        if (!isAvailable()) return
        if (!receiverRegistered) {
            context.registerReceiver(
                receiver,
                IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION),
            )
            receiverRegistered = true
        }
        registerLocalService()
        discoverServices()
    }

    fun stop() {
        if (receiverRegistered) {
            context.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        channel?.let { ch ->
            manager?.clearLocalServices(ch, null)
            manager?.stopPeerDiscovery(ch, null)
        }
        discovered.clear()
    }

    private fun registerLocalService() {
        val eid = ephemeralIdGenerator.getCurrentId()
        val record = mapOf(
            "protocol" to "viro-reach",
            "version" to "1",
            "eid" to eid,
            "cap" to "voice",
        )
        val info = WifiP2pDnsSdServiceInfo.newInstance("_viroreach._tcp", "local.", record)
        channel?.let { ch -> manager?.addLocalService(ch, info, null) }
    }

    private fun discoverServices() {
        val request = WifiP2pDnsSdServiceRequest.newInstance()
        channel?.let { ch ->
            manager?.setDnsSdResponseListeners(
                ch,
                { _, _, _ -> /* service instance discovered; TXT arrives separately */ },
                { _, txtRecord, _ ->
                    val eid = txtRecord["eid"] ?: return@setDnsSdResponseListeners
                    val btag = txtRecord["btag"]
                    if (!eid.startsWith("vr1_")) return@setDnsSdResponseListeners
                    if (eid == ephemeralIdGenerator.getCurrentId()) return@setDnsSdResponseListeners
                    if (discovered.add(eid)) {
                        scope.launch { onPeerDiscovered(eid, CallRouteType.WIFI_DIRECT, btag) }
                    }
                },
            )
            manager?.addServiceRequest(ch, request, null)
            manager?.discoverServices(ch, null)
        }
    }
}
