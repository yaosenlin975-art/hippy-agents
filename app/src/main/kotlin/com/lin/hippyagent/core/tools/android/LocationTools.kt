package com.lin.hippyagent.core.tools.android

import android.content.Context
import android.location.Geocoder
import android.os.Looper
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

class GetCurrentLocationTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "get_current_location",
        description = "获取当前位置",
        parameters = emptyMap(),
        requiredPermissions = listOf("ACCESS_FINE_LOCATION"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        return try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val result = withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine { cont ->
                    val callback = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            val location = result.lastLocation
                            if (location != null) {
                                val info = buildString {
                                    appendLine("Latitude: ${location.latitude}")
                                    appendLine("Longitude: ${location.longitude}")
                                    appendLine("Accuracy: ${location.accuracy}m")
                                    if (location.hasAltitude()) appendLine("Altitude: ${location.altitude}m")
                                }
                                cont.resume(ToolResult(callId, true, info.trimEnd()))
                            } else {
                                cont.resume(ToolResult(callId, false, error = "Location unavailable: no GPS fix"))
                            }
                            fusedClient.removeLocationUpdates(this)
                        }
                    }
                    fusedClient.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            val info = "Latitude: ${location.latitude}\nLongitude: ${location.longitude}\nAccuracy: ${location.accuracy}m"
                            cont.resume(ToolResult(callId, true, info))
                        } else {
                            fusedClient.requestLocationUpdates(
                                LocationRequest.create().apply {
                                    priority = com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
                                    numUpdates = 1
                                },
                                callback,
                                Looper.getMainLooper()
                            )
                        }
                    }.addOnFailureListener { e ->
                        cont.resume(ToolResult(callId, false, error = "Location error: ${e.message}"))
                    }
                    cont.invokeOnCancellation { fusedClient.removeLocationUpdates(callback) }
                }
            }
            result ?: ToolResult(callId, false, error = "Location request timed out")
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to get location: ${e.message}")
        }
    }
}

