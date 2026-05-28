package com.lin.hippyagent.core.tools.android

import android.content.Context
import android.net.wifi.WifiManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

/**
 * 获取WiFi信息工具
 * 
 * 返回当前连接的WiFi名称(SSID)、信号强度、频率、连接状态等信息
 * 需要权限: ACCESS_WIFI_STATE, ACCESS_FINE_LOCATION(Android 10+)
 */
class GetWifiInfoTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "get_wifi_info",
        description = "获取WiFi信息(名称、信号强度、频率等)",
        parameters = emptyMap(),
        requiredPermissions = listOf("ACCESS_WIFI_STATE", "ACCESS_FINE_LOCATION"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return ToolResult(callId, false, error = "WifiManager not available")

            if (!wifiManager.isWifiEnabled) {
                return ToolResult(callId, true, output = "WiFi is currently disabled")
            }

            val connectionInfo = wifiManager.connectionInfo
            if (connectionInfo == null || connectionInfo.ssid == "<unknown ssid>") {
                return ToolResult(callId, true, output = "Not connected to any WiFi network")
            }

            val ssid = connectionInfo.ssid.replace("\"", "")
            val bssid = connectionInfo.bssid
            val rssi = connectionInfo.rssi
            val frequency = connectionInfo.frequency
            val linkSpeed = connectionInfo.linkSpeed
            val ipAddress = intToIp(connectionInfo.ipAddress)

            val signalQuality = when {
                rssi >= -50 -> "Excellent"
                rssi >= -60 -> "Good"
                rssi >= -70 -> "Fair"
                else -> "Weak"
            }

            val frequencyBand = when {
                frequency >= 5000 -> "5 GHz"
                frequency >= 2400 -> "2.4 GHz"
                else -> "Unknown"
            }

            val output = buildString {
                appendLine("WiFi Information:")
                appendLine("========================")
                appendLine("SSID: $ssid")
                appendLine("BSSID: $bssid")
                appendLine("Signal Strength: $rssi dBm ($signalQuality)")
                appendLine("Frequency: $frequency MHz ($frequencyBand)")
                appendLine("Link Speed: $linkSpeed Mbps")
                appendLine("IP Address: $ipAddress")
                appendLine("Network ID: ${connectionInfo.networkId}")
                appendLine("Hidden Network: ${connectionInfo.hiddenSSID}")
            }

            ToolResult(callId, true, output.trimEnd())
        } catch (e: SecurityException) {
            ToolResult(callId, false, error = "Permission denied: ${e.message}")
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to get WiFi info: ${e.message}")
        }
    }

    private fun intToIp(ipAddress: Int): String {
        return if (ipAddress == 0) {
            "Not available"
        } else {
            "${ipAddress and 0xFF}.${ipAddress shr 8 and 0xFF}.${ipAddress shr 16 and 0xFF}.${ipAddress shr 24 and 0xFF}"
        }
    }
}

