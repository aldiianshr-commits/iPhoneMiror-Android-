package com.aldiianshr.iphonemiror.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.aldiianshr.iphonemiror.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes the receiver over mDNS/DNS-SD (Bonjour) with Android's NsdManager so that
 * the iPhone lists it under Control Center → Screen Mirroring. Registration is only
 * done while the user has the receiver switched on.
 */
class AirPlayAdvertiser(private val context: Context) {

    enum class State { IDLE, REGISTERING, REGISTERED, FAILED }

    data class Status(
        val state: State = State.IDLE,
        val airplayName: String? = null,
        val raopName: String? = null,
        val error: String? = null,
    )

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var airplayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registeredCount = 0

    @Synchronized
    fun register(
        name: String,
        port: Int,
        airplayTxt: Map<String, String>,
        raopName: String,
        raopTxt: Map<String, String>,
    ) {
        unregister()
        acquireMulticastLock()
        registeredCount = 0
        _status.value = Status(State.REGISTERING)
        airplayListener = listener("airplay") { _status.value = _status.value.copy(airplayName = it) }
        raopListener = listener("raop") { _status.value = _status.value.copy(raopName = it) }
        try {
            nsd.registerService(serviceInfo(name, AIRPLAY_TYPE, port, airplayTxt), NsdManager.PROTOCOL_DNS_SD, airplayListener)
            nsd.registerService(serviceInfo(raopName, RAOP_TYPE, port, raopTxt), NsdManager.PROTOCOL_DNS_SD, raopListener)
        } catch (e: Exception) {
            AppLog.e(TAG, "NSD registration failed", e)
            _status.value = Status(State.FAILED, error = e.message)
        }
    }

    @Synchronized
    fun unregister() {
        airplayListener?.let { runCatching { nsd.unregisterService(it) } }
        raopListener?.let { runCatching { nsd.unregisterService(it) } }
        airplayListener = null
        raopListener = null
        releaseMulticastLock()
        _status.value = Status(State.IDLE)
    }

    private fun serviceInfo(name: String, type: String, port: Int, txt: Map<String, String>) =
        NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            setPort(port)
            for ((k, v) in txt) setAttribute(k, v)
        }

    private fun listener(label: String, onName: (String) -> Unit) = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            AppLog.i(TAG, "Registered $label service '${info.serviceName}'")
            onName(info.serviceName)
            synchronized(this@AirPlayAdvertiser) {
                registeredCount++
                if (registeredCount >= 2) _status.value = _status.value.copy(state = State.REGISTERED)
            }
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            AppLog.e(TAG, "Registration of $label failed (code $errorCode)")
            _status.value = _status.value.copy(state = State.FAILED, error = "mDNS $label error $errorCode")
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            AppLog.i(TAG, "Unregistered $label service")
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            AppLog.w(TAG, "Unregistration of $label failed (code $errorCode)")
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        multicastLock = wifi.createMulticastLock("iPhoneMiror-mdns").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) runCatching { it.release() } }
        multicastLock = null
    }

    companion object {
        private const val TAG = "AirPlayAdvertiser"
        const val AIRPLAY_TYPE = "_airplay._tcp"
        const val RAOP_TYPE = "_raop._tcp"
    }
}
