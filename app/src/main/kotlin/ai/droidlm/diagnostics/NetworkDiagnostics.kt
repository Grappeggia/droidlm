package ai.droidlm.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

class NetworkDiagnostics(private val context: Context) {
    fun connectivityFields(endpoint: String? = null): Map<String, Any?> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity?.activeNetwork
        val capabilities = activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val linkProperties = activeNetwork?.let { connectivity.getLinkProperties(it) }
        val endpointProxy = endpoint?.let(::proxyForEndpoint).orEmpty()
        return mapOf(
            "activeNetworkPresent" to (activeNetwork != null),
            "transports" to transportNames(capabilities),
            "hasInternetCapability" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true),
            "hasValidatedInternet" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
            "notMetered" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true),
            "vpnActive" to (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true),
            "interfaceName" to linkProperties?.interfaceName,
            "dnsServers" to linkProperties?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            "linkHttpProxy" to linkProxyFields(linkProperties),
            "systemHttpsProxyHost" to System.getProperty("https.proxyHost"),
            "systemHttpsProxyPort" to System.getProperty("https.proxyPort"),
            "endpointProxy" to endpointProxy
        )
    }

    private fun proxyForEndpoint(endpoint: String): Map<String, Any?> = runCatching {
        val uri = URI(endpoint)
        val proxies = ProxySelector.getDefault()?.select(uri).orEmpty()
        mapOf(
            "count" to proxies.size,
            "proxies" to proxies.map { proxy ->
                val address = proxy.address()
                mapOf(
                    "type" to proxy.type().name,
                    "host" to ((address as? InetSocketAddress)?.hostString),
                    "port" to ((address as? InetSocketAddress)?.port)
                )
            },
            "usesProxy" to proxies.any { it.type() != Proxy.Type.DIRECT }
        )
    }.getOrElse { error ->
        mapOf("errorClass" to error::class.java.name, "message" to error.message)
    }

    private fun linkProxyFields(linkProperties: LinkProperties?): Map<String, Any?>? {
        val proxy = linkProperties?.httpProxy ?: return null
        return mapOf(
            "host" to proxy.host,
            "port" to proxy.port,
            "exclusionList" to proxy.exclusionList
        )
    }

    private fun transportNames(capabilities: NetworkCapabilities?): List<String> {
        if (capabilities == null) return emptyList()
        return listOfNotNull(
            transportName(capabilities, NetworkCapabilities.TRANSPORT_WIFI, "WIFI"),
            transportName(capabilities, NetworkCapabilities.TRANSPORT_CELLULAR, "CELLULAR"),
            transportName(capabilities, NetworkCapabilities.TRANSPORT_ETHERNET, "ETHERNET"),
            transportName(capabilities, NetworkCapabilities.TRANSPORT_VPN, "VPN"),
            transportName(capabilities, NetworkCapabilities.TRANSPORT_BLUETOOTH, "BLUETOOTH"),
            transportName(capabilities, NetworkCapabilities.TRANSPORT_LOWPAN, "LOWPAN"),
            transportName(capabilities, NetworkCapabilities.TRANSPORT_WIFI_AWARE, "WIFI_AWARE")
        )
    }

    private fun transportName(capabilities: NetworkCapabilities, transport: Int, name: String): String? =
        if (capabilities.hasTransport(transport)) name else null
}
