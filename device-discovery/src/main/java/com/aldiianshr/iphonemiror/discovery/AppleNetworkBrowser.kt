package com.aldiianshr.iphonemiror.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.aldiianshr.iphonemiror.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Browses the local network for Bonjour services that Apple devices publish on their own
 * (Continuity / Rapport `_companion-link._tcp`, `_rdlink._tcp`, and Wi-Fi sync
 * `_apple-mobdev2._tcp`). This only proves an Apple device is reachable on the same
 * Wi-Fi; mirroring itself is always started from the iPhone.
 */
class AppleNetworkBrowser(context: Context) {

    data class NetworkAppleDevice(
        val serviceName: String,
        val serviceTypes: Set<String>,
        val lastSeen: Long,
    )

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val found = LinkedHashMap<String, NetworkAppleDevice>()
    private val _devices = MutableStateFlow<List<NetworkAppleDevice>>(emptyList())
    val devices: StateFlow<List<NetworkAppleDevice>> = _devices.asStateFlow()
    private val _browsing = MutableStateFlow(false)
    val browsing: StateFlow<Boolean> = _browsing.asStateFlow()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    @Synchronized
    fun start() {
        if (listeners.isNotEmpty()) return
        synchronized(found) { found.clear() }
        _devices.value = emptyList()
        for (type in SERVICE_TYPES) {
            val l = discoveryListener(type)
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
                listeners += l
            } catch (e: Exception) {
                AppLog.w(TAG, "discoverServices($type) failed", e)
            }
        }
        _browsing.value = listeners.isNotEmpty()
    }

    @Synchronized
    fun stop() {
        for (l in listeners) runCatching { nsd.stopServiceDiscovery(l) }
        listeners.clear()
        _browsing.value = false
    }

    private fun discoveryListener(type: String) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = AppLog.d(TAG, "Browsing $serviceType")
        override fun onDiscoveryStopped(serviceType: String) = AppLog.d(TAG, "Stopped $serviceType")
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
            AppLog.w(TAG, "Browse $serviceType failed ($errorCode)")
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(info: NsdServiceInfo) {
            val name = cleanName(info.serviceName)
            synchronized(found) {
                val prev = found[name]
                found[name] = NetworkAppleDevice(
                    serviceName = name,
                    serviceTypes = (prev?.serviceTypes.orEmpty()) + type.trimEnd('.'),
                    lastSeen = System.currentTimeMillis(),
                )
                _devices.value = found.values.toList()
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            val name = cleanName(info.serviceName)
            synchronized(found) {
                val prev = found[name] ?: return
                val remaining = prev.serviceTypes - type.trimEnd('.')
                if (remaining.isEmpty()) found.remove(name) else found[name] = prev.copy(serviceTypes = remaining)
                _devices.value = found.values.toList()
            }
        }
    }

    companion object {
        private const val TAG = "AppleBrowser"
        val SERVICE_TYPES = listOf("_companion-link._tcp", "_rdlink._tcp", "_apple-mobdev2._tcp")

        /** `_apple-mobdev2` names look like `aa:bb:..@fe80::...`; keep the readable part. */
        fun cleanName(raw: String): String = raw.substringBefore("@").replace("\\032", " ").trim()
    }
}
