package com.lin.hippyagent.core.tools.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

class ReadSensorTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "read_sensor",
        description = "读取传感器数据",
        parameters = mapOf(
            "sensor_type" to ToolParameter(
                name = "sensor_type",
                type = "string",
                description = "传感器类型: accelerometer/gyroscope/light/pressure/temperature/all",
                required = false,
                defaultValue = "all"
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val sensorType = getOptionalArgument(arguments, "sensor_type", "all")!!
        val callId = arguments["callId"] as? String ?: ""

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val sensors = listOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_GYROSCOPE to "gyroscope",
            Sensor.TYPE_LIGHT to "light",
            Sensor.TYPE_PRESSURE to "pressure",
            Sensor.TYPE_TEMPERATURE to "temperature",
            Sensor.TYPE_PROXIMITY to "proximity",
            Sensor.TYPE_GRAVITY to "gravity",
            Sensor.TYPE_LINEAR_ACCELERATION to "linear_acceleration",
            Sensor.TYPE_ROTATION_VECTOR to "rotation_vector",
            Sensor.TYPE_MAGNETIC_FIELD to "magnetic_field",
            Sensor.TYPE_AMBIENT_TEMPERATURE to "ambient_temperature",
            Sensor.TYPE_RELATIVE_HUMIDITY to "humidity",
            Sensor.TYPE_HEART_RATE to "heart_rate",
            Sensor.TYPE_STEP_COUNTER to "step_counter",
            Sensor.TYPE_SIGNIFICANT_MOTION to "significant_motion"
        )

        val result = buildString {
            appendLine("Available Sensors:")
            appendLine("==================")

            val filteredSensors = if (sensorType == "all") {
                sensors
            } else {
                sensors.filter { it.second.equals(sensorType, ignoreCase = true) }
            }

            filteredSensors.forEach { (type, name) ->
                val sensor = sensorManager.getDefaultSensor(type)
                if (sensor != null) {
                    appendLine("$name: ${sensor.name} (vendor: ${sensor.vendor}, version: ${sensor.version}, power: ${sensor.power}mA, resolution: ${sensor.resolution})")
                } else {
                    if (sensorType != "all") {
                        appendLine("$name: Not available on this device")
                    }
                }
            }

            if (sensorType != "all" && filteredSensors.isEmpty()) {
                appendLine("Unknown sensor type: $sensorType")
            }
        }

        return ToolResult(callId, true, result.trimEnd().ifEmpty { "No sensors found" })
    }
}

