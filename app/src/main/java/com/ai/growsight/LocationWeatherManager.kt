// com/ai/growsight/ai/LocationWeatherManager.kt
package com.ai.growsight.ai

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ai.growsight.R
import kotlinx.coroutines.*
import android.location.LocationManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log

object LocationWeatherManager {

    // ── Time budget ───────────────────────────────────────────────────────────
    // LocationHelper can take up to 15 s for a fresh GPS fix.
    // Add ~5 s for the weather API call.  Total budget: 20 s.
    private const val WEATHER_FETCH_TIMEOUT_MS = 20_000L
    private const val WEATHER_FETCH_TIMEOUT_SEC = (WEATHER_FETCH_TIMEOUT_MS / 1000).toInt()

    interface WeatherFlowCallback {
        fun onWeatherReady(weather: WeatherData?)
        fun onSkipped()
    }

    fun startWeatherFlow(
        activity: AppCompatActivity,
        callback: WeatherFlowCallback
    ) {
        // Accept either FINE or COARSE permission.
        // Without FINE, Android caps GPS accuracy to ~500 m — see manifest note below.
        val hasFinePermission = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarsePermission = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasAnyPermission = hasFinePermission || hasCoarsePermission

        if (!hasFinePermission) {
            // Log a warning so it's visible in dev builds — app still works with coarse,
            // but accuracy will be limited.  Add ACCESS_FINE_LOCATION to AndroidManifest.xml
            // and request it at runtime alongside COARSE to get full GPS accuracy.
            Log.w("LocationWeatherManager",
                "ACCESS_FINE_LOCATION not granted — location accuracy limited to coarse (~500 m). " +
                        "Add ACCESS_FINE_LOCATION to manifest and request it at runtime for best results.")
        }

        val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isLocationOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        when {
            !hasAnyPermission -> callback.onWeatherReady(null)
            !isLocationOn     -> showLocationOffModal(activity, callback)
            else              -> checkInternetAndFetch(activity, callback)
        }
    }

    private fun showLocationOffModal(
        activity: AppCompatActivity,
        callback: WeatherFlowCallback
    ) {
        val dialogView = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_location_weather, null)

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val locationOffSection = dialogView.findViewById<View>(R.id.locationOffSection)
        val findingSection     = dialogView.findViewById<View>(R.id.findingLocationSection)
        val btnOpenSettings    = dialogView.findViewById<Button>(R.id.btnOpenSettings)
        val btnSkip            = dialogView.findViewById<Button>(R.id.btnSkipLocation)
        val btnCancelLocation  = dialogView.findViewById<Button>(R.id.btnCancelLocation)
        val locationStatusText = dialogView.findViewById<TextView>(R.id.locationStatusText)

        btnOpenSettings.setOnClickListener {
            activity.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))

            activity.lifecycleScope.launch {
                var waited = 0
                while (waited < 30_000) {
                    delay(1000)
                    waited += 1000

                    val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val nowOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                    if (nowOn) {
                        locationOffSection.visibility = View.GONE
                        findingSection.visibility     = View.VISIBLE
                        checkInternetAndFetch(
                            activity, callback, dialog, locationStatusText, btnCancelLocation
                        )
                        return@launch
                    }
                }
                // User didn't turn on location within 30 s
                dialog.dismiss()
                callback.onWeatherReady(null)
            }
        }

        btnSkip.setOnClickListener {
            dialog.dismiss()
            callback.onSkipped()
        }

        dialog.show()
    }

    private fun checkInternetAndFetch(
        activity: AppCompatActivity,
        callback: WeatherFlowCallback,
        existingDialog: AlertDialog? = null,
        statusText: TextView? = null,
        cancelButton: Button? = null
    ) {
        if (!isInternetAvailable(activity)) {
            if (existingDialog != null) {
                existingDialog.findViewById<View>(R.id.locationOffSection)?.visibility   = View.GONE
                existingDialog.findViewById<View>(R.id.findingLocationSection)?.visibility = View.GONE
                existingDialog.findViewById<View>(R.id.noInternetSection)?.visibility    = View.VISIBLE
                existingDialog.findViewById<Button>(R.id.btnContinueNoInternet)
                    ?.setOnClickListener {
                        existingDialog.dismiss()
                        callback.onWeatherReady(null)
                    }
            } else {
                showNoInternetModal(activity, callback)
            }
            return
        }

        // Show the "finding location" dialog if we don't already have one
        val dialog = existingDialog ?: run {
            val dialogView = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_location_weather, null)

            val d = AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(false)
                .create()

            d.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialogView.findViewById<View>(R.id.locationOffSection).visibility    = View.GONE
            dialogView.findViewById<View>(R.id.findingLocationSection).visibility = View.VISIBLE
            d.show()
            d
        }

        val finalStatusText = statusText ?: dialog.findViewById(R.id.locationStatusText)
        val finalCancelBtn  = cancelButton ?: dialog.findViewById(R.id.btnCancelLocation)

        var cancelled = false

        finalCancelBtn?.setOnClickListener {
            cancelled = true
            dialog.dismiss()
            callback.onWeatherReady(null)
        }

        activity.lifecycleScope.launch {
            var secondsWaited = 0

            // Countdown ticker — updates every second
            val tickerJob = launch {
                while (true) {
                    delay(1000)
                    secondsWaited++
                    val remaining = WEATHER_FETCH_TIMEOUT_SEC - secondsWaited
                    activity.runOnUiThread {
                        finalStatusText?.text = when {
                            remaining > 10 -> "Finding your location... ($remaining seconds remaining)"
                            remaining > 0  -> "Almost there... ($remaining seconds remaining)"
                            else           -> "Finishing up..."
                        }
                    }
                }
            }

            // ── Main fetch: location + weather, 20-second budget ─────────────
            // LocationHelper will use GPS (up to 15 s), then WeatherService (~1–2 s).
            val weather = withTimeoutOrNull(WEATHER_FETCH_TIMEOUT_MS) {
                try {
                    val location = LocationHelper.getLocation(activity)
                    if (location != null) {
                        Log.d("LocationWeatherManager",
                            "Location acquired: ${location.latitude}, ${location.longitude} " +
                                    "(acc ${location.accuracy}m)")
                        activity.runOnUiThread {
                            finalStatusText?.text = "Location found! Fetching weather data..."
                        }
                        WeatherService.fetchWeather(location.latitude, location.longitude)
                    } else {
                        Log.w("LocationWeatherManager", "Location returned null — skipping weather fetch")
                        null
                    }
                } catch (e: Exception) {
                    Log.e("LocationWeatherManager", "Fetch failed: ${e.message}", e)
                    null
                }
            }

            tickerJob.cancel()

            if (!cancelled) {
                dialog.dismiss()
                if (weather != null) {
                    callback.onWeatherReady(weather)
                } else {
                    showWeatherFailedToast(activity)
                    callback.onWeatherReady(null)
                }
            }
        }
    }

    private fun showNoInternetModal(
        activity: AppCompatActivity,
        callback: WeatherFlowCallback
    ) {
        val dialogView = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_location_weather, null)

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.locationOffSection).visibility    = View.GONE
        dialogView.findViewById<View>(R.id.findingLocationSection).visibility = View.GONE
        dialogView.findViewById<View>(R.id.noInternetSection).visibility     = View.VISIBLE

        dialogView.findViewById<Button>(R.id.btnContinueNoInternet).setOnClickListener {
            dialog.dismiss()
            callback.onWeatherReady(null)
        }

        dialog.show()
    }

    private fun showWeatherFailedToast(activity: AppCompatActivity) {
        activity.runOnUiThread {
            android.widget.Toast.makeText(
                activity,
                "⚠️ Could not get weather data — using general recommendations",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isInternetAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps    = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}