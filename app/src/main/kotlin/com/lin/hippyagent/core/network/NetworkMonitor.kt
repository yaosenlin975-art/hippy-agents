package com.lin.hippyagent.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import timber.log.Timber

/**
 * 网络状态
 */
data class NetworkState(
    val isConnected: Boolean = false,
    val isMetered: Boolean = true,
    val bandwidth: NetworkBandwidth = NetworkBandwidth.UNKNOWN
)

/**
 * 网络带宽等级
 */
enum class NetworkBandwidth {
    UNKNOWN,
    LOW,        // 2G/3G
    MEDIUM,     // 4G/WiFi 限速
    HIGH        // 5G/WiFi 不限速
}

/**
 * 网络状态监听器
 * 
 * 使用 ConnectivityManager.NetworkCallback 实时监听网络变化
 * 提供网络连通性、计费状态、带宽等级等信息
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * 当前网络状态（同步获取）
     */
    fun getCurrentState(): NetworkState {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkState()
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkState()

        return NetworkState(
            isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).not(),
            bandwidth = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                        NetworkBandwidth.HIGH
                    } else {
                        NetworkBandwidth.MEDIUM
                    }
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val downSpeed = capabilities.linkDownstreamBandwidthKbps
                    when {
                        downSpeed > 100_000 -> NetworkBandwidth.HIGH      // 5G
                        downSpeed > 10_000 -> NetworkBandwidth.MEDIUM     // 4G
                        else -> NetworkBandwidth.LOW                      // 2G/3G
                    }
                }
                else -> NetworkBandwidth.UNKNOWN
            }
        )
    }

    /**
     * 网络状态变化 Flow
     */
    fun observeNetworkState(): Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentState())
                Timber.d("Network available: ${getCurrentState()}")
            }

            override fun onLost(network: Network) {
                trySend(NetworkState())
                Timber.d("Network lost")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(getCurrentState())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // 发送初始状态
        trySend(getCurrentState())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    /**
     * 是否在线（便捷方法）
     */
    fun isOnline(): Boolean = getCurrentState().isConnected

    /**
     * 是否使用 WiFi（不限速网络）
     */
    fun isOnWifi(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

