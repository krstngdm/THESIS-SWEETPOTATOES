package com.ai.growsight

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.ai.growsight.ai.ModelManager
import com.ai.growsight.data.AppDatabase
import com.ai.growsight.data.ConversationEntity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.Manifest

class PlantationProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILING_DONE = "extra_profiling_done"
        private const val LOCATION_PERMISSION_CODE = 8001

        fun todayMidnightMs(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private lateinit var db: AppDatabase
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var selectedDateMs: Long = todayMidnightMs()

    private var confirmedLat: Double? = null
    private var confirmedLon: Double? = null
    private var confirmedLabel: String? = null

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var backButton: ImageButton
    private lateinit var plantationNameInput: EditText
    private lateinit var plantingDatePicker: DatePicker
    private lateinit var weekBadge: TextView
    private lateinit var createButton: MaterialButton
    private lateinit var detectLocationButton: MaterialButton
    private lateinit var locationStatusRow: LinearLayout
    private lateinit var locationLabelText: TextView
    private lateinit var latLonText: TextView
    private lateinit var confirmLocationRow: LinearLayout
    private lateinit var confirmLocationButton: MaterialButton
    private lateinit var changeLocationButton: MaterialButton
    private lateinit var manualLocationContainer: LinearLayout
    private lateinit var locationSearchInput: EditText
    private lateinit var searchLocationButton: MaterialButton
    private lateinit var locationResultsList: ListView
    private lateinit var confirmedLocationChip: LinearLayout
    private lateinit var confirmedLocationText: TextView
    private lateinit var editLocationLink: TextView
    private lateinit var manualSearchButton: MaterialButton
    private lateinit var openMapDirectlyLink: TextView

    // ── Map picker launcher ───────────────────────────────────────────────────
    // FIX (Issue 2): We no longer use RESULT_LABEL from MapPickerActivity.
    // Instead we re-geocode with our own reverseGeocode() so the label always
    // matches the pin the user actually placed — not the stale GPS label that
    // MapPickerActivity carried from the initial location detection.
    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val lat = result.data?.getDoubleExtra(MapPickerActivity.RESULT_LAT, 0.0) ?: 0.0
            val lon = result.data?.getDoubleExtra(MapPickerActivity.RESULT_LON, 0.0) ?: 0.0
            val returnedLabel = result.data?.getStringExtra(MapPickerActivity.RESULT_LABEL)?.trim() ?: ""
            lifecycleScope.launch {
                // Use what the map returned — this already contains either the user's
                // typed text or the live-geocoded name for the pinned position.
                // Only fall back to our own geocode call if the map returned nothing.
                val label = if (returnedLabel.isNotBlank()) returnedLabel
                else reverseGeocode(lat, lon)
                applyConfirmedLocation(lat, lon, label)
            }
        }
        // Back pressed — stay on current state, do nothing
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plantation_profile)

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "prompts-db")
            .addMigrations(AppDatabase.MIGRATION_6_7)
            .build()

        bindViews()
        setupDatePicker()
        setupLocationCard()

        backButton.setOnClickListener { finish() }
        createButton.setOnClickListener { onCreateTapped() }
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        backButton              = findViewById(R.id.backButton)
        plantationNameInput     = findViewById(R.id.plantationNameInput)
        plantingDatePicker      = findViewById(R.id.plantingDatePicker)
        weekBadge               = findViewById(R.id.weekBadge)
        createButton            = findViewById(R.id.createButton)
        detectLocationButton    = findViewById(R.id.detectLocationButton)
        locationStatusRow       = findViewById(R.id.locationStatusRow)
        locationLabelText       = findViewById(R.id.locationLabelText)
        latLonText              = findViewById(R.id.latLonText)
        confirmLocationRow      = findViewById(R.id.confirmLocationRow)
        confirmLocationButton   = findViewById(R.id.confirmLocationButton)
        changeLocationButton    = findViewById(R.id.changeLocationButton)
        manualLocationContainer = findViewById(R.id.manualLocationContainer)
        locationSearchInput     = findViewById(R.id.locationSearchInput)
        searchLocationButton    = findViewById(R.id.searchLocationButton)
        locationResultsList     = findViewById(R.id.locationResultsList)
        confirmedLocationChip   = findViewById(R.id.confirmedLocationChip)
        confirmedLocationText   = findViewById(R.id.confirmedLocationText)
        editLocationLink        = findViewById(R.id.editLocationLink)
        manualSearchButton      = findViewById(R.id.manualSearchButton)
        openMapDirectlyLink     = findViewById(R.id.openMapDirectlyLink)
    }

    // ── Date picker ───────────────────────────────────────────────────────────

    private fun setupDatePicker() {
        val today = Calendar.getInstance()
        plantingDatePicker.maxDate = today.timeInMillis
        val earliest = Calendar.getInstance().apply { add(Calendar.WEEK_OF_YEAR, -16) }
        plantingDatePicker.minDate = earliest.timeInMillis

        updateWeekBadge(selectedDateMs)

        plantingDatePicker.init(
            today.get(Calendar.YEAR),
            today.get(Calendar.MONTH),
            today.get(Calendar.DAY_OF_MONTH)
        ) { _, year, month, day ->
            selectedDateMs = Calendar.getInstance().apply {
                set(year, month, day, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            updateWeekBadge(selectedDateMs)
        }
    }

    private fun updateWeekBadge(dateMs: Long) {
        val weeks = TimeUnit.MILLISECONDS
            .toDays(System.currentTimeMillis() - dateMs)
            .toInt().div(7).coerceAtLeast(0)
        weekBadge.text = when {
            weeks == 0  -> "Planted today — Week 1 starts now"
            weeks == 1  -> "Week 1 · planted last week"
            weeks >= 16 -> "Week 16 · at or past the harvest window — little time remaining"
            else        -> "Week $weeks from today"
        }
    }

    // ── Location card setup ───────────────────────────────────────────────────

    private fun setupLocationCard() {

        detectLocationButton.setOnClickListener {
            requestLocationDetection()
        }

        confirmLocationButton.setOnClickListener {
            applyConfirmedLocation(confirmedLat!!, confirmedLon!!, confirmedLabel ?: "")
        }

        changeLocationButton.setOnClickListener {
            if (confirmedLat != null && confirmedLon != null) {
                // Has GPS result → open map for precise pinning
                mapPickerLauncher.launch(
                    Intent(this, MapPickerActivity::class.java).apply {
                        putExtra(MapPickerActivity.EXTRA_LAT,   confirmedLat!!)
                        putExtra(MapPickerActivity.EXTRA_LON,   confirmedLon!!)
                        putExtra(MapPickerActivity.EXTRA_LABEL, confirmedLabel ?: "")
                    }
                )
            } else {
                // No GPS result → fall back to manual search
                confirmLocationRow.visibility      = View.GONE
                locationStatusRow.visibility       = View.GONE
                manualLocationContainer.visibility = View.VISIBLE
            }
        }

        searchLocationButton.setOnClickListener {
            manualLocationContainer.visibility = View.VISIBLE
            locationSearchInput.requestFocus()
        }

        manualSearchButton.setOnClickListener {
            val query = locationSearchInput.text.toString().trim()
            if (query.isNotEmpty()) searchLocation(query)
            else Toast.makeText(this, "Enter a location to search", Toast.LENGTH_SHORT).show()
        }

        openMapDirectlyLink.setOnClickListener {
            // Open map centered on Philippines as default if no coords yet
            val lat = confirmedLat ?: 12.8797
            val lon = confirmedLon ?: 121.7740
            mapPickerLauncher.launch(
                Intent(this, MapPickerActivity::class.java).apply {
                    putExtra(MapPickerActivity.EXTRA_LAT,   lat)
                    putExtra(MapPickerActivity.EXTRA_LON,   lon)
                    putExtra(MapPickerActivity.EXTRA_LABEL, confirmedLabel ?: "")
                }
            )
        }

        editLocationLink.setOnClickListener {
            confirmedLat   = null
            confirmedLon   = null
            confirmedLabel = null
            confirmedLocationChip.visibility   = View.GONE
            locationStatusRow.visibility       = View.GONE
            confirmLocationRow.visibility      = View.GONE
            manualLocationContainer.visibility = View.GONE
        }
    }

    // ── GPS detection ─────────────────────────────────────────────────────────

    private fun requestLocationDetection() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_CODE
            )
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val isLocationEnabled =
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        if (!isLocationEnabled) {
            showLocationDialog(startInFindingState = false)
            return
        }

        showLocationDialog(startInFindingState = true)
    }

    private fun showLocationDialog(startInFindingState: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_location_weather, null)

        val locationOffSection     = dialogView.findViewById<LinearLayout>(R.id.locationOffSection)
        val findingLocationSection = dialogView.findViewById<LinearLayout>(R.id.findingLocationSection)
        val noInternetSection      = dialogView.findViewById<LinearLayout>(R.id.noInternetSection)
        val btnOpenSettings        = dialogView.findViewById<MaterialButton>(R.id.btnOpenSettings)
        val btnSkipLocation        = dialogView.findViewById<Button>(R.id.btnSkipLocation)
        val btnCancelLocation      = dialogView.findViewById<Button>(R.id.btnCancelLocation)
        val btnContinueNoInternet  = dialogView.findViewById<MaterialButton>(R.id.btnContinueNoInternet)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun showSection(section: String) {
            locationOffSection.visibility     = if (section == "off")      View.VISIBLE else View.GONE
            findingLocationSection.visibility = if (section == "finding")  View.VISIBLE else View.GONE
            noInternetSection.visibility      = if (section == "internet") View.VISIBLE else View.GONE
        }

        btnOpenSettings.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }

        btnSkipLocation.setOnClickListener {
            dialog.dismiss()
            manualLocationContainer.visibility = View.VISIBLE
        }

        btnCancelLocation.setOnClickListener {
            dialog.dismiss()
            manualLocationContainer.visibility = View.VISIBLE
        }

        btnContinueNoInternet.setOnClickListener {
            dialog.dismiss()
            manualLocationContainer.visibility = View.VISIBLE
        }

        if (startInFindingState) {
            showSection("finding")
            dialog.show()

            val isOnline = try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.activeNetworkInfo?.isConnected == true
            } catch (e: Exception) { false }

            if (!isOnline) {
                showSection("internet")
                return
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                dialog.dismiss()
                return
            }

            val client = LocationServices.getFusedLocationProviderClient(this)
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { location ->
                    dialog.dismiss()
                    if (location != null) {
                        lifecycleScope.launch {
                            val label = reverseGeocode(location.latitude, location.longitude)
                            onLocationDetected(location.latitude, location.longitude, label)
                        }
                    } else {
                        onLocationFailed("Could not get location. Try again or enter it manually below.")
                    }
                }
                .addOnFailureListener { e ->
                    dialog.dismiss()
                    onLocationFailed("Location unavailable: ${e.message}")
                }
        } else {
            showSection("off")
            dialog.show()
        }
    }

    private fun onLocationDetected(lat: Double, lon: Double, label: String) {
        confirmedLat   = lat
        confirmedLon   = lon
        confirmedLabel = label

        locationStatusRow.visibility       = View.VISIBLE
        locationLabelText.text             = label.ifEmpty { "Unknown location" }
        latLonText.text                    = "${"%.5f".format(lat)}, ${"%.5f".format(lon)}"
        confirmLocationRow.visibility      = View.VISIBLE
        manualLocationContainer.visibility = View.GONE
        changeLocationButton.text          = "Refine on map"

        Toast.makeText(
            this,
            "📍 Tap \"Refine on map\" to pin your exact plantation spot",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun onLocationFailed(reason: String) {
        locationStatusRow.visibility  = View.GONE
        confirmLocationRow.visibility = View.GONE
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
    }

    // ── Reverse geocoding ─────────────────────────────────────────────────────
    // FIX (Issue 1): Street/drive-level specificity improvements:
    //   Android Geocoder – now combines subThoroughfare (number) + thoroughfare (road name),
    //     and uses subLocality (barangay) before falling back to locality.
    //   Nominatim – neighbourhood is checked before suburb/village (more specific in PH OSM
    //     data); also adds quarter and hamlet as extra fallbacks; if the structured
    //     fields still produce no street name, falls back to the first 3 parts of
    //     display_name so we always show something useful.

    private suspend fun reverseGeocode(lat: Double, lon: Double): String =
        withContext(Dispatchers.IO) {

            // ── 1. Android Geocoder ───────────────────────────────────────────
            try {
                val geocoder = Geocoder(this@PlantationProfileActivity, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    val a = addresses[0]

                    // Combine house number + street name into one part when available
                    val streetPart = listOfNotNull(
                        a.subThoroughfare?.takeIf { it.isNotBlank() },
                        a.thoroughfare?.takeIf    { it.isNotBlank() }
                    ).joinToString(" ").takeIf { it.isNotBlank() }

                    // subLocality is the barangay in PH; fall back to locality (city/town)
                    val localityPart = a.subLocality?.takeIf { it.isNotBlank() }
                        ?: a.locality?.takeIf { it.isNotBlank() }

                    val label = listOfNotNull(streetPart, localityPart, a.adminArea?.takeIf { it.isNotBlank() })
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(3)
                        .joinToString(", ")

                    if (label.isNotBlank()) return@withContext label
                }
            } catch (_: Exception) {}

            // ── 2. Nominatim (OpenStreetMap) ──────────────────────────────────
            try {
                val url = "https://nominatim.openstreetmap.org/reverse" +
                        "?format=json&lat=$lat&lon=$lon&zoom=18&addressdetails=1"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "GrowSight/1.0 Android")
                    .build()
                val body = httpClient.newCall(req).execute().use { it.body?.string() ?: "" }
                val json = org.json.JSONObject(body)
                val addr = json.optJSONObject("address")

                if (addr != null) {
                    // Street name – covers all OSM highway types mapped as "road"
                    val streetName = addr.optString("road").ifBlank { null }

                    // Neighbourhood (barangay) – in PH OSM data "neighbourhood" is more
                    // specific than "suburb", so it takes priority here
                    val neighbourhood = addr.optString("neighbourhood").ifBlank { null }
                        ?: addr.optString("quarter").ifBlank { null }
                        ?: addr.optString("suburb").ifBlank { null }
                        ?: addr.optString("village").ifBlank { null }
                        ?: addr.optString("hamlet").ifBlank { null }

                    val cityName = addr.optString("city").ifBlank { null }
                        ?: addr.optString("town").ifBlank { null }
                        ?: addr.optString("municipality").ifBlank { null }

                    val label = listOfNotNull(streetName, neighbourhood, cityName)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(3)
                        .joinToString(", ")

                    if (label.isNotBlank()) return@withContext label

                    // Fallback: first 3 comma-parts of display_name (always populated)
                    val displayName = json.optString("display_name", "")
                    if (displayName.isNotBlank()) {
                        return@withContext displayName.split(",")
                            .take(3)
                            .joinToString(", ") { it.trim() }
                    }
                }
            } catch (_: Exception) {}

            // ── 3. Last resort: raw coordinates ──────────────────────────────
            return@withContext "%.4f, %.4f".format(lat, lon)
        }

    // ── Forward geocoding (search) ────────────────────────────────────────────

    private fun searchLocation(query: String) {
        searchLocationButton.isEnabled = false
        searchLocationButton.text      = "Searching…"
        locationResultsList.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search" +
                        "?q=$encoded&format=json&limit=5&addressdetails=1"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "GrowSight/1.0 Android")
                    .build()
                val body = httpClient.newCall(req).execute().use { it.body?.string() ?: "[]" }
                val results = parseNominatimResults(body)

                withContext(Dispatchers.Main) {
                    searchLocationButton.isEnabled = true
                    searchLocationButton.text      = "Search location"

                    if (results.isEmpty()) {
                        Toast.makeText(
                            this@PlantationProfileActivity,
                            "No results for \"$query\". Try a city or municipality name.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@withContext
                    }

                    val adapter = ArrayAdapter(
                        this@PlantationProfileActivity,
                        android.R.layout.simple_list_item_1,
                        results.map { it.label }
                    )
                    locationResultsList.adapter    = adapter
                    locationResultsList.visibility = View.VISIBLE

                    locationResultsList.setOnItemClickListener { _, _, position, _ ->
                        val picked = results[position]
                        locationResultsList.visibility     = View.GONE
                        manualLocationContainer.visibility = View.GONE
                        // Open map so they can pin precisely
                        mapPickerLauncher.launch(
                            Intent(this@PlantationProfileActivity, MapPickerActivity::class.java).apply {
                                putExtra(MapPickerActivity.EXTRA_LAT,   picked.lat)
                                putExtra(MapPickerActivity.EXTRA_LON,   picked.lon)
                                putExtra(MapPickerActivity.EXTRA_LABEL, picked.label)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    searchLocationButton.isEnabled = true
                    searchLocationButton.text      = "🔍 Search"
                    Toast.makeText(
                        this@PlantationProfileActivity,
                        "Search failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private data class NominatimResult(val label: String, val lat: Double, val lon: Double)

    private fun parseNominatimResults(json: String): List<NominatimResult> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                val display = obj.optString("display_name", "")
                val label   = display.split(",").take(3).joinToString(", ") { it.trim() }
                NominatimResult(label, lat, lon)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Apply confirmed location ──────────────────────────────────────────────

    private fun applyConfirmedLocation(lat: Double, lon: Double, label: String) {
        confirmedLat   = lat
        confirmedLon   = lon
        confirmedLabel = label

        locationStatusRow.visibility       = View.GONE
        confirmLocationRow.visibility      = View.GONE
        manualLocationContainer.visibility = View.GONE

        confirmedLocationText.text       = label.ifEmpty { "%.4f, %.4f".format(lat, lon) }
        confirmedLocationChip.visibility = View.VISIBLE

        Toast.makeText(this, "Location confirmed ✓", Toast.LENGTH_SHORT).show()
    }

    // ── Create plantation ─────────────────────────────────────────────────────

    private fun onCreateTapped() {
        val rawName = plantationNameInput.text.toString().trim()

        // If name is blank, fall back to the confirmed location label
        val baseName = when {
            rawName.isNotEmpty() -> rawName
            !confirmedLabel.isNullOrBlank() -> {
                Toast.makeText(
                    this,
                    "No name entered — using your location as the plantation name.",
                    Toast.LENGTH_LONG
                ).show()
                confirmedLabel!!
            }
            else -> {
                plantationNameInput.error = "Enter a name, or confirm a location first so it can be used as the name"
                plantationNameInput.requestFocus()
                return
            }
        }

        // Always ensure "Plantation" is the last word
        val name = if (baseName.endsWith("Plantation", ignoreCase = true)) baseName
        else "$baseName Plantation"

        val computedWeek = TimeUnit.MILLISECONDS
            .toDays(System.currentTimeMillis() - selectedDateMs)
            .toInt().div(7).coerceIn(0, 16)

        createButton.isEnabled = false
        createButton.text      = "Creating…"

        lifecycleScope.launch(Dispatchers.IO) {
            val newId = db.conversationDao().insertConversation(
                ConversationEntity(
                    name          = name,
                    cropAgeWeeks  = computedWeek,
                    plantingDate  = selectedDateMs,
                    latitude      = confirmedLat,
                    longitude     = confirmedLon,
                    locationLabel = confirmedLabel
                )
            )

            withContext(Dispatchers.Main) {
                startActivity(
                    Intent(this@PlantationProfileActivity, ConversationsActivity::class.java).apply {
                        putExtra(ConversationsActivity.EXTRA_CONVERSATION_ID, newId)
                        putExtra("conversation_name", name)
                        putExtra("yolo_available", ModelManager.getYoloDetector() != null)
                        putExtra("cnn_available",  ModelManager.getMaturityClassifier() != null)
                        putExtra(EXTRA_PROFILING_DONE, true)
                    }
                )
                finish()
            }
        }
    }

    // ── Permission result ─────────────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission just granted — check if location services are on
                val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
                val isLocationEnabled =
                    locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                            locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

                if (isLocationEnabled) {
                    showLocationDialog(startInFindingState = true)
                } else {
                    showLocationDialog(startInFindingState = false)
                }
            } else {
                Toast.makeText(
                    this,
                    "Location access denied. Enter the location manually below.",
                    Toast.LENGTH_LONG
                ).show()
                manualLocationContainer.visibility = View.VISIBLE
            }
        }
    }
}