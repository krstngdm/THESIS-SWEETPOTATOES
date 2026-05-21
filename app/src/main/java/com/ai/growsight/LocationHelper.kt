// com/ai/growsight/ai/LocationHelper.kt
package com.ai.growsight.ai

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object LocationHelper {

    // ── Tuning constants ──────────────────────────────────────────────────────
    private const val MAX_LOCATION_AGE_MS       = 10 * 60 * 1000L  // 10 min — reject older cached fixes
    private const val MAX_ACCEPTABLE_ACCURACY_M = 200f              // reject fixes coarser than 200 m
    private const val FRESH_FIX_TIMEOUT_MS      = 15_000L           // 15 s — time budget for GPS lock
    private const val FRESH_FIX_INTERVAL_MS     = 1_000L            // ask for updates every 1 s

    /**
     * Returns the best available location.
     *
     * Strategy:
     *   1. Try the Fused Provider's last-known fix.
     *      Accept it only if it is recent (< 10 min) AND accurate (< 200 m).
     *   2. Otherwise request a fresh HIGH_ACCURACY fix and wait up to 15 seconds.
     *      HIGH_ACCURACY drives the GPS hardware, giving 5–15 m accuracy outdoors.
     *
     * Caller must hold ACCESS_FINE_LOCATION (or at minimum ACCESS_COARSE_LOCATION).
     * Without ACCESS_FINE_LOCATION the OS will cap accuracy to coarse (~500 m).
     */
    @SuppressLint("MissingPermission")
    suspend fun getLocation(context: Context): Location? {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        // ── Step 1: check the cached last-known fix ───────────────────────────
        val cached = getLastKnownLocation(fusedClient)
        if (cached != null && isFreshAndAccurate(cached)) {
            Log.d("LocationHelper",
                "Using cached fix: ${cached.latitude}, ${cached.longitude} " +
                        "(age ${(System.currentTimeMillis() - cached.time) / 1000}s, " +
                        "acc ${cached.accuracy}m)")
            return cached
        }

        if (cached != null) {
            Log.d("LocationHelper",
                "Cached fix rejected — age ${(System.currentTimeMillis() - cached.time) / 1000}s, " +
                        "acc ${cached.accuracy}m — requesting fresh GPS fix")
        } else {
            Log.d("LocationHelper", "No cached fix — requesting fresh GPS fix")
        }

        // ── Step 2: request a fresh GPS fix, wait up to 15 seconds ───────────
        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val request = LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    FRESH_FIX_INTERVAL_MS
                )
                    .setMaxUpdates(1)
                    .setMinUpdateDistanceMeters(0f)
                    .build()

                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        fusedClient.removeLocationUpdates(this)
                        val loc = result.lastLocation
                        Log.d("LocationHelper",
                            "Fresh GPS fix: ${loc?.latitude}, ${loc?.longitude} " +
                                    "(acc ${loc?.accuracy}m)")
                        if (cont.isActive) cont.resume(loc)
                    }

                    override fun onLocationAvailability(availability: LocationAvailability) {
                        if (!availability.isLocationAvailable) {
                            Log.w("LocationHelper", "GPS hardware reports location unavailable")
                        }
                    }
                }

                fusedClient.requestLocationUpdates(
                    request, callback, Looper.getMainLooper()
                )

                cont.invokeOnCancellation {
                    fusedClient.removeLocationUpdates(callback)
                }
            }
        }.also { loc ->
            if (loc == null) {
                Log.w("LocationHelper",
                    "Fresh GPS fix timed out after ${FRESH_FIX_TIMEOUT_MS / 1000}s")
            }
        }
    }

    // ── Backward-compatible alias so no call sites need changing ──────────────
    suspend fun getLastLocation(context: Context): Location? = getLocation(context)

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Suspends until the Fused Provider returns its cached last-known fix (or null). */
    private suspend fun getLastKnownLocation(
        fusedClient: FusedLocationProviderClient
    ): Location? = suspendCancellableCoroutine { cont ->
        fusedClient.lastLocation
            .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    /**
     * Returns true if the fix is younger than MAX_LOCATION_AGE_MS
     * AND has a reported accuracy better than MAX_ACCEPTABLE_ACCURACY_M.
     */
    private fun isFreshAndAccurate(location: Location): Boolean {
        val ageMs = System.currentTimeMillis() - location.time
        return ageMs < MAX_LOCATION_AGE_MS && location.accuracy <= MAX_ACCEPTABLE_ACCURACY_M
    }
}