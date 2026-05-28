package com.lin.hippyagent.core.linux.tools

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.BatteryManager
import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import timber.log.Timber

/**
 * 设备访问工具：访问 Android 设备功能（电池、传感器、位置等）
 */
class DeviceAccessTool(
    private val context: Context,
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "device_access",
        description = "Access Android device features (battery, sensors, location, etc.)",
        parameters = mapOf(
            "action" to ToolParameter(
                name = "action",
                type = "string",
                description = "Action: battery, sensors, location, device_info, storage",
                required = true
            ),
            "sensor_type" to ToolParameter(
                name = "sensor_type",
                type = "string",
                description = "Sensor type for sensors action (accelerometer, gyroscope, etc.)",
                required = false
            )
        ),
        requiredPermissions = listOf("DEVICE_ACCESS"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val action = getRequiredArgument(arguments, "action")
        val sensorType = getOptionalArgument(arguments, "sensor_type")

        return try {
            when (action) {
                "battery" -> getBatteryInfo(callId)
                "sensors" -> getSensorInfo(sensorType, callId)
                "location" -> getLocationInfo(callId)
                "device_info" -> getDeviceInfo(callId)
                "storage" -> getStorageInfo(callId)
                else -> ToolResult(callId, false, error = "Unknown action: $action. Use battery/sensors/location/device_info/storage.")
            }
        } catch (e: Exception) {
            Timber.e(e, "Device access failed")
            ToolResult(callId, false, error = "Device access failed: ${e.message}")
        }
    }

    /**
     * 获取电池信息
     */
    private fun getBatteryInfo(callId: String): ToolResult {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)

        val statusText = when (batteryStatus) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
            else -> "Unknown"
        }

        val sb = StringBuilder("# Battery Information\n\n")
        sb.appendLine("Level: $batteryLevel%")
        sb.appendLine("Status: $statusText")

        return ToolResult(callId, true, output = sb.toString())
    }

    /**
     * 获取传感器信息
     */
    private fun getSensorInfo(sensorType: String?, callId: String): ToolResult {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        if (sensorType.isNullOrBlank()) {
            // 列出所有可用传感器
            val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            val sb = StringBuilder("# Available Sensors\n\n")
            sensors.forEach { sensor ->
                sb.appendLine("- ${sensor.name} (${getSensorTypeName(sensor.type)})")
            }
            return ToolResult(callId, true, output = sb.toString())
        }

        // 获取特定传感器信息
        val type = getSensorType(sensorType)
        val sensor = sensorManager.getDefaultSensor(type)

        if (sensor == null) {
            return ToolResult(callId, false, error = "Sensor not found: $sensorType")
        }

        val sb = StringBuilder("# Sensor Information: ${sensor.name}\n\n")
        sb.appendLine("Type: ${getSensorTypeName(sensor.type)}")
        sb.appendLine("Vendor: ${sensor.vendor}")
        sb.appendLine("Resolution: ${sensor.resolution}")
        sb.appendLine("Max Range: ${sensor.maximumRange}")
        sb.appendLine("Power: ${sensor.power} mA")

        return ToolResult(callId, true, output = sb.toString())
    }

    /**
     * 获取位置信息
     */
    private fun getLocationInfo(callId: String): ToolResult {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 检查权限
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(callId, false, error = "Location permission not granted")
        }

        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (location == null) {
            return ToolResult(callId, true, output = "No location available. Please enable GPS.")
        }

        val sb = StringBuilder("# Location Information\n\n")
        sb.appendLine("Latitude: ${location.latitude}")
        sb.appendLine("Longitude: ${location.longitude}")
        sb.appendLine("Accuracy: ${location.accuracy}m")
        sb.appendLine("Altitude: ${location.altitude}m")
        sb.appendLine("Speed: ${location.speed} m/s")
        sb.appendLine("Time: ${java.util.Date(location.time)}")

        return ToolResult(callId, true, output = sb.toString())
    }

    /**
     * 获取设备信息
     */
    private fun getDeviceInfo(callId: String): ToolResult {
        val sb = StringBuilder("# Device Information\n\n")
        sb.appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
        sb.appendLine("Model: ${android.os.Build.MODEL}")
        sb.appendLine("Device: ${android.os.Build.DEVICE}")
        sb.appendLine("Android Version: ${android.os.Build.VERSION.RELEASE}")
        sb.appendLine("SDK Level: ${android.os.Build.VERSION.SDK_INT}")
        sb.appendLine("Board: ${android.os.Build.BOARD}")
        sb.appendLine("Hardware: ${android.os.Build.HARDWARE}")

        return ToolResult(callId, true, output = sb.toString())
    }

    /**
     * 获取存储信息
     */
    private fun getStorageInfo(callId: String): ToolResult {
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalSize = totalBlocks * blockSize
        val availableSize = availableBlocks * blockSize
        val usedSize = totalSize - availableSize

        val sb = StringBuilder("# Storage Information\n\n")
        sb.appendLine("Total: ${formatSize(totalSize)}")
        sb.appendLine("Used: ${formatSize(usedSize)}")
        sb.appendLine("Available: ${formatSize(availableSize)}")
        sb.appendLine("Usage: ${(usedSize * 100 / totalSize)}%")

        return ToolResult(callId, true, output = sb.toString())
    }

    /**
     * 格式化大小
     */
    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024
        val mb = kb / 1024
        val gb = mb / 1024
        return when {
            gb > 0 -> "%.2f GB".format(gb.toDouble())
            mb > 0 -> "%.2f MB".format(mb.toDouble())
            kb > 0 -> "%.2f KB".format(kb.toDouble())
            else -> "$bytes B"
        }
    }

    /**
     * 获取传感器类型名称
     */
    private fun getSensorTypeName(type: Int): String {
        return when (type) {
            Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
            Sensor.TYPE_GYROSCOPE -> "Gyroscope"
            Sensor.TYPE_LIGHT -> "Light"
            Sensor.TYPE_PROXIMITY -> "Proximity"
            Sensor.TYPE_PRESSURE -> "Pressure"
            Sensor.TYPE_TEMPERATURE -> "Temperature"
            Sensor.TYPE_MAGNETIC_FIELD -> "Magnetic Field"
            else -> "Unknown ($type)"
        }
    }

    /**
     * 获取传感器类型
     */
    private fun getSensorType(name: String): Int {
        return when (name.lowercase()) {
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "light" -> Sensor.TYPE_LIGHT
            "proximity" -> Sensor.TYPE_PROXIMITY
            "pressure" -> Sensor.TYPE_PRESSURE
            "temperature" -> Sensor.TYPE_TEMPERATURE
            "magnetic_field" -> Sensor.TYPE_MAGNETIC_FIELD
            else -> Sensor.TYPE_ACCELEROMETER
        }
    }
}

