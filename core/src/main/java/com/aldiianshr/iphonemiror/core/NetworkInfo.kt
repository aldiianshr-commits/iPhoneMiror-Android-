package com.aldiianshr.iphonemiror.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.Inet4Address

data class LocalNetworkState(
    val onWifi: Boolean,
    val onEthernet: Boolean,
    val ipv4Addresses: List<String>,
    val interfaceName: String?,
    val gatewayIpv4: String? = null,
) {
    val usable: Boolean get() = (onWifi || onEthernet) && ipv4Addresses.isNotEmpty()

    /** iPhone Personal Hotspot always hands out 172.20.10.0/28 with the iPhone at .1. */
    val onIphoneHotspot: Boolean get() = ipv4Addresses.any { it.startsWith("172.20.10.") }
}

/** Reads the current local network from ConnectivityManager (no location permission needed). */
object NetworkInfo {
    fun current(context: Context): LocalNetworkState {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val link: LinkProperties? = network?.let { cm.getLinkProperties(it) }
        val v4 = link?.linkAddresses.orEmpty()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
        val gw = link?.routes.orEmpty()
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway as? Inet4Address }
            .firstOrNull()?.hostAddress
        return LocalNetworkState(
            onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            onEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
            ipv4Addresses = v4,
            interfaceName = link?.interfaceName,
            gatewayIpv4 = gw,
        )
    }
}
