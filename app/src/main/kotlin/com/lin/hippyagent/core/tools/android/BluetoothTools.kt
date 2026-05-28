package com.lin.hippyagent.core.tools.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

/**
 * 蓝牙控制工具
 * 
 * 支持开启/关闭蓝牙,以及查询蓝牙状态
 * 注意: Android 12+ 开启蓝牙需要 BLUETOOTH_CONNECT 权限,且无法通过代码直接开启,需要发送Intent
 * 需要权限: BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT(Android 12+)
 */
class BluetoothControlTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "bluetooth_control",
        description = "蓝牙开关控制(开启/关闭/查询状态)",
        parameters = mapOf(
            "action" to ToolParameter(
                name = "action",
                type = "string",
                description = "操作: enable(开启)/disable(关闭)/status(查询状态)",
                required = true
            )
        ),
        requiredPermissions = listOf("BLUETOOTH", "BLUETOOTH_ADMIN", "BLUETOOTH_CONNECT"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val action = getRequiredArgument(arguments, "action")

        val bluetoothManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        } else {
            @Suppress("DEPRECATION")
            null
        }

        val adapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothManager?.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }

        if (adapter == null) {
            return ToolResult(callId, false, error = "Bluetooth not available on this device")
        }

        return when (action.lowercase()) {
            "enable" -> {
                if (adapter.isEnabled) {
                    ToolResult(callId, true, output = "Bluetooth is already enabled")
                } else {
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolResult(callId, true, output = "Bluetooth enable request sent. User needs to confirm.")
                }
            }
            "disable" -> {
                if (!adapter.isEnabled) {
                    ToolResult(callId, true, output = "Bluetooth is already disabled")
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ToolResult(callId, false, error = "Android 13+ does not allow programmatic Bluetooth disable. Please disable manually.")
                    } else {
                        @Suppress("DEPRECATION")
                        val disabled = adapter.disable()
                        if (disabled) {
                            ToolResult(callId, true, output = "Bluetooth disabled")
                        } else {
                            ToolResult(callId, false, error = "Failed to disable Bluetooth")
                        }
                    }
                }
            }
            "status" -> {
                val status = if (adapter.isEnabled) "enabled" else "disabled"
                val name = adapter.name ?: "Unknown"
                val address = adapter.address ?: "Unknown"
                val state = when (adapter.state) {
                    BluetoothAdapter.STATE_OFF -> "OFF"
                    BluetoothAdapter.STATE_ON -> "ON"
                    BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
                    BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
                    else -> "UNKNOWN"
                }

                val output = buildString {
                    appendLine("Bluetooth Status:")
                    appendLine("========================")
                    appendLine("State: $state")
                    appendLine("Enabled: $status")
                    appendLine("Name: $name")
                    appendLine("Address: $address")
                    appendLine("Discoverable: ${if (adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) "Yes" else "No"}")
                }

                ToolResult(callId, true, output.trimEnd())
            }
            else -> {
                ToolResult(callId, false, error = "Invalid action: $action. Use 'enable', 'disable', or 'status'")
            }
        }
    }
}

/**
 * 获取已配对蓝牙设备工具
 * 
 * 返回所有已配对的蓝牙设备列表,包括设备名称、地址、类型、连接状态等
 * 需要权限: BLUETOOTH, BLUETOOTH_CONNECT(Android 12+)
 */
class GetPairedDevicesTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "get_paired_devices",
        description = "获取已配对的蓝牙设备列表",
        parameters = emptyMap(),
        requiredPermissions = listOf("BLUETOOTH", "BLUETOOTH_CONNECT"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""

        return try {
            val bluetoothManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            } else {
                @Suppress("DEPRECATION")
                null
            }

            val adapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bluetoothManager?.adapter
            } else {
                @Suppress("DEPRECATION")
                BluetoothAdapter.getDefaultAdapter()
            }

            if (adapter == null) {
                return ToolResult(callId, false, error = "Bluetooth not available on this device")
            }

            if (!adapter.isEnabled) {
                return ToolResult(callId, false, error = "Bluetooth is disabled. Please enable Bluetooth first.")
            }

            val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices

            if (pairedDevices.isEmpty()) {
                return ToolResult(callId, true, output = "No paired devices found")
            }

            val output = buildString {
                appendLine("Paired Bluetooth Devices (${pairedDevices.size}):")
                appendLine("========================")

                pairedDevices.forEachIndexed { index, device ->
                    appendLine("Device ${index + 1}:")
                    appendLine("  Name: ${device.name ?: "Unknown"}")
                    appendLine("  Address: ${device.address}")
                    appendLine("  Bond State: ${getBondStateString(device.bondState)}")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        appendLine("  Type: ${getDeviceTypeString(device.type)}")
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val connected = device.javaClass.getMethod("isConnected").invoke(device) as? Boolean ?: false
                            appendLine("  Connected: ${if (connected) "Yes" else "No"}")
                        } catch (_: Exception) {
                            appendLine("  Connected: Unknown")
                        }
                    }

                    val deviceClass = device.bluetoothClass
                    if (deviceClass != null) {
                        appendLine("  Major Class: ${getMajorClassString(deviceClass.majorDeviceClass)}")
                        appendLine("  Service: ${getServicesString(deviceClass.deviceClass)}")
                    }

                    appendLine()
                }
            }

            ToolResult(callId, true, output.trimEnd())
        } catch (e: SecurityException) {
            ToolResult(callId, false, error = "Permission denied: ${e.message}")
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to get paired devices: ${e.message}")
        }
    }

    private fun getBondStateString(bondState: Int): String = when (bondState) {
        BluetoothDevice.BOND_BONDED -> "Paired"
        BluetoothDevice.BOND_BONDING -> "Pairing"
        BluetoothDevice.BOND_NONE -> "Not Paired"
        else -> "Unknown"
    }

    private fun getDeviceTypeString(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "Low Energy"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual Mode"
        BluetoothDevice.DEVICE_TYPE_UNKNOWN -> "Unknown"
        else -> "Unknown"
    }

    private fun getMajorClassString(majorClass: Int): String = when (majorClass) {
        BluetoothClass.Device.Major.COMPUTER -> "Computer"
        BluetoothClass.Device.Major.PHONE -> "Phone"
        BluetoothClass.Device.Major.NETWORKING -> "Networking"
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio/Video"
        BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
        BluetoothClass.Device.Major.IMAGING -> "Imaging"
        BluetoothClass.Device.Major.WEARABLE -> "Wearable"
        BluetoothClass.Device.Major.TOY -> "Toy"
        BluetoothClass.Device.Major.HEALTH -> "Health"
        BluetoothClass.Device.Major.UNCATEGORIZED -> "Uncategorized"
        else -> "Unknown"
    }

    private fun getServicesString(deviceClass: Int): String {
        val services = mutableListOf<String>()
        if (deviceClass and android.bluetooth.BluetoothClass.Service.LIMITED_DISCOVERABILITY != 0) {
            services.add("Limited Discoverability")
        }
        if (deviceClass and android.bluetooth.BluetoothClass.Service.POSITIONING != 0) {
            services.add("Positioning")
        }
        if (deviceClass and android.bluetooth.BluetoothClass.Service.NETWORKING != 0) {
            services.add("Networking")
        }
        if (deviceClass and android.bluetooth.BluetoothClass.Service.RENDER != 0) {
            services.add("Render")
        }
        if (deviceClass and android.bluetooth.BluetoothClass.Service.CAPTURE != 0) {
            services.add("Capture")
        }
        if (deviceClass and android.bluetooth.BluetoothClass.Service.OBJECT_TRANSFER != 0) {
            services.add("Object Transfer")
        }
        if (deviceClass and android.bluetooth.BluetoothClass.Service.AUDIO != 0) {
            services.add("Audio")
        }
        if (deviceClass and android.bluetooth.BluetoothClass.Service.TELEPHONY != 0) {
            services.add("Telephony")
        }
        if (deviceClass and android.bluetooth.BluetoothClass.Service.INFORMATION != 0) {
            services.add("Information")
        }
        return services.joinToString(", ").ifEmpty { "None" }
    }
}

