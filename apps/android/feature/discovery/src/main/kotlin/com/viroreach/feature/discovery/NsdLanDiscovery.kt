package com.viroreach.feature.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.viroreach.core.model.CallRouteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Real Android NSD/mDNS LAN discovery for Viro Reach.
 * Advertises only protocol-level TXT records — no PII.
 */
class NsdLanDiscovery(
    private val context: Context,
    private val ephemeralIdGenerator: EphemeralIdGenerator,
    private val bindingTagProvider: (() -> String?)? = null,
    private val onPeerDiscovered: suspend (
        ephemeralId: String,
        transport: CallRouteType,
        bindingTag: String?,
        hostAddress: String,
        signalingPort: Int,
    ) -> Unit,
) {
    companion object {
        private const val TAG = "NsdLanDiscovery"
        const val SERVICE_TYPE = "_viroreach._tcp."
        const val SERVICE_VERSION = "1"
        const val SIGNALING_PORT = 8765
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _anonymousPeerCount = MutableStateFlow(0)
    val anonymousPeerCount: StateFlow<Int> = _anonymousPeerCount.asStateFlow()

    private val discoveredEphemeralIds = mutableSetOf<String>()
    private var isRunning = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isRunning(): Boolean = isRunning

    fun start() {
        if (isRunning) return
        isRunning = true
        registerService()
        discoverServices()
    }

    fun stop() {
        isRunning = false
        registrationListener?.let { nsdManager.unregisterService(it) }
        discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        discoveredEphemeralIds.clear()
        _anonymousPeerCount.value = 0
    }

    private fun registerService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "viro-${ephemeralIdGenerator.getCurrentId().takeLast(8)}"
            serviceType = SERVICE_TYPE
            port = SIGNALING_PORT
            setAttribute("protocol", "viro-reach")
            setAttribute("version", SERVICE_VERSION)
            setAttribute("eid", ephemeralIdGenerator.getCurrentId())
            setAttribute("cap", "voice")
            bindingTagProvider?.invoke()?.let { setAttribute("btag", it) }
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "Registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun discoverServices() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.d(TAG, "Discovery started: $type")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != SERVICE_TYPE) return
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val eid = info.attributes?.get("eid")?.let { String(it) } ?: return
                        val btag = info.attributes?.get("btag")?.let { String(it) }
                        val host = info.host?.hostAddress ?: return
                        val port = info.port.takeIf { it > 0 } ?: SIGNALING_PORT
                        if (eid == ephemeralIdGenerator.getCurrentId()) return
                        if (discoveredEphemeralIds.add(eid)) {
                            _anonymousPeerCount.value = discoveredEphemeralIds.size
                            scope.launch {
                                onPeerDiscovered(eid, CallRouteType.LAN, btag, host, port)
                            }
                        }
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, code: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, code: Int) {}
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }
}
