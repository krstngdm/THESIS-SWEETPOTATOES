package com.ai.growsight

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.util.concurrent.TimeUnit

class MapPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LAT    = "map_lat"
        const val EXTRA_LON    = "map_lon"
        const val EXTRA_LABEL  = "map_label"
        const val RESULT_LAT   = "result_lat"
        const val RESULT_LON   = "result_lon"
        const val RESULT_LABEL = "result_label"
    }

    private lateinit var mapView: MapView
    private lateinit var mapLocationLabel: EditText   // Changed from TextView → now user-editable
    private lateinit var mapLatLonLabel: TextView
    private lateinit var mapConfirmButton: MaterialButton
    private lateinit var mapCancelButton: Button
    private lateinit var mapBackButton: ImageButton
    private lateinit var mapLayerToggle: TextView

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var geocodeJob: Job? = null
    private var currentLat   = 0.0
    private var currentLon   = 0.0
    private var currentLabel = ""

    // Prevents TextWatcher from treating programmatic setText() as a user edit
    private var isUpdatingFromGeocode = false
    // Once the user types anything, geocoding stops overwriting their input
    private var userHasEditedLabel = false

    // Tracks which tile layer is active
    private var isSatelliteMode = true

    // ── ESRI World Imagery (satellite) tile source ────────────────────────────
    // ESRI tiles use  zoom / y / x  ordering (not the standard zoom / x / y).
    // We override getTileURLString to swap x and y for the correct URL.
    private val esriSatelliteTileSource = object : XYTileSource(
        "ESRIWorldImagery",
        0, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            "${getBaseUrl()}${MapTileIndex.getZoom(pMapTileIndex)}/" +
                    "${MapTileIndex.getY(pMapTileIndex)}/" +
                    "${MapTileIndex.getX(pMapTileIndex)}"
        // Note: ESRI tiles carry no file extension in the URL
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_map_picker)

        mapView          = findViewById(R.id.mapView)
        mapLocationLabel = findViewById(R.id.mapLocationLabel)
        mapLatLonLabel   = findViewById(R.id.mapLatLonLabel)
        mapConfirmButton = findViewById(R.id.mapConfirmButton)
        mapCancelButton  = findViewById(R.id.mapCancelButton)
        mapBackButton    = findViewById(R.id.mapBackButton)
        mapLayerToggle   = findViewById(R.id.mapLayerToggle)

        val startLat   = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val startLon   = intent.getDoubleExtra(EXTRA_LON, 0.0)
        val startLabel = intent.getStringExtra(EXTRA_LABEL) ?: ""

        currentLat   = startLat
        currentLon   = startLon
        currentLabel = startLabel

        setupMap(startLat, startLon, startLabel)
        setupLabelEditing()
        setupLayerToggle()

        mapBackButton.setOnClickListener   { finish() }
        mapCancelButton.setOnClickListener { finish() }

        mapConfirmButton.setOnClickListener {
            // Always trust what the user has in the EditText — either their own
            // typed value or the last geocoded suggestion they left unchanged
            val userLabel = mapLocationLabel.text.toString().trim()
            currentLabel  = userLabel.ifEmpty { "%.4f, %.4f".format(currentLat, currentLon) }
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra(RESULT_LAT,   currentLat)
                putExtra(RESULT_LON,   currentLon)
                putExtra(RESULT_LABEL, currentLabel)
            })
            finish()
        }
    }

    // ── Map setup ─────────────────────────────────────────────────────────────

    private fun setupMap(lat: Double, lon: Double, label: String) {
        mapView.setTileSource(esriSatelliteTileSource)  // satellite by default
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)                // zoom in a bit more for satellite
        mapView.controller.setCenter(GeoPoint(lat, lon))

        updateBottomCard(lat, lon, label)

        mapView.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent): Boolean {
                val c = mapView.mapCenter
                currentLat = c.latitude
                currentLon = c.longitude
                scheduleReverseGeocode(currentLat, currentLon)
                return true
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                val c = mapView.mapCenter
                currentLat = c.latitude
                currentLon = c.longitude
                scheduleReverseGeocode(currentLat, currentLon)
                return true
            }
        })
    }

    // ── Layer toggle (satellite ↔ street map) ─────────────────────────────────

    private fun setupLayerToggle() {
        // Label shows what tapping will switch TO (opposite of current mode)
        mapLayerToggle.text = "🗺 Map"
        mapLayerToggle.setOnClickListener {
            isSatelliteMode = !isSatelliteMode
            if (isSatelliteMode) {
                mapView.setTileSource(esriSatelliteTileSource)
                mapLayerToggle.text = "🗺 Map"
            } else {
                mapView.setTileSource(TileSourceFactory.MAPNIK)
                mapLayerToggle.text = "🛰 Satellite"
            }
        }
    }

    // ── Editable location label ───────────────────────────────────────────────

    private fun setupLabelEditing() {
        mapLocationLabel.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Only mark as user-edited when the change came from the keyboard,
                // not from our own programmatic setText() calls
                if (!isUpdatingFromGeocode) userHasEditedLabel = true
            }
        })
    }

    // ── Geocoding ─────────────────────────────────────────────────────────────

    // Debounce: wait 600 ms after the user stops scrolling before hitting the API
    private fun scheduleReverseGeocode(lat: Double, lon: Double) {
        mapLatLonLabel.text = "${"%.5f".format(lat)}, ${"%.5f".format(lon)}"
        if (!userHasEditedLabel) {
            isUpdatingFromGeocode = true
            mapLocationLabel.setText("Locating…")
            isUpdatingFromGeocode = false
        }
        geocodeJob?.cancel()
        geocodeJob = lifecycleScope.launch {
            delay(600)
            val label = reverseGeocode(lat, lon)
            currentLabel = label
            withContext(Dispatchers.Main) {
                if (!userHasEditedLabel) {
                    isUpdatingFromGeocode = true
                    mapLocationLabel.setText(label)
                    mapLocationLabel.setSelection(label.length) // cursor at end
                    isUpdatingFromGeocode = false
                }
            }
        }
    }

    private fun updateBottomCard(lat: Double, lon: Double, label: String) {
        if (!userHasEditedLabel) {
            isUpdatingFromGeocode = true
            mapLocationLabel.setText(label.ifEmpty { "Unknown location" })
            isUpdatingFromGeocode = false
        }
        mapLatLonLabel.text = "${"%.5f".format(lat)}, ${"%.5f".format(lon)}"
    }

    // Zoom 18 = most granular Nominatim can return (road-level)
    private suspend fun reverseGeocode(lat: Double, lon: Double): String =
        withContext(Dispatchers.IO) {
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
                    val street = addr.optString("road").ifBlank { null }
                    val neighbourhood = addr.optString("neighbourhood").ifBlank { null }
                        ?: addr.optString("quarter").ifBlank { null }
                        ?: addr.optString("suburb").ifBlank { null }
                        ?: addr.optString("village").ifBlank { null }
                        ?: addr.optString("hamlet").ifBlank { null }
                    val city = addr.optString("city").ifBlank { null }
                        ?: addr.optString("town").ifBlank { null }
                        ?: addr.optString("municipality").ifBlank { null }
                    val label = listOfNotNull(street, neighbourhood, city)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(", ")
                    if (label.isNotBlank()) return@withContext label
                }
                // Fallback: first 3 parts of display_name
                val display = json.optString("display_name", "")
                if (display.isNotBlank()) {
                    return@withContext display.split(",").take(3).joinToString(", ") { it.trim() }
                }
            } catch (_: Exception) {}
            "%.4f, %.4f".format(lat, lon)
        }

    override fun onResume()  { super.onResume();  mapView.onResume() }
    override fun onPause()   { super.onPause();   mapView.onPause() }
    override fun onDestroy() { super.onDestroy(); mapView.onDetach() }
}