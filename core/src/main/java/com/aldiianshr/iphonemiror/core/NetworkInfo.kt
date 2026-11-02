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
) {
    val usable: Boolean get() = (onWifi || onEthernet) && ipv4Addresses.isNotEmpty()
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
        return LocalNetworkState(
            onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            onEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
            ipv4Addresses = v4,
            interfaceName = link?.interfaceName,
        )
    }
}
