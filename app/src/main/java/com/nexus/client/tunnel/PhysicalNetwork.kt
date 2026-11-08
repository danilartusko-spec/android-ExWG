package com.nexus.client.tunnel

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.os.Build
import java.net.Inet6Address

/** Shared selection for VPN capabilities and rule downloads; never selects the VPN itself. */
object PhysicalNetwork {
    fun select(manager: ConnectivityManager, excluded: Network? = null): Network? {
        val candidates = manager.allNetworks.filter { network ->
            if (network == excluded) return@filter false
            val caps = manager.getNetworkCapabilities(network)
            val links = manager.getLinkProperties(network)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                links != null && links.routes.any { it.isDefaultRoute }
        }
        if (Build.VERSION.SDK_INT >= 23) manager.activeNetwork?.takeIf { it in candidates }?.let { return it }
        @Suppress("DEPRECATION")
        val activeType = manager.activeNetworkInfo?.type
        return candidates.maxByOrNull { network ->
            val caps = manager.getNetworkCapabilities(network)
            @Suppress("DEPRECATION")
            val sameType = activeType != null && manager.getNetworkInfo(network)?.type == activeType
            (if (sameType) 100 else 0) +
                (if (Build.VERSION.SDK_INT >= 23 && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 20 else 0) +
                (if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) 5 else 0)
        }
    }

    fun hasIPv6(manager: ConnectivityManager, network: Network?): Boolean {
        return hasIPv6(network?.let(manager::getLinkProperties))
    }

    fun hasIPv6(links: LinkProperties?): Boolean {
        links ?: return false
        return links.linkAddresses.any { it.address is Inet6Address && !it.address.isLinkLocalAddress && !it.address.isLoopbackAddress } &&
            links.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address }
    }
}
