package com.karan.speedbond.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Holds Wi-Fi and cellular [Network] handles simultaneously and hands out one
 * OkHttpClient per network whose sockets are pinned to that network.
 *
 * This is the Android equivalent of Plexo's SO_BINDTODEVICE trick: no root,
 * no VPN — just public ConnectivityManager APIs. Requesting the cellular
 * network explicitly keeps mobile data up even while Wi-Fi is connected
 * (normally Android would put it to sleep).
 */
data class BondedNetwork(
    val id: String, // "wifi" | "cellular"
    val displayName: String, // "Wi-Fi" | "Mobile data"
    val network: Network,
    val client: OkHttpClient,
)

class NetworkBondManager(private val context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _networks = MutableStateFlow<List<BondedNetwork>>(emptyList())
    val networks: StateFlow<List<BondedNetwork>> = _networks

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = addNetwork("wifi", "Wi-Fi", network)
        override fun onLost(network: Network) = removeNetwork("wifi", network)
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = addNetwork("cellular", "Mobile data", network)
        override fun onLost(network: Network) = removeNetwork("cellular", network)
    }

    private fun clientFor(network: Network): OkHttpClient =
        OkHttpClient.Builder()
            .socketFactory(network.socketFactory) // pins every socket to this network
            .addInterceptor { chain ->
                // Identify as Chrome: many file hosts 403 bot user-agents outright.
                // identity encoding: never let a range response come back gzipped,
                // or the byte offsets stop meaning what we asked for.
                val req = chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
                    )
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .build()
                chain.proceed(req)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private fun addNetwork(id: String, displayName: String, network: Network) {
        val bonded = BondedNetwork(id, displayName, network, clientFor(network))
        _networks.value = _networks.value.filterNot { it.id == id } + bonded
    }

    private fun removeNetwork(id: String, network: Network) {
        _networks.value = _networks.value.filterNot { it.id == id && it.network == network }
    }

    /** Start holding both networks. Call [unbind] when done. */
    fun bind() {
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cellRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.requestNetwork(wifiRequest, wifiCallback)
        cm.requestNetwork(cellRequest, cellCallback)
    }

    fun unbind() {
        try {
            cm.unregisterNetworkCallback(wifiCallback)
        } catch (_: Exception) {
        }
        try {
            cm.unregisterNetworkCallback(cellCallback)
        } catch (_: Exception) {
        }
        _networks.value = emptyList()
    }
}
