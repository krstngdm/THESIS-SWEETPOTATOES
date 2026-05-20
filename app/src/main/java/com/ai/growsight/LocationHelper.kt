// com/ai/growsight/ai/LocationHelper.kt
package com.ai.growsight.ai

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocationHelper {

    @SuppressLint("MissingPermission")
    suspend fun getLastLocation(context: Context): Location? {
        return kotlinx.coroutines.withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { cont ->
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)

                fusedClient.lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            Log.d("LocationHelper", "Got last location: ${location.latitude}, ${location.longitude}")
                            cont.resume(location)
                        } else {
                            Log.d("LocationHelper", "Last location null, requesting fresh...")
                            requestFreshLocation(context, fusedClient, cont)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("LocationHelper", "Failed to get location: ${e.message}")
                        cont.resume(null)
                    }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(
        context: Context,
        fusedClient: com.google.android.gms.location.FusedLocationProviderClient,
        cont: kotlinx.coroutines.CancellableContinuation<Location?>
    ) {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000L)
            .setMaxUpdates(1)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                fusedClient.removeLocationUpdates(this)
                val location = result.lastLocation
                Log.d("LocationHelper", "Fresh location: ${location?.latitude}, ${location?.longitude}")
                cont.resume(location)
            }
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        cont.invokeOnCancellation {
            fusedClient.removeLocationUpdates(callback)
        }
    }
}