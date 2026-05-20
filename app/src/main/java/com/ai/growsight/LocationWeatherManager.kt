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

    // Callback when flow completes
    interface WeatherFlowCallback {
        fun onWeatherReady(weather: WeatherData?)
        fun onSkipped()
    }

    fun startWeatherFlow(
        activity: AppCompatActivity,
        callback: WeatherFlowCallback
    ) {
        val hasPermission = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isLocationOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        when {
            // Location permission not granted — skip silently
            !hasPermission -> callback.onWeatherReady(null)

            // Location service is OFF — show modal
            !isLocationOn -> showLocationOffModal(activity, callback)

            // Location is on — check internet and fetch
            else -> checkInternetAndFetch(activity, callback)
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
        val findingSection = dialogView.findViewById<View>(R.id.findingLocationSection)
        val noInternetSection = dialogView.findViewById<View>(R.id.noInternetSection)
        val btnOpenSettings = dialogView.findViewById<Button>(R.id.btnOpenSettings)
        val btnSkip = dialogView.findViewById<Button>(R.id.btnSkipLocation)
        val btnCancelLocation = dialogView.findViewById<Button>(R.id.btnCancelLocation)
        val locationStatusText = dialogView.findViewById<TextView>(R.id.locationStatusText)

        btnOpenSettings.setOnClickListener {
            // Open location settings
            activity.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))

            // Poll for location being turned on
            activity.lifecycleScope.launch {
                var waited = 0
                while (waited < 30000) { // wait up to 30 seconds for user to turn on
                    delay(1000)
                    waited += 1000

                    val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val nowOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                    if (nowOn) {
                        // Location turned on — switch to finding state
                        locationOffSection.visibility = View.GONE
                        findingSection.visibility = View.VISIBLE
                        checkInternetAndFetch(activity, callback, dialog, locationStatusText, btnCancelLocation)
                        return@launch
                    }
                }
                // User didn't turn on location in time
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
        val hasInternet = isInternetAvailable(activity)

        if (!hasInternet) {
            if (existingDialog != null) {
                // Update existing dialog to show no internet state
                existingDialog.findViewById<View>(R.id.locationOffSection)?.visibility = View.GONE
                existingDialog.findViewById<View>(R.id.findingLocationSection)?.visibility = View.GONE
                existingDialog.findViewById<View>(R.id.noInternetSection)?.visibility = View.VISIBLE
                existingDialog.findViewById<Button>(R.id.btnContinueNoInternet)?.setOnClickListener {
                    existingDialog.dismiss()
                    callback.onWeatherReady(null)
                }
            } else {
                // Show fresh dialog with no internet state
                showNoInternetModal(activity, callback)
            }
            return
        }

        // Has internet — show finding location modal if no existing dialog
        val dialog = existingDialog ?: run {
            val dialogView = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_location_weather, null)

            val d = AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(false)
                .create()

            d.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialogView.findViewById<View>(R.id.locationOffSection).visibility = View.GONE
            dialogView.findViewById<View>(R.id.findingLocationSection).visibility = View.VISIBLE
            d.show()
            d
        }

        val finalStatusText = statusText ?: dialog.findViewById(R.id.locationStatusText)
        val finalCancelBtn = cancelButton ?: dialog.findViewById(R.id.btnCancelLocation)

        var cancelled = false

        finalCancelBtn?.setOnClickListener {
            cancelled = true
            dialog.dismiss()
            callback.onWeatherReady(null)
        }

        // Fetch location + weather with 10 second timeout
        activity.lifecycleScope.launch {
            var secondsWaited = 0
            val updateJob = launch {
                while (true) {
                    delay(1000)
                    secondsWaited++
                    val remaining = 10 - secondsWaited
                    activity.runOnUiThread {
                        finalStatusText?.text = if (remaining > 0)
                            "Searching... ($remaining seconds remaining)"
                        else
                            "Almost there..."
                    }
                }
            }

            val weather = withTimeoutOrNull(10000L) {
                try {
                    val location = LocationHelper.getLastLocation(activity)
                    if (location != null) {
                        activity.runOnUiThread {
                            finalStatusText?.text = "Location found! Fetching weather..."
                        }
                        WeatherService.fetchWeather(location.latitude, location.longitude)
                    } else null
                } catch (e: Exception) {
                    Log.e("LocationWeatherManager", "Failed: ${e.message}")
                    null
                }
            }

            updateJob.cancel()

            if (!cancelled) {
                dialog.dismiss()
                if (weather != null) {
                    callback.onWeatherReady(weather)
                } else {
                    // Timed out or failed
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

        dialogView.findViewById<View>(R.id.locationOffSection).visibility = View.GONE
        dialogView.findViewById<View>(R.id.findingLocationSection).visibility = View.GONE
        dialogView.findViewById<View>(R.id.noInternetSection).visibility = View.VISIBLE

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
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}