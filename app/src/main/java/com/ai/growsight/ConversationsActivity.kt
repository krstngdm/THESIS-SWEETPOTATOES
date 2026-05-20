package com.ai.growsight

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.ybq.android.spinkit.SpinKitView
import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.room.Room

import com.ai.growsight.ai.CropInterpretation
import com.ai.growsight.ai.InterpretationEngine
import com.ai.growsight.ai.MaturityClassifier
import com.ai.growsight.ai.ModelManager
import com.ai.growsight.ai.PlantAnalysisResult
import com.ai.growsight.ai.YoloDetector
import com.ai.growsight.data.AppDatabase
import com.ai.growsight.data.ConversationEntity
import com.ai.growsight.ai.ScenarioClassifier
import com.ai.growsight.data.PromptEntity
import com.ai.growsight.ai.LocationWeatherManager
import com.ai.growsight.ai.WeatherData
import com.ai.growsight.ai.ModelUpdateManager
import com.ai.growsight.ai.WeatherService
import com.ai.growsight.ai.LocationHelper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.ImageDecoder
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.animation.AccelerateDecelerateInterpolator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.NumberPicker

import android.app.ProgressDialog
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.Calendar

import android.view.ViewOutlineProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.postDelayed
import androidx.core.view.updateLayoutParams

class ConversationsActivity : AppCompatActivity() {

    companion object {
        const val PICK_IMAGES_REQUEST = 1001
        private const val CAMERA_REQUEST_CODE = 2001
        private const val READ_STORAGE_PERMISSION_REQUEST_CODE = 102
        private const val LOCATION_PERMISSION_CODE = 4001
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val CAMERA_PERMISSION_CODE = 3001
        const val EXTRA_IMAGE_URIS = "extra_image_uris"
        const val EXTRA_HAS_PROCESSED_IMAGES = "extra_has_processed_images"
        val sentUris = mutableSetOf<String>()
    }


    private val UPDATE_CHECK_PREF = "model_auto_update_prefs"
    private val KEY_LAST_AUTO_CHECK = "last_auto_check_timestamp"

    private val processedUris = mutableSetOf<String>()

    private lateinit var uploadedImagesContainer: LinearLayout
    private lateinit var sendButton: com.google.android.material.button.MaterialButton
    private lateinit var uploadButton: ImageButton
    private lateinit var deleteButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private lateinit var previewContainer: LinearLayout
    private lateinit var scrollContent: ScrollView
    private lateinit var conversationTitle: TextView
    private lateinit var conversationListView: ListView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var messagesContainer: LinearLayout
    private lateinit var instructionText: TextView
    private lateinit var loader: SpinKitView
    private lateinit var previewScrollView: HorizontalScrollView

    private lateinit var weatherBanner: LinearLayout
    private lateinit var weatherBannerText: TextView
    private lateinit var weatherBannerAction: TextView

    private val uploadedUris = mutableListOf<Uri>()
    private var cameraImageUri: Uri? = null
    private var conversationId: Long = -1L
    private lateinit var db: AppDatabase

    private var yolo: YoloDetector? = null
    private var cnn: MaturityClassifier? = null
    private var scenarioClassifier: ScenarioClassifier? = null

    private var testClickCount = 0
    private var preloadedWeather: WeatherData? = null

    private var isSwitchingConversation = false
    private var isProcessingImages = false

    private var hasProcessedIncomingImages = false
    private var isInitialLoad = true
    private var currentProcessingIntentId = 0L
    private var isHistoryLoaded = false

    private var cachedWeather: WeatherData? = null
    private var weatherFetchState: WeatherFetchState = WeatherFetchState.IDLE
    private var weatherFetchedAt: Long = 0L
    private val WEATHER_CACHE_DURATION_MS = 30 * 60 * 1000L // 30 minutes

    private enum class WeatherFetchState { IDLE, FETCHING, DONE, SKIPPED }

    private lateinit var shimmerLayout: com.facebook.shimmer.ShimmerFrameLayout


    // ─────────────────────────────────────────────────────────────────────────
    // FEATURE 1 — Week / Stage constraint rules
    // Adjust these ranges to match your agronomic data.
    // ─────────────────────────────────────────────────────────────────────────
    fun expectedStageForWeek(week: Int): String = when {
        week <= 10 -> "Not Ready"
        week <= 15 -> "Near Harvest"
        else       -> "Harvest Ready"
    }

    fun isStageValidForWeek(stage: String, week: Int): Boolean = when (stage) {
        "Not Ready"     -> week <= 12
        "Near Harvest"  -> week in 8..18
        "Harvest Ready" -> week >= 14
        else            -> true
    }

    // ─────────────────────────────────────────────────────────────────────────


    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversations)

        checkForModelUpdatesOnStart()

        messagesContainer = findViewById(R.id.messagesContainer)
        instructionText   = findViewById(R.id.instructionText)
        loader            = findViewById(R.id.wave_loader)

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "prompts-db")
            .fallbackToDestructiveMigration().build()
        val conversationDao = db.conversationDao()

        previewScrollView        = findViewById(R.id.previewScrollView)
        uploadedImagesContainer  = findViewById(R.id.uploadedImagesContainer)
        sendButton               = findViewById(R.id.sendButton)
        uploadButton             = findViewById(R.id.uploadButton)
        deleteButton             = findViewById(R.id.deleteButton)
        cameraButton             = findViewById(R.id.cameraButton)
        previewContainer         = findViewById(R.id.previewContainer)
        scrollContent            = findViewById(R.id.scrollContent)
        conversationTitle        = findViewById(R.id.conversationTitle)
        conversationListView     = findViewById(R.id.conversationListView)
        drawerLayout             = findViewById(R.id.drawerLayout)
        val hamburgerButton      = findViewById<ImageButton>(R.id.menuButton)
        val backButton           = findViewById<ImageButton>(R.id.logoButton)
        val addConversationButton = findViewById<ImageButton>(R.id.addConversationButton)
        val editTitleButton      = findViewById<ImageButton>(R.id.editTitleButton)

        weatherBanner       = findViewById(R.id.weatherBanner)
        weatherBannerText   = findViewById(R.id.weatherBannerText)
        weatherBannerAction = findViewById(R.id.weatherBannerAction)

        yolo = ModelManager.getYoloDetector()
        cnn  = ModelManager.getMaturityClassifier()
        scenarioClassifier = ModelManager.getScenarioClassifier()

        shimmerLayout = findViewById(R.id.shimmerLayout)
        shimmerLayout?.visibility = View.GONE  // Safe call with ?

        if (yolo == null && cnn == null) Log.w("ConversationsActivity", "No models available - using fallback mode")
        else Log.d("ConversationsActivity", "Models loaded: YOLO=${yolo != null}, CNN=${cnn != null}")

        preloadedWeather = run {
            val temp = intent.getFloatExtra("preloaded_weather_temp", -999f)
            if (temp == -999f) null
            else WeatherData(
                temperatureCelsius = temp,
                humidity           = intent.getIntExtra("preloaded_weather_humidity", 0),
                precipitationMm    = intent.getFloatExtra("preloaded_weather_precip", 0f),
                weatherCode        = intent.getIntExtra("preloaded_weather_code", 0),
                locationLabel      = "Loaded"
            )
        }

        addConversationButton.setOnClickListener { showCreateConversationDialog() }
        backButton.setOnClickListener { finish() }

        hamburgerButton.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) drawerLayout.closeDrawer(GravityCompat.END)
            else drawerLayout.openDrawer(GravityCompat.END)
        }

        if (uploadedImagesContainer.childCount > 0) hideInstruction()

        val hasProcessed  = intent.getBooleanExtra(EXTRA_HAS_PROCESSED_IMAGES, false)
        val incomingUris  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_IMAGE_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(EXTRA_IMAGE_URIS)
        }

        if (!hasProcessed && !incomingUris.isNullOrEmpty()) {
            val urisHash = incomingUris.joinToString(",") { it.toString() }.hashCode()
            if (!processedUris.contains(urisHash.toString())) {
                lifecycleScope.launch {
                    handleIncomingImages(incomingUris)
                    processedUris.add(urisHash.toString())
                }
            }
        } else {
            Log.d("onCreate", "Skipping image processing - already processed or no images")
        }

        intent.removeExtra(EXTRA_IMAGE_URIS)
        intent.putExtra(EXTRA_HAS_PROCESSED_IMAGES, true)

        editTitleButton.setOnClickListener {
            conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, conversationId)
            if (conversationId != -1L) {
                lifecycleScope.launch {
                    try {
                        val conv = conversationDao.getConversationById(conversationId)
                        conv?.let {
                            val history = db.promptDao().getPromptsForConversation(conversationId)
                            runOnUiThread {
                                conversationTitle.text = it.name
                                uploadedImagesContainer.removeAllViews()
                                clearConversationCards()
                                history.forEach { prompt ->
                                    val uris = prompt.imageUris.map { Uri.parse(it) }
                                    addConversationCard(
                                        uris,
                                        parseDiagnosticString(prompt.diagnostic),
                                        prompt.timestamp,
                                        prompt.diagnostic == "no_detection",
                                        prompt.cropAgeWeeks
                                    )
                                }
                                isHistoryLoaded = true
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        runOnUiThread { Toast.makeText(this@ConversationsActivity, "Error loading conversation", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            val input = EditText(this).apply { setText(conversationTitle.text) }
            AlertDialog.Builder(this)
                .setTitle("Edit Conversation Name")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val newName = input.text.toString()
                    if (newName.isNotBlank()) {
                        lifecycleScope.launch {
                            db.conversationDao().updateConversationName(conversationId, newName)
                            runOnUiThread { conversationTitle.text = newName }
                            refreshConversationList()
                        }
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }

        conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, conversationId)
        if (conversationId != -1L) {
            lifecycleScope.launch {
                try {
                    val conv = conversationDao.getConversationById(conversationId)
                    conv?.let {
                        val history = db.promptDao().getPromptsForConversation(conversationId)
                        runOnUiThread {
                            conversationTitle.text = it.name
                            uploadedImagesContainer.removeAllViews()
                            previewContainer.removeAllViews()
                            clearConversationCards()
                            history.forEach { prompt ->
                                val uris = prompt.imageUris.map { Uri.parse(it) }
                                addConversationCard(
                                    uris,
                                    parseDiagnosticString(prompt.diagnostic),
                                    prompt.timestamp,
                                    prompt.diagnostic == "no_detection",
                                    prompt.cropAgeWeeks
                                )
                            }
                            isHistoryLoaded = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread { Toast.makeText(this@ConversationsActivity, "Error loading conversation", Toast.LENGTH_SHORT).show() }
                }
            }
        }

        refreshConversationList()
        updatePreviewVisibility()

        conversationListView.setOnItemClickListener { _, _, position, _ ->
            isSwitchingConversation = true
            isProcessingImages  = false
            isHistoryLoaded     = false
            loader.visibility        = View.GONE
            shimmerLayout.stopShimmer()
            shimmerLayout.visibility = View.GONE
            sendButton.isEnabled   = true
            uploadButton.isEnabled = true
            cameraButton.isEnabled = true
            uploadedImagesContainer.removeAllViews()
            previewContainer.removeAllViews()
            clearConversationCards()
            hidePreviewSection()

            lifecycleScope.launch {
                val all  = conversationDao.getAllConversations()
                val conv = all[position]
                conversationId = conv.id
                val history = db.promptDao().getPromptsForConversation(conversationId)
                runOnUiThread {
                    conversationTitle.text = conv.name
                    drawerLayout.closeDrawer(GravityCompat.END)
                    history.forEach { prompt ->
                        val uris = prompt.imageUris.map { Uri.parse(it) }
                        addConversationCard(
                            uris,
                            parseDiagnosticString(prompt.diagnostic),
                            prompt.timestamp,
                            prompt.diagnostic == "no_detection",
                            prompt.cropAgeWeeks
                        )
                    }
                    loader.visibility        = View.GONE
                    shimmerLayout.stopShimmer()
                    shimmerLayout.visibility = View.GONE
                    isProcessingImages      = false
                    isSwitchingConversation = false
                    isHistoryLoaded         = true
                }
            }
        }

        conversationListView.setOnItemLongClickListener { _, _, position, _ ->
            lifecycleScope.launch {
                val all  = db.conversationDao().getAllConversations()
                val conv = all[position]
                runOnUiThread {
                    AlertDialog.Builder(this@ConversationsActivity)
                        .setTitle("Manage Conversation")
                        .setItems(arrayOf("Edit Name", "Update Crop Age", "Delete Conversation")) { _, which ->
                            when (which) {
                                0 -> showEditDialog(conv)
                                1 -> showCropAgeDialog { age ->
                                    if (age > 0) {
                                        lifecycleScope.launch {
                                            db.conversationDao().updateCropAge(conv.id, age)
                                            Toast.makeText(this@ConversationsActivity, "Crop age updated to Week $age", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                2 -> deleteConversation(conv)
                            }
                        }.show()
                }
            }
            true
        }

        cameraButton.setOnClickListener {
            if (conversationId == -1L) {
                Toast.makeText(this, "Please select or create a plantation first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                detectionCameraLauncher.launch(
                    Intent(this, CameraDetectionActivity::class.java)
                )
            } else {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
                )
            }
        }

        uploadButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) openGallery()
                else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), READ_STORAGE_PERMISSION_REQUEST_CODE)
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) openGallery()
                else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), READ_STORAGE_PERMISSION_REQUEST_CODE)
            }
        }

        sendButton.setOnClickListener {
            isHistoryLoaded = true
            if (uploadedUris.isEmpty()) { Toast.makeText(this, "Add at least one image", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

            hasProcessedIncomingImages = false
            processedUris.clear()
            isProcessingImages = true
            showLoader()

            val persisted = uploadedUris.mapNotNull { ensureLocalCopy(it) }
            if (persisted.isEmpty()) { Toast.makeText(this, "Could not persist images", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

            uploadedUris.clear()
            previewContainer.removeAllViews()
            hidePreviewSection()

            if (!areModelsAvailable()) {
                val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(Date())
                lifecycleScope.launch {
                    db.promptDao().insertPrompt(PromptEntity(conversationId = conversationId, imageUris = persisted.map { it.toString() }, diagnostic = "no_detection", timestamp = timestamp, weekNumber = null, cropAgeWeeks   = db.conversationDao().getCropAge(conversationId)))
                    withContext(Dispatchers.Main) {
                        addConversationCard(persisted, null, timestamp, true)
                        scrollToBottom()
                        Toast.makeText(this@ConversationsActivity, "Images saved (AI unavailable)", Toast.LENGTH_SHORT).show()
                    }
                }
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.Main) {
                val history     = db.promptDao().getPromptsForConversation(conversationId)
                val existingAge = db.conversationDao().getCropAge(conversationId)

                // First send — ask crop age
                if (history.isEmpty() && existingAge == null) {
                    val age = suspendCancellableCoroutine<Int> { cont ->
                        showCropAgeDialog { selectedAge -> cont.resume(selectedAge) }
                    }
                    if (age > 0) db.conversationDao().updateCropAge(conversationId, age)
                }

                // FEATURE 4 — ask "same plant?" on every 2nd+ send
                val promptHistory = db.promptDao().getPromptsForConversation(conversationId)
                if (promptHistory.isNotEmpty()) {
                    val isSame = checkSameCrop()
                    if (!isSame) {
                        // Different plant — spin up a new conversation
                        val count   = db.conversationDao().getConversationCount()
                        val newName = "Plantation#${count + 1}"
                        withContext(Dispatchers.Main) {
                            showCropAgeDialog { ageWeeks ->
                                lifecycleScope.launch {
                                    val newId = db.conversationDao().insertConversation(
                                        ConversationEntity(name = newName, cropAgeWeeks = if (ageWeeks > 0) ageWeeks else null)
                                    )
                                    conversationId = newId
                                    withContext(Dispatchers.Main) {
                                        conversationTitle.text = newName
                                        uploadedImagesContainer.removeAllViews()
                                        clearConversationCards()
                                        refreshConversationList()
                                    }
                                    fetchWeatherThenProcess(persisted)
                                }
                            }
                        }
                        return@launch
                    }
                }

                fetchWeatherThenProcess(persisted)
            }
            scrollToBottom()
        }

        setupTesting()
        requestLocationPermission()
        checkAndShowWeatherBanner()
    }

    override fun onResume() {
        super.onResume()
        checkAndShowWeatherBanner()

        if (!isProcessingImages) {
            isSwitchingConversation = false
            loader.visibility        = View.GONE
            shimmerLayout.stopShimmer()
            shimmerLayout.visibility = View.GONE
            sendButton.isEnabled    = true
            uploadButton.isEnabled  = true
            cameraButton.isEnabled  = true
        }

        val cardsAlreadyShown = uploadedImagesContainer.childCount > 0

        if (conversationId != -1L && !isHistoryLoaded && !isProcessingImages && !cardsAlreadyShown) {
            lifecycleScope.launch {
                val history = db.promptDao().getPromptsForConversation(conversationId)
                runOnUiThread {
                    // Double-check: another coroutine may have beaten us here
                    if (!isHistoryLoaded && uploadedImagesContainer.childCount == 0) {
                        history.forEach { prompt ->
                            val uris = prompt.imageUris.map { Uri.parse(it) }
                            addConversationCard(
                                uris,
                                parseDiagnosticString(prompt.diagnostic),
                                prompt.timestamp,
                                prompt.diagnostic == "no_detection",
                                prompt.cropAgeWeeks
                            )
                        }
                        isHistoryLoaded = true
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incomingUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableArrayListExtra(EXTRA_IMAGE_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableArrayListExtra(EXTRA_IMAGE_URIS)
        }
        val hasProcessed = intent?.getBooleanExtra(EXTRA_HAS_PROCESSED_IMAGES, false) ?: false
        if (!hasProcessed && !incomingUris.isNullOrEmpty() && !isFinishing) {
            val urisHash = incomingUris.joinToString(",") { it.toString() }.hashCode()
            if (!processedUris.contains(urisHash.toString())) {
                lifecycleScope.launch {
                    handleIncomingImages(incomingUris)
                    processedUris.add(urisHash.toString())
                }
            }
        }
        intent?.removeExtra(EXTRA_IMAGE_URIS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("has_processed_images", hasProcessedIncomingImages)
        outState.putLong("current_conversation_id", conversationId)
        outState.putStringArrayList("processed_uris", ArrayList(processedUris))
        outState.putBoolean("is_history_loaded", isHistoryLoaded)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        hasProcessedIncomingImages = savedInstanceState.getBoolean("has_processed_images", false)
        conversationId             = savedInstanceState.getLong("current_conversation_id", -1L)
        isHistoryLoaded            = false          // always reload on restore
        savedInstanceState.getStringArrayList("processed_uris")?.let { processedUris.addAll(it) }
        if (conversationId != -1L) {
            lifecycleScope.launch {
                val conv = db.conversationDao().getConversationById(conversationId)
                conv?.let { runOnUiThread { conversationTitle.text = it.name } }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    detectionCameraLauncher.launch(Intent(this, CameraDetectionActivity::class.java))
                } else {
                    Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            READ_STORAGE_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) openGallery()
                else Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
            }
            LOCATION_PERMISSION_CODE -> {
                Log.d("Location", if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) "Location permission granted" else "Location permission denied — weather unavailable")
            }
        }
    }

    private val detectionCameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uriString = result.data?.getStringExtra(CameraDetectionActivity.RESULT_IMAGE_URI)
                val confidence = result.data?.getFloatExtra("detection_confidence", 0f) ?: 0f
                val wasDetected = result.data?.getBooleanExtra("was_detected", false) ?: false
                if (uriString != null) {
                    val uri = Uri.parse(uriString)
                    val persisted = ensureLocalCopy(uri) ?: return@registerForActivityResult
                    uploadedUris.clear()
                    uploadedUris.add(persisted)
                    // Directly trigger send flow with the captured image
                    isHistoryLoaded = true
                    hasProcessedIncomingImages = false
                    processedUris.clear()
                    isProcessingImages = true
                    showLoader()
                    val persistedList = listOf(persisted)
                    lifecycleScope.launch(Dispatchers.Main) {
                        val history     = db.promptDao().getPromptsForConversation(conversationId)
                        val existingAge = db.conversationDao().getCropAge(conversationId)
                        if (history.isEmpty() && existingAge == null) {
                            val age = suspendCancellableCoroutine<Int> { cont ->
                                showCropAgeDialog { cont.resume(it) }
                            }
                            if (age > 0) db.conversationDao().updateCropAge(conversationId, age)
                        }
                        val promptHistory = db.promptDao().getPromptsForConversation(conversationId)
                        if (promptHistory.isNotEmpty()) {
                            val isSame = checkSameCrop()
                            if (!isSame) {
                                val count   = db.conversationDao().getConversationCount()
                                val newName = "Plantation#${count + 1}"
                                showCropAgeDialog { ageWeeks ->
                                    lifecycleScope.launch {
                                        val newId = db.conversationDao().insertConversation(
                                            ConversationEntity(name = newName, cropAgeWeeks = if (ageWeeks > 0) ageWeeks else null)
                                        )
                                        conversationId = newId
                                        withContext(Dispatchers.Main) {
                                            conversationTitle.text = newName
                                            uploadedImagesContainer.removeAllViews()
                                            clearConversationCards()
                                            refreshConversationList()
                                        }
                                        fetchWeatherThenProcess(persistedList)
                                    }
                                }
                                return@launch
                            }
                        }
                        fetchWeatherThenProcess(persistedList)
                    }
                }
            }
        }

    private fun checkForModelUpdatesOnStart() {
        lifecycleScope.launch {
            try {
                if (!shouldAutoCheckForUpdates()) {
                    val days = getDaysUntilNextAutoCheck()
                    if (days > 0) Log.d("ModelUpdate", "Next auto-check in $days days")
                    return@launch
                }

                // Force onto Main before writing prefs
                withContext(Dispatchers.Main) {
                    recordAutoCheckTimestamp()
                    Toast.makeText(
                        this@ConversationsActivity,
                        "Checking for AI model updates…",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                Log.d("ModelUpdate", "Auto-checking for model updates (weekly schedule)")

                val success = ModelUpdateManager.checkForModelUpdates(this@ConversationsActivity)

                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(
                            this@ConversationsActivity,
                            "✅ Models updated! Restarting…",
                            Toast.LENGTH_LONG
                        ).show()
                        recreate()
                    }
                }

            } catch (e: Exception) {
                Log.e("ModelUpdate", "Failed to auto-check for updates", e)
            }
        }
    }

    internal fun shouldAutoCheckForUpdates(): Boolean {
        val prefs = getSharedPreferences(UPDATE_CHECK_PREF, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_AUTO_CHECK, 0L)
        Log.d("ModelUpdate", "Last check timestamp: $lastCheck, now: ${System.currentTimeMillis()}")
        if (lastCheck == 0L) return true
        val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - lastCheck)
        Log.d("ModelUpdate", "Days since last check: $days")
        return days >= 7
    }

    internal fun recordAutoCheckTimestamp() {
        val ts = System.currentTimeMillis()
        val result = getSharedPreferences(UPDATE_CHECK_PREF, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_AUTO_CHECK, ts)
            .commit()
        Log.d("ModelUpdate", "Timestamp written: $ts, commit success: $result")
    }

    internal fun resetAutoCheckTimer() {
        getSharedPreferences(UPDATE_CHECK_PREF, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_AUTO_CHECK, System.currentTimeMillis())
            .commit() // ← same fix
        Log.d("ModelUpdate", "Auto-check timer reset (manual check triggered)")
    }

    internal fun getDaysUntilNextAutoCheck(): Int {
        val prefs = getSharedPreferences(UPDATE_CHECK_PREF, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_AUTO_CHECK, 0L)
        if (lastCheck == 0L) return 0
        val daysSince = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - lastCheck).toInt()
        return (7 - daysSince).coerceAtLeast(0)
    }

    private suspend fun manualModelUpdateCheck() = withContext(Dispatchers.Main) {
        resetAutoCheckTimer()

        // ── Custom progress dialog ────────────────────────────────────────────
        val progressLayout = LinearLayout(this@ConversationsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 40)
            gravity = android.view.Gravity.CENTER
        }

        val progressBar = android.widget.ProgressBar(
            this@ConversationsActivity,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
            isIndeterminate = true    // starts as spinner-style
            visibility = View.VISIBLE
        }

        val progressMessage = TextView(this@ConversationsActivity).apply {
            text = "Checking GitHub for model updates…"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#212121"))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        progressLayout.addView(progressBar)
        progressLayout.addView(progressMessage)

        val progressDialog = MaterialAlertDialogBuilder(
            this@ConversationsActivity,
            R.style.MyCustomDialogLayout      // ← reuses your existing style + corner radius
        )
            .setTitle("Checking for Updates")
            .setView(progressLayout)
            .setCancelable(false)
            .show()

        var isHorizontalMode = false

        try {
            val success = ModelUpdateManager.checkForModelUpdates(
                context = this@ConversationsActivity,
                listener = object : ModelUpdateManager.UpdateListener {
                    override fun onUpdateStarted() {
                        Log.d("ModelUpdate", "Manual update check started")
                    }
                    override fun onDownloadProgress(fileName: String, progress: Int, totalFiles: Int) {
                        runOnUiThread {
                            if (!isHorizontalMode) {
                                isHorizontalMode = true
                                progressBar.isIndeterminate = false
                                progressBar.max = totalFiles
                            }
                            progressBar.progress = progress
                            progressMessage.text = "Downloading $fileName\n($progress / $totalFiles files)"
                        }
                    }
                    override fun onUpdateCompleted(success: Boolean, message: String) {
                        // no-op
                    }
                }
            )

            progressDialog.dismiss()

            if (success) {
                MaterialAlertDialogBuilder(this@ConversationsActivity, R.style.MyCustomDialogLayout)
                    .setTitle("✅ Update Complete")
                    .setMessage("Models updated successfully.\n\nThe app will now restart to load the new models.")
                    .setPositiveButton("OK") { _, _ -> recreate() }
                    .setCancelable(false)
                    .show()
            } else {
                Toast.makeText(
                    this@ConversationsActivity,
                    "✅ Models are up to date! Next auto-check in 7 days.",
                    Toast.LENGTH_LONG
                ).show()
            }

        } catch (e: Exception) {
            progressDialog.dismiss()
            Toast.makeText(
                this@ConversationsActivity,
                "Failed to check for updates: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showUpdateInfoDialog() {
        val daysUntilNext = getDaysUntilNextAutoCheck()
        val lastCheckPrefs = getSharedPreferences(UPDATE_CHECK_PREF, Context.MODE_PRIVATE)
        val lastCheckTimestamp = lastCheckPrefs.getLong(KEY_LAST_AUTO_CHECK, 0)

        val lastCheckDate = if (lastCheckTimestamp > 0) {
            val date = Date(lastCheckTimestamp)
            java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(date)
        } else {
            "Never"
        }

        AlertDialog.Builder(this)
            .setTitle("🤖 Model Auto-Update Settings")
            .setMessage(buildString {
                append("📅 Last auto-check: $lastCheckDate\n")
                append("⏰ Next auto-check: ${if (daysUntilNext == 0) "Today" else "in $daysUntilNext days"}\n\n")
                append("⚙️ How it works:\n")
                append("• Auto-checks for model updates once per week\n")
                append("• Timer resets when you manually check for updates\n")
                append("• Updates are downloaded from GitHub releases\n\n")
                append("To manually check, go to Test Menu → 'Check for Model Updates'")
            })
            .setPositiveButton("Manual Check Now") { _, _ ->
                lifecycleScope.launch {
                    manualModelUpdateCheck()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // Update your existing showTestMenu() method to include the model update option
    private fun showTestMenu() {
        AlertDialog.Builder(this)
            .setTitle("🧪 Model Testing")
            .setItems(arrayOf(
                "Quick Model Test",
                "Full Integration Test",
                "Model Status Check",
                "Test with Real Image",
                "Comprehensive Test",
                "Check for Model Updates",
                "Update Settings",
                "🎬 Test Shimmer Animation"  // Add this option
            )) { _, which ->
                when (which) {
                    0 -> runQuickTest()
                    1 -> runFullIntegrationTest()
                    2 -> checkModelStatus()
                    3 -> lifecycleScope.launch { testWithRealImage() }
                    4 -> runComprehensiveTest()
                    5 -> lifecycleScope.launch { manualModelUpdateCheck() }
                    6 -> showUpdateInfoDialog()
                    7 -> testShimmerAnimation()  // Handle shimmer test
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun testShimmerAnimation() {
        shimmerLayout?.let { shimmer ->
            shimmer.post {
                shimmer.visibility = View.VISIBLE
                shimmer.startShimmer()

                Handler(Looper.getMainLooper()).postDelayed({
                    if (shimmer.visibility == View.VISIBLE) {
                        shimmer.stopShimmer()
                        shimmer.visibility = View.GONE
                        Toast.makeText(this, "Shimmer test completed", Toast.LENGTH_SHORT).show()
                    }
                }, 3000)
            }
        } ?: run {
            Toast.makeText(this, "Shimmer layout not found in layout", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUpdateCropAgeDialog(onDone: () -> Unit) {
        lifecycleScope.launch {
            val currentAge     = db.conversationDao().getCropAge(conversationId)
            val currentAgeText = if (currentAge != null) "Week $currentAge" else "unknown"
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@ConversationsActivity)
                    .setTitle("Update Crop Age?")
                    .setMessage("Current recorded age: $currentAgeText\n\nWould you like to update the crop age for this scan?")
                    .setPositiveButton("Update Age") { _, _ ->
                        showCropAgeDialog { age ->
                            lifecycleScope.launch {
                                if (age > 0) db.conversationDao().updateCropAge(conversationId, age)
                                withContext(Dispatchers.Main) { onDone() }
                            }
                        }
                    }
                    .setNegativeButton("Keep Same Age") { _, _ -> onDone() }
                    .show()
            }
        }
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION_CODE)
        }
    }

    // ─── Diagnostic parsing ───────────────────────────────────────────────────

    private fun parseDiagnosticString(diagnostic: String): CropInterpretation? {
        if (diagnostic == "no_detection" || diagnostic.isBlank()) return null
        return try {
            val parts             = diagnostic.split("|")
            if (parts.size < 2) return null
            val stage             = parts[0]
            val confidencePercent = parts[1].toIntOrNull() ?: 0
            val savedHarvestTime  = if (parts.size >= 3 && parts[2].isNotBlank()) parts[2] else null
            val weatherSummary    = if (parts.size >= 4 && parts[3].isNotBlank()) parts[3].replace("~", "|") else null
            val savedWeatherRecs  = if (parts.size >= 5 && parts[4].isNotBlank()) parts[4].replace("~", "|").split("^").filter { it.isNotBlank() } else emptyList()
            val savedNarrative    = if (parts.size >= 6 && parts[5].isNotBlank()) parts[5].replace("~pipe~", "|") else null
            // parts[6] = saved bullet recommendations for Full detail tab
            val savedBullets      = if (parts.size >= 7 && parts[6].isNotBlank())
                parts[6].replace("~pipe~", "|").split("^").filter { it.isNotBlank() }
            else emptyList()

            val label = when (stage) {
                "Not Ready"     -> "not_ready"
                "Near Harvest"  -> "near_harvest"
                "Harvest Ready" -> "harvest_ready"
                else            -> return null
            }
            val fakeResult = PlantAnalysisResult(label = label, confidence = confidencePercent / 100f, allScores = emptyMap())
            val base       = InterpretationEngine.interpret(fakeResult)

            // Use saved bullets if available, otherwise fall back to base recommendations
            val restoredRecs = if (savedBullets.isNotEmpty()) savedBullets else {
                val baseFiltered = base.recommendations.filter { rec ->
                    !rec.startsWith("🌡️") && !rec.startsWith("💧") &&
                            !rec.startsWith("🌧️") && !rec.startsWith("🌦️") && !rec.startsWith("☀️")
                }
                baseFiltered + savedWeatherRecs
            }

            base.copy(
                harvestTime           = savedHarvestTime ?: base.harvestTime,
                weatherSummary        = weatherSummary,
                recommendations       = restoredRecs,
                interpretationSummary = savedNarrative
            )
        } catch (e: Exception) {
            Log.e("parseDiagnostic", "Failed to parse: $diagnostic", e)
            null
        }
    }

    // ─── UI helpers ───────────────────────────────────────────────────────────

    private fun encodeStageToLabel(stage: String): String = when (stage) {
        "Not Ready"     -> "not_ready"
        "Near Harvest"  -> "near_harvest"
        "Harvest Ready" -> "harvest_ready"
        else            -> "unknown"
    }

    private fun showPreviewSection()    { previewScrollView.visibility = View.VISIBLE }
    private fun hidePreviewSection()    { previewScrollView.visibility = View.GONE }
    private fun updatePreviewVisibility() { if (previewContainer.childCount > 0) showPreviewSection() else hidePreviewSection() }
    private fun hideInstruction()       { instructionText.visibility = View.GONE }
    private fun showInstruction()       { instructionText.visibility = View.VISIBLE }

    private fun showLoader() {
        runOnUiThread {
            if (isProcessingImages && !isSwitchingConversation) {
                loader.visibility      = View.GONE          // hide old spinner
                shimmerLayout.visibility = View.VISIBLE
                shimmerLayout.startShimmer()
                hideInstruction()  // ← Add this line to hide instruction text
                sendButton.isEnabled   = false
                uploadButton.isEnabled = false
                cameraButton.isEnabled = false
            } else {
                loader.visibility        = View.GONE
                shimmerLayout.stopShimmer()
                shimmerLayout.visibility = View.GONE
            }
        }
    }

    private fun hideLoader() {
        runOnUiThread {
            loader.visibility        = View.GONE
            shimmerLayout.stopShimmer()
            shimmerLayout.visibility = View.GONE
            sendButton.isEnabled     = true
            uploadButton.isEnabled   = true
            cameraButton.isEnabled   = true
            isProcessingImages       = false
        }
    }

    private fun checkAndShowWeatherBanner() {
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        val isLocationEnabled = hasLocationPermission && (
                locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                        locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
                )

        val params = weatherBanner.layoutParams

        if (!isLocationEnabled) {
            weatherBanner.visibility = View.VISIBLE
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            weatherBanner.layoutParams = params

            weatherBannerAction.setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            scrollContent.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                val sizeInDp = 163
                val sizeInPx = (sizeInDp * resources.displayMetrics.density).toInt()
                topMargin = sizeInPx
            }

            if (weatherFetchState == WeatherFetchState.DONE) {
                weatherFetchState = WeatherFetchState.IDLE
                cachedWeather     = null
                weatherFetchedAt  = 0L
                Log.d("Weather", "Cache invalidated — location turned off")
            }

        } else {
            weatherBanner.visibility = View.GONE
            scrollContent.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                val sizeInDp = 116
                val sizeInPx = (sizeInDp * resources.displayMetrics.density).toInt()
                topMargin = sizeInPx
            }
            params.height = 0
            weatherBanner.layoutParams = params
        }
    }

    private fun showModelErrorDialog(error: String) {
        AlertDialog.Builder(this).setTitle("AI Models Not Available").setMessage("$error\n\nYou can still save images without AI analysis.").setPositiveButton("OK", null).show()
    }

    fun clearConversationCards() {
        uploadedImagesContainer.removeAllViews()
        if (uploadedImagesContainer.childCount == 0 && shimmerLayout.visibility != View.VISIBLE) {
            showInstruction()
        }
    }

    private fun loadThumbnail(uri: Uri, targetSize: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            var sampleSize = 1
            val (width, height) = options.outWidth to options.outHeight
            while (width / (sampleSize * 2) >= targetSize && height / (sampleSize * 2) >= targetSize) sampleSize *= 2
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
        } catch (e: Exception) { Log.e("Thumbnail", "Failed to load thumbnail: ${e.message}"); null }
    }

    // ─── Incoming images ──────────────────────────────────────────────────────

    private suspend fun handleIncomingImages(incomingUris: ArrayList<Uri>?) {
        Log.d("HandleIncoming", "Called with ${incomingUris?.size} URIs, conversationId=$conversationId")
        val urisHash = incomingUris?.joinToString(",") { it.toString() }?.hashCode()?.toString() ?: return

        if (db.promptDao().existsByUriHash(urisHash)) { Log.d("HandleIncoming", "URIs already in DB — skipping"); hideLoader(); return }
        if (incomingUris.isNullOrEmpty())  { hideLoader(); return }
        if (isSwitchingConversation)       { hideLoader(); return }

        if (conversationId != -1L) {
            val existingPrompts = db.promptDao().getPromptsForConversation(conversationId)
            if (existingPrompts.isNotEmpty()) { Log.d("HandleIncoming", "Conversation already has prompts - skipping"); hideLoader(); return }
        }

        if (processedUris.contains(urisHash)) { hideLoader(); return }
        if (hasProcessedIncomingImages)        { hideLoader(); return }
        hasProcessedIncomingImages = true

        if (conversationId == -1L) {
            try {
                val count       = db.conversationDao().getConversationCount()
                val defaultName = "Plantation#${count + 1}"
                var cropAge: Int? = null
                if (db.conversationDao().getCropAge(conversationId) == null) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            showCropAgeDialog { age -> cropAge = if (age > 0) age else null; cont.resume(Unit) }
                        }
                    }
                } else {
                    cropAge = db.conversationDao().getCropAge(conversationId)
                }
                val newId = db.conversationDao().insertConversation(ConversationEntity(name = defaultName, cropAgeWeeks = cropAge))
                conversationId = newId
                runOnUiThread { conversationTitle.text = defaultName }
                refreshConversationList()
                val persistedUris = incomingUris.mapNotNull { ensureLocalCopy(it) }
                if (persistedUris.isNotEmpty() && areModelsAvailable()) {
                    isProcessingImages = true; showLoader()
                    withContext(Dispatchers.Main) {
                        val preloaded = preloadedWeather
                        if (preloaded != null) processImagesWithAI(persistedUris, preloaded) else fetchWeatherThenProcess(persistedUris)
                    }
                } else throw IllegalStateException("No images could be persisted")
            } catch (e: Exception) {
                Log.e("HandleIncoming", "Failed: ${e.message}", e); hideLoader(); fallbackSaveImages(incomingUris)
            }
        }
        processedUris.add(urisHash)
    }

    // ─── AI Processing ────────────────────────────────────────────────────────

    private data class ImageResult(
        val uri: Uri,
        val interpretation: CropInterpretation?,
        val isNotSweetPotato: Boolean,
        val isLowConfidence: Boolean
    )

    private suspend fun processImagesWithAI(persistedUris: List<Uri>, weather: WeatherData?) {

        val existingPrompts = db.promptDao().getPromptsForConversation(conversationId)
        val lastPrompt = existingPrompts.lastOrNull()
        if (lastPrompt != null) {
            val timeDiff = try {
                val sdf = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
                val lastTime = sdf.parse(lastPrompt.timestamp)
                if (lastTime != null) Date().time - lastTime.time else Long.MAX_VALUE
            } catch (e: Exception) { Long.MAX_VALUE }
            if (timeDiff < 10000) {
                withContext(Dispatchers.Main) { Toast.makeText(this@ConversationsActivity, "Images already analyzed recently", Toast.LENGTH_SHORT).show() }
                hideLoader(); return
            }
        }

        val urisHash = persistedUris.joinToString(",") { it.toString() }.hashCode()
        if (processedUris.contains(urisHash.toString())) { hideLoader(); return }
        processedUris.add(urisHash.toString())

        if (!isProcessingImages) { isProcessingImages = true; showLoader() }
        withContext(Dispatchers.Main) { Toast.makeText(this@ConversationsActivity, "Starting AI analysis...", Toast.LENGTH_SHORT).show() }
        if (!waitForModels()) { hideLoader(); fallbackSaveImages(persistedUris); return }

        val timestamp                   = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(Date())
        val OBJECT_DETECTION_THRESHOLD  = 0.60f
        val MATURITY_CONFIDENCE_THRESHOLD = 0.60f
        val cameraWasDetected           = intent.getBooleanExtra("camera_was_detected", false)
        val cameraConfidence            = intent.getFloatExtra("camera_detection_confidence", 0f)

        val imageResults = mutableListOf<ImageResult>()

        for ((index, uri) in persistedUris.withIndex()) {
            var bitmap: Bitmap? = null; var usedCrop: Bitmap? = null
            try {
                bitmap = loadBitmapFromUri(uri)
                if (bitmap == null) { imageResults.add(ImageResult(uri, null, false, false)); continue }

                var detectedResult: PlantAnalysisResult? = null
                var isNotSweetPotato = false; var isLowConfidence = false

                val skipYolo = cameraWasDetected && persistedUris.size == 1
                if (skipYolo) {
                    if (cnn != null) {
                        val result = withContext(Dispatchers.Default) { cnn?.classify(bitmap) }
                        if (result != null) { if (result.confidence >= MATURITY_CONFIDENCE_THRESHOLD) detectedResult = result else isLowConfidence = true }
                    }
                } else if (yolo != null) {
                    val detections         = withContext(Dispatchers.Default) { yolo?.detect(bitmap) ?: emptyList() }
                    val detection          = detections.maxByOrNull { it.score }
                    val detectionConfidence = detection?.score ?: 0f
                    if (detectionConfidence >= OBJECT_DETECTION_THRESHOLD && detection != null) {
                        val left   = detection.box.left.toInt().coerceIn(0, bitmap.width - 1)
                        val top    = detection.box.top.toInt().coerceIn(0, bitmap.height - 1)
                        val right  = detection.box.right.toInt().coerceIn(left + 1, bitmap.width)
                        val bottom = detection.box.bottom.toInt().coerceIn(top + 1, bitmap.height)
                        val w = right - left; val h = bottom - top
                        if (w > 10 && h > 10) usedCrop = try { Bitmap.createBitmap(bitmap, left, top, w, h) } catch (e: Exception) { null }
                        if (cnn != null) {
                            val result = withContext(Dispatchers.Default) { cnn?.classify(usedCrop ?: bitmap) }
                            Log.d("CONF", "CNN raw: ${result?.confidence}")  // add this
                            if (result != null) { if (result.confidence >= MATURITY_CONFIDENCE_THRESHOLD) detectedResult = result else isLowConfidence = true }
                        }
                    } else { isNotSweetPotato = true }
                } else if (cnn != null) {
                    val result = withContext(Dispatchers.Default) { cnn?.classify(bitmap) }
                    if (result != null) { if (result.confidence >= MATURITY_CONFIDENCE_THRESHOLD) detectedResult = result else isLowConfidence = true }
                }

                val interpretation = detectedResult?.let { plantResult ->
                    val sc = scenarioClassifier
                    if (sc != null) {
                        InterpretationEngine.interpret(plantResult)
                    } else {
                        if (weather != null) InterpretationEngine.interpretWithWeather(plantResult, weather)
                        else InterpretationEngine.interpret(plantResult)
                    }
                }
                imageResults.add(ImageResult(uri, interpretation, isNotSweetPotato, isLowConfidence))
            } catch (e: Exception) {
                Log.e("AI", "Error on image ${index + 1}: ${e.message}", e)
                imageResults.add(ImageResult(uri, null, false, false))
            } finally { bitmap?.recycle(); usedCrop?.recycle() }
        }

        val validResults        = imageResults.filter { it.interpretation != null }
        val notSweetPotatoCount = imageResults.count { it.isNotSweetPotato }
        val lowConfidenceCount  = imageResults.count { it.isLowConfidence }

        // Weighted majority vote
        val stageWeights = mutableMapOf<String, Float>()
        validResults.forEach { result ->
            val stage  = result.interpretation!!.stage
            val weight = result.interpretation.confidencePercent / 100f
            stageWeights[stage] = (stageWeights[stage] ?: 0f) + weight
        }
        val majorityStage         = stageWeights.maxByOrNull { it.value }?.key
        var summaryInterpretation = validResults.firstOrNull { it.interpretation?.stage == majorityStage }?.interpretation

        Log.d("CONF", "CNN raw confidence: ${validResults.firstOrNull()?.interpretation?.confidencePercent}")
        Log.d("CONF", "summaryInterpretation confidence: ${summaryInterpretation?.confidencePercent}")

        // Per-image data for the toggle panel
        val perImageStages: List<Pair<Uri, String?>> = imageResults.map { it.uri to it.interpretation?.stage }

        // Conflict detection
        val uniqueStages = validResults.map { it.interpretation!!.stage }.distinct()
        val hasConflict  = uniqueStages.size > 1
        val stageOrder   = mapOf("Not Ready" to 0, "Near Harvest" to 1, "Harvest Ready" to 2)
        val stageIndices = uniqueStages.mapNotNull { stageOrder[it] }
        val hasExtremeConflict = stageIndices.isNotEmpty() && (stageIndices.max() - stageIndices.min()) >= 2

        // FEATURE 1 — Stage constraint hard-block
        val cropAgeForValidation = db.conversationDao().getCropAge(conversationId)
        var wasBlocked  = false
        var blockReason: String? = null

        // ── Run ScenarioClassifier on the summary result ──────────────────────
        val finalInterpretation: CropInterpretation? = if (summaryInterpretation != null) {
            val sc = scenarioClassifier
            if (sc != null) {
                val scenarioResult = sc.interpret(
                    stage       = summaryInterpretation.stage,
                    confidence  = summaryInterpretation.confidencePercent / 100f,
                    cropWeek    = cropAgeForValidation,
                    weather     = weather,
                    imageCount  = persistedUris.size,
                    validCount  = validResults.size,
                    hasConflict = hasConflict
                )
                Log.d("AI", "ScenarioClassifier: ${scenarioResult.scenarioLabel} (id=${scenarioResult.scenarioId})")

                // Stage constraint hard-block still applies
                if (cropAgeForValidation != null && cropAgeForValidation > 0) {
                    val stage = summaryInterpretation.stage
                    if (!isStageValidForWeek(stage, cropAgeForValidation)) {
                        val expected = expectedStageForWeek(cropAgeForValidation)
                        blockReason  = "AI detected \"$stage\" but your crop is Week $cropAgeForValidation.\n\n" +
                                "At Week $cropAgeForValidation, your crop is expected to be: \"$expected\".\n\nResult overridden to inconclusive."
                        wasBlocked   = true
                        null
                    } else {
                        InterpretationEngine.interpretWithScenario(
                            result         = validResults.first { it.interpretation?.stage == majorityStage }
                                .let { PlantAnalysisResult(
                                    label      = encodeStageToLabel(summaryInterpretation.stage),
                                    confidence = summaryInterpretation.confidencePercent / 100f,
                                    allScores  = emptyMap()
                                )},
                            scenarioResult = scenarioResult,
                            weather        = weather
                        )
                    }
                } else {
                    InterpretationEngine.interpretWithScenario(
                        result         = PlantAnalysisResult(
                            label      = encodeStageToLabel(summaryInterpretation.stage),
                            confidence = summaryInterpretation.confidencePercent / 100f,
                            allScores  = emptyMap()
                        ),
                        scenarioResult = scenarioResult,
                        weather        = weather
                    )
                }
            } else {
                // No ScenarioClassifier — fall back to old behaviour
                if (cropAgeForValidation != null && cropAgeForValidation > 0 && !isStageValidForWeek(summaryInterpretation.stage, cropAgeForValidation)) {
                    val expected = expectedStageForWeek(cropAgeForValidation)
                    blockReason  = "AI detected \"${summaryInterpretation.stage}\" but your crop is Week $cropAgeForValidation.\n\nExpected: \"$expected\".\n\nResult overridden to inconclusive."
                    wasBlocked   = true
                    null
                } else {
                    if (weather != null) InterpretationEngine.interpretWithWeather(
                        PlantAnalysisResult(encodeStageToLabel(summaryInterpretation.stage), summaryInterpretation.confidencePercent / 100f, emptyMap()),
                        weather
                    ) else summaryInterpretation
                }
            }
        } else null

        Log.d("CONF", "finalInterpretation confidence: ${finalInterpretation?.confidencePercent}")

        if (wasBlocked && blockReason != null) {
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@ConversationsActivity)
                    .setTitle("⚠️ Result Blocked")
                    .setMessage(blockReason)
                    .setPositiveButton("OK", null).show()
            }
        }

        val diagnosticForDb = if (finalInterpretation != null) {
            val weatherPart   = finalInterpretation.weatherSummary?.replace("|", "~") ?: ""
            val weatherRecs   = finalInterpretation.recommendations
                .filter { it.startsWith("🌡️") || it.startsWith("💧") || it.startsWith("🌧️") || it.startsWith("🌦️") || it.startsWith("☀️") }
                .joinToString("^").replace("|", "~")
            val narrative     = finalInterpretation.interpretationSummary?.replace("|", "~pipe~") ?: ""
            // parts[6] = all recommendation bullets joined by ^ for Full detail tab
            val bullets       = finalInterpretation.recommendations.joinToString("^").replace("|", "~pipe~")
            "${finalInterpretation.stage}|${finalInterpretation.confidencePercent}|${finalInterpretation.harvestTime}|${weatherPart}|${weatherRecs}|${narrative}|${bullets}"
        } else if (summaryInterpretation != null) {
            val weatherPart   = summaryInterpretation.weatherSummary?.replace("|", "~") ?: ""
            val weatherRecs   = summaryInterpretation.recommendations
                .filter { it.startsWith("🌡️") || it.startsWith("💧") || it.startsWith("🌧️") || it.startsWith("🌦️") || it.startsWith("☀️") }
                .joinToString("^").replace("|", "~")
            val bullets       = summaryInterpretation.recommendations.joinToString("^").replace("|", "~pipe~")
            "${summaryInterpretation.stage}|${summaryInterpretation.confidencePercent}|${summaryInterpretation.harvestTime}|${weatherPart}|${weatherRecs}||${bullets}"
        } else {
            "no_detection"
        }

        val progressionInsight = getProgressionInsight()
        val estimatedAge       = getEstimatedCurrentAge()

        db.promptDao().insertPrompt(PromptEntity(
            conversationId = conversationId,
            imageUris      = persistedUris.map { it.toString() },
            diagnostic     = diagnosticForDb,
            timestamp      = timestamp,
            weekNumber     = null,
            cropAgeWeeks   = db.conversationDao().getCropAge(conversationId),
            uriHash        = persistedUris.joinToString(",") { it.toString() }.hashCode().toString()
        ))

        withContext(Dispatchers.Main) {
            addConversationCard(
                images               = persistedUris,
                imageResults         = imageResults.map { Triple(it.uri, it.interpretation, it.isNotSweetPotato) },
                summaryInterpretation = finalInterpretation,
                notSweetPotatoCount  = notSweetPotatoCount,
                lowConfidenceCount   = lowConfidenceCount,
                timestamp            = timestamp,
                weather              = weather,
                progressionInsight   = progressionInsight,
                cropAgeWeeks         = estimatedAge,
                hasConflict          = hasConflict,
                hasExtremeConflict   = hasExtremeConflict,
                perImageStages       = perImageStages
            )
            scrollToBottom()
            hideLoader()

            // Mini-card — only show when finalInterpretation succeeded
            val noDetectionUris = imageResults.filter { it.isNotSweetPotato }.map { it.uri }
            if (noDetectionUris.isNotEmpty() && finalInterpretation != null) {
                addNoDetectionMiniCard(noDetectionUris)
            }

            // Toast
            val toast = when {
                finalInterpretation != null -> "✅ ${finalInterpretation.stage} — ${validResults.size}/${persistedUris.size} valid images"
                notSweetPotatoCount == persistedUris.size -> "❌ No sweet potato plants detected in any image"
                else -> "⚠️ Could not classify images"
            }
            Toast.makeText(this@ConversationsActivity, toast, Toast.LENGTH_LONG).show()
        }
    }

    private fun fetchWeatherThenProcess(uris: List<Uri>) {
        val now = System.currentTimeMillis()
        val cacheExpired = (now - weatherFetchedAt) > WEATHER_CACHE_DURATION_MS

        when {
            weatherFetchState == WeatherFetchState.DONE && !cacheExpired -> {
                Log.d("Weather", "Using cached weather — ${(now - weatherFetchedAt) / 60000}min old")
                lifecycleScope.launch { processImagesWithAI(uris, cachedWeather) }
                return
            }
            // ✅ REMOVED: the SKIPPED early-return block that blocked all retries
            weatherFetchState == WeatherFetchState.FETCHING -> {
                lifecycleScope.launch {
                    while (weatherFetchState == WeatherFetchState.FETCHING) delay(200)
                    processImagesWithAI(uris, cachedWeather)
                }
                return
            }
            else -> {
                // Cache expired or IDLE or SKIPPED — always reset and fetch fresh
                if (weatherFetchState != WeatherFetchState.IDLE) {
                    Log.d("Weather", "Resetting state from $weatherFetchState — fetching fresh")
                    weatherFetchState = WeatherFetchState.IDLE
                }
            }
        }

        weatherFetchState = WeatherFetchState.FETCHING

        val timeoutJob = lifecycleScope.launch {
            delay(8_000)
            if (weatherFetchState == WeatherFetchState.FETCHING) {
                Log.w("Weather", "Timeout — proceeding without weather")
                cachedWeather     = null
                weatherFetchState = WeatherFetchState.IDLE   // ✅ Reset to IDLE, not SKIPPED
                weatherFetchedAt  = 0L                       // ✅ Don't cache the failed timestamp
                lifecycleScope.launch { processImagesWithAI(uris, null) }
            }
        }

        LocationWeatherManager.startWeatherFlow(
            activity = this,
            callback = object : LocationWeatherManager.WeatherFlowCallback {
                override fun onWeatherReady(weather: WeatherData?) {
                    if (weatherFetchState != WeatherFetchState.FETCHING) return
                    timeoutJob.cancel()
                    cachedWeather     = weather
                    weatherFetchState = WeatherFetchState.DONE
                    weatherFetchedAt  = System.currentTimeMillis()
                    Log.d("Weather", "Weather cached: ${weather?.temperatureCelsius}°C")
                    lifecycleScope.launch { processImagesWithAI(uris, weather) }
                }
                override fun onSkipped() {
                    if (weatherFetchState != WeatherFetchState.FETCHING) return
                    timeoutJob.cancel()
                    cachedWeather     = null
                    weatherFetchState = WeatherFetchState.IDLE   // ✅ Reset to IDLE, not SKIPPED
                    weatherFetchedAt  = 0L                       // ✅ Don't cache the failed timestamp
                    lifecycleScope.launch { processImagesWithAI(uris, null) }
                }
            }
        )
    }

    private suspend fun fallbackSaveImages(uris: List<Uri>) {
        try {
            val persistedUris = uris.mapNotNull { ensureLocalCopy(it) }
            if (persistedUris.isNotEmpty()) {
                val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(Date())
                db.promptDao().insertPrompt(PromptEntity(
                    conversationId = conversationId,
                    imageUris      = persistedUris.map { it.toString() },
                    diagnostic     = "no_detection",
                    timestamp      = timestamp,
                    weekNumber     = null,
                    cropAgeWeeks   = db.conversationDao().getCropAge(conversationId),
                    uriHash        = persistedUris.joinToString(",") { it.toString() }.hashCode().toString()
                ))
                withContext(Dispatchers.Main) {
                    addConversationCard(persistedUris, null, timestamp, true)
                    Toast.makeText(this@ConversationsActivity, "Images saved (AI unavailable)", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) { Log.e("Fallback", "Fallback save failed", e) }
    }

    private suspend fun waitForModels(): Boolean = withContext(Dispatchers.IO) { delay(100); ModelManager.areModelsAvailable() }
    private fun areModelsAvailable() = ModelManager.areModelsAvailable()

    // ─── Card UI ──────────────────────────────────────────────────────────────

    /**
     * Returns the drawable resource ID for the stage-color circle badge.
     * Used on per-image thumbnails inside the toggle detail panel.
     */
    private fun stageColorDrawable(stage: String?): Int = when (stage) {
        "Not Ready"     -> R.drawable.circle_red
        "Near Harvest"  -> R.drawable.circle_yellow
        "Harvest Ready" -> R.drawable.circle_green
        else            -> R.drawable.circle_gray
    }

    /**
     * Builds one thumbnail FrameLayout for the DETAIL panel.
     * Shows a small stage-color dot badge in the bottom-right corner.
     *
     * @param uri        image URI
     * @param stage      detected stage for this image (null = no detection)
     * @param thumbSize  pixel size of the thumbnail square
     */
    private fun buildDetailThumbnail(uri: Uri, stage: String?, thumbSize: Int): android.widget.FrameLayout {
        val wrapper = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize).apply { setMargins(4, 4, 4, 4) }
        }

        val img = ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.circle_gray)
            setOnClickListener { showImageModal(uri) }
        }
        wrapper.addView(img)

        // Stage-color dot badge — bottom-right corner
        val dotSize = (thumbSize * 0.22).toInt().coerceAtLeast(16)
        val dot = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                setMargins(0, 0, 4, 4)
            }
            setBackgroundResource(stageColorDrawable(stage))
        }
        wrapper.addView(dot)

        // Load thumbnail asynchronously
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = loadThumbnail(uri, thumbSize)
            withContext(Dispatchers.Main) { if (bmp != null) img.setImageBitmap(bmp) else img.setImageURI(uri) }
        }
        return wrapper
    }

    /**
     * Full card builder. All images + summary always visible.
     * Per-image breakdown + conflict info hidden behind the (!) toggle button.
     */
    private fun addConversationCard(
        images: List<Uri>,
        imageResults: List<Triple<Uri, CropInterpretation?, Boolean>>,
        summaryInterpretation: CropInterpretation?,
        notSweetPotatoCount: Int,
        lowConfidenceCount: Int,
        timestamp: String,
        weather: WeatherData?,
        progressionInsight: String?              = null,
        cropAgeWeeks: Int?                       = null,
        hasConflict: Boolean                     = false,
        hasExtremeConflict: Boolean              = false,
        perImageStages: List<Pair<Uri, String?>> = emptyList()
    ) {
        val card = layoutInflater.inflate(R.layout.item_conversation_card, uploadedImagesContainer, false)

        val mainImageRow            = card.findViewById<LinearLayout>(R.id.cardImageRow)
        val stageLabel              = card.findViewById<TextView>(R.id.stageLabel)
        val stageColorDot           = card.findViewById<View>(R.id.stageColorDot)
        val confidenceChip          = card.findViewById<TextView>(R.id.confidenceChip)
        val harvestTime             = card.findViewById<TextView>(R.id.harvestTime)
        val harvestTimeTitle        = card.findViewById<TextView>(R.id.harvestTimeTitle)
        val recommendationContainer = card.findViewById<LinearLayout>(R.id.recommendationContainer)
        val interpretationText      = card.findViewById<TextView>(R.id.interpretationSummaryText)
        val cardTimestamp           = card.findViewById<TextView>(R.id.cardTimestamp)
        val lowConfidenceWarning    = card.findViewById<TextView>(R.id.lowConfidenceWarning)
        val scenarioBadge           = card.findViewById<TextView>(R.id.scenarioBadge)
        val toggleSummary           = card.findViewById<LinearLayout>(R.id.toggleSummary)
        val toggleFullDetail        = card.findViewById<LinearLayout>(R.id.toggleFullDetail)
        val weatherStrip            = card.findViewById<LinearLayout>(R.id.weatherStrip)
        val weatherTemp             = card.findViewById<TextView>(R.id.weatherTemp)
        val weatherHumidity         = card.findViewById<TextView>(R.id.weatherHumidity)
        val weatherPrecip           = card.findViewById<TextView>(R.id.weatherPrecip)

        cardTimestamp.text = timestamp

        // ── Image row ─────────────────────────────────────────────────────────
        imageResults.forEach { (uri, interpretation, _) ->
            val wrapper = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(4, 4, 4, 4) }
            }
            val sizeInDp = 125
            val sizeInPx = (sizeInDp * resources.displayMetrics.density).toInt()

            val img = ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(sizeInPx, sizeInPx)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.circle_gray)

                val radiusPx = (5 * resources.displayMetrics.density) // 16dp → pixels
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
                clipToOutline = true
                setOnClickListener { showImageModal(uri) }
            }

            wrapper.addView(img)
            mainImageRow.addView(wrapper)
            lifecycleScope.launch(Dispatchers.IO) {
                val thumbnail = loadThumbnail(uri, 300)
                withContext(Dispatchers.Main) {
                    if (thumbnail != null) img.setImageBitmap(thumbnail) else img.setImageURI(uri)
                }
            }
        }

        // ── No detection case ─────────────────────────────────────────────────
        if (summaryInterpretation == null) {
            scenarioBadge.visibility        = View.GONE
            confidenceChip.visibility       = View.GONE
            harvestTimeTitle.visibility     = View.GONE
            harvestTime.visibility          = View.GONE
            lowConfidenceWarning.visibility = View.GONE
            weatherStrip.visibility         = View.GONE   // no weather on no-detection cards
            stageLabel.text                 = "No sweet potato detected"
            stageLabel.textSize             = 20f
            stageColorDot.setBackgroundResource(R.drawable.circle_gray)
            card.findViewById<LinearLayout>(R.id.viewModeToggleBar).visibility = View.GONE
            interpretationText.text       = "No plant was detected in the submitted images."
            interpretationText.visibility = View.VISIBLE
            recommendationContainer.removeAllViews()
            if (lowConfidenceCount > 0)
                addRecommendationBullet(recommendationContainer, "⚠️ $lowConfidenceCount image(s) were too unclear to classify")
            addRecommendationBullet(recommendationContainer, "Upload clearer images")
            addRecommendationBullet(recommendationContainer, "Ensure images clearly show sweet potato leaves or plant")
            uploadedImagesContainer.addView(card)
            if (uploadedImagesContainer.childCount > 0) {
                hideInstruction()  // Hide instruction when cards exist
            }
            scrollToBottom()
            return
        }

        // ── Scenario badge ────────────────────────────────────────────────────
        if (!summaryInterpretation.scenarioLabel.isNullOrBlank()) {
            scenarioBadge.text       = summaryInterpretation.scenarioLabel.replace("_", " ")
            scenarioBadge.visibility = View.VISIBLE
        } else {
            scenarioBadge.visibility = View.GONE
        }

        // ── Stage row ─────────────────────────────────────────────────────────
        stageLabel.text     = "${summaryInterpretation.stageEmoji} ${summaryInterpretation.stage}"
        stageLabel.textSize = 20f
        stageColorDot.setBackgroundResource(when (summaryInterpretation.stageColor) {
            "green"  -> R.drawable.circle_green
            "yellow" -> R.drawable.circle_yellow
            "red"    -> R.drawable.circle_red
            else     -> R.drawable.circle_gray
        })
        confidenceChip.visibility = View.VISIBLE
        confidenceChip.text       = "${summaryInterpretation.confidencePercent}% confidence"
        confidenceChip.textSize   = 14f
        confidenceChip.background = when (summaryInterpretation.stageColor) {
            "green"  -> ContextCompat.getDrawable(this, R.drawable.rounded_corner_green)
            "yellow" -> ContextCompat.getDrawable(this, R.drawable.rounded_corner_orange)
            "red"    -> ContextCompat.getDrawable(this, R.drawable.rounded_corner_red)
            else     -> ContextCompat.getDrawable(this, R.drawable.rounded_corner_gray)
        }
        confidenceChip.setTextColor(when (summaryInterpretation.stageColor) {
            "green" -> Color.WHITE; "yellow" -> Color.BLACK; "red" -> Color.WHITE; else -> Color.BLACK
        })
        lowConfidenceWarning.visibility = if (summaryInterpretation.lowConfidenceWarning) {
            lowConfidenceWarning.textSize = 14f; View.VISIBLE
        } else View.GONE

        // ── Harvest time ──────────────────────────────────────────────────────
        harvestTime.visibility      = View.VISIBLE
        harvestTimeTitle.visibility = View.VISIBLE
        harvestTime.text            = "→ ${summaryInterpretation.harvestTime}"
        harvestTime.textSize        = 16f

        // ─────────────────────────────────────────────────────────────────────
        // SUMMARY TAB = narrative paragraph from ScenarioClassifier
        // ─────────────────────────────────────────────────────────────────────
        val narrative = summaryInterpretation.interpretationSummary
        if (!narrative.isNullOrBlank()) {
            interpretationText.text       = narrative
            interpretationText.visibility = View.VISIBLE
        } else {
            interpretationText.text       = "No summary interpretation available."
            interpretationText.visibility = View.VISIBLE
        }

        // ─────────────────────────────────────────────────────────────────────
        // FULL DETAIL TAB = all bullet sections
        // ─────────────────────────────────────────────────────────────────────
        recommendationContainer.removeAllViews()

        if (images.size > 1) {
            val validCount = imageResults.count { it.second != null }
            addSectionHeader(recommendationContainer, "📊 Analysis Summary")
            addRecommendationBullet(recommendationContainer, "$validCount of ${images.size} images successfully analyzed")
            if (lowConfidenceCount > 0)
                addRecommendationBullet(recommendationContainer, "$lowConfidenceCount image(s) had unclear results")
        }

        if (cropAgeWeeks != null && cropAgeWeeks > 0) {
            addSectionHeader(recommendationContainer, "🌱 Crop Age")
            addRecommendationBullet(
                recommendationContainer,
                if (cropAgeWeeks >= 20) "Week 20 or older" else "Week $cropAgeWeeks since planting"
            )
        }

        if (progressionInsight != null) {
            addSectionHeader(recommendationContainer, "📈 Growth Progression")
            progressionInsight.split("\n").forEach { line ->
                if (line.isNotBlank()) addRecommendationBullet(recommendationContainer, line)
            }
        }

        summaryInterpretation.weatherSummary?.let { ws ->
            addSectionHeader(recommendationContainer, "🌤️ Weather Conditions")
            addRecommendationBullet(
                recommendationContainer,
                ws.replace("🌤️ Weather: ", "").replace("🌤️ ", "")
            )
        }

        addSectionHeader(recommendationContainer, "📋 Recommendations")
        summaryInterpretation.recommendations.forEach {
            addRecommendationBullet(recommendationContainer, it)
        }

        // Hide toggle bar only if BOTH tabs have nothing meaningful
        if (narrative.isNullOrBlank() && summaryInterpretation.recommendations.isEmpty()) {
            card.findViewById<LinearLayout>(R.id.viewModeToggleBar).visibility = View.GONE
        }

        fun tintDrawables(view: TextView, color: Int) {
            view.compoundDrawables.forEach { drawable ->
                if (drawable != null) {
                    androidx.core.graphics.drawable.DrawableCompat.wrap(drawable).also {
                        androidx.core.graphics.drawable.DrawableCompat.setTint(it, color)
                    }
                }
            }
        }

        val summaryIcon  = toggleSummary.getChildAt(0) as? ImageView
        val summaryText  = toggleSummary.getChildAt(1) as? TextView
        val detailIcon   = toggleFullDetail.getChildAt(0) as? ImageView
        val detailText   = toggleFullDetail.getChildAt(1) as? TextView

        fun applyViewMode(isSummary: Boolean) {
            if (isSummary) {
                // ── Summary tab — ACTIVE ──────────────────────────────────────
                toggleSummary.setBackgroundResource(R.drawable.toggle_active_bg)
                summaryText?.setTextColor(Color.parseColor("#212121"))
                summaryText?.setTypeface(null, android.graphics.Typeface.BOLD)
                summaryIcon?.setColorFilter(
                    Color.parseColor("#212121"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )

                // ── Full detail tab — INACTIVE ────────────────────────────────
                toggleFullDetail.setBackgroundColor(Color.TRANSPARENT)
                detailText?.setTextColor(Color.parseColor("#9E9E9E"))
                detailText?.setTypeface(null, android.graphics.Typeface.NORMAL)
                detailIcon?.setColorFilter(
                    Color.parseColor("#9E9E9E"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )

                // ── Content visibility ────────────────────────────────────────
                interpretationText.visibility      = View.VISIBLE
                recommendationContainer.visibility = View.GONE

            } else {
                // ── Full detail tab — ACTIVE ──────────────────────────────────
                toggleFullDetail.setBackgroundResource(R.drawable.toggle_active_bg)
                detailText?.setTextColor(Color.parseColor("#212121"))
                detailText?.setTypeface(null, android.graphics.Typeface.BOLD)
                detailIcon?.setColorFilter(
                    Color.parseColor("#212121"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )

                // ── Summary tab — INACTIVE ────────────────────────────────────
                toggleSummary.setBackgroundColor(Color.TRANSPARENT)
                summaryText?.setTextColor(Color.parseColor("#9E9E9E"))
                summaryText?.setTypeface(null, android.graphics.Typeface.NORMAL)
                summaryIcon?.setColorFilter(
                    Color.parseColor("#9E9E9E"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )

                // ── Content visibility ────────────────────────────────────────
                interpretationText.visibility      = View.GONE
                recommendationContainer.visibility = View.VISIBLE
            }
        }

        applyViewMode(isSummary = true)

        toggleSummary.setOnClickListener    { applyViewMode(isSummary = true)  }
        toggleFullDetail.setOnClickListener { applyViewMode(isSummary = false) }

        // ── Weather strip footer ──────────────────────────────────────────────
        if (weather != null && weather.temperatureCelsius > -900f) {
            weatherTemp.text        = "🌡️ ${"%.1f".format(weather.temperatureCelsius)}°C"
            weatherHumidity.text    = "💧 ${weather.humidity}%"
            weatherPrecip.text      = "🌧️ ${weather.precipitationMm}mm"
            weatherStrip.visibility = View.VISIBLE
        } else if (!summaryInterpretation.weatherSummary.isNullOrBlank()) {
            try {
                val afterPipe  = summaryInterpretation.weatherSummary.substringAfter("|").trim()
                val tempPart   = afterPipe.substringBefore(",").trim()
                val humidPart  = afterPipe.substringAfter(",").trim().replace("humidity", "").trim()
                weatherTemp.text         = "🌡️ $tempPart"
                weatherHumidity.text     = "💧 $humidPart"
                weatherPrecip.visibility = View.GONE
                weatherStrip.visibility  = View.VISIBLE
            } catch (e: Exception) {
                weatherStrip.visibility = View.GONE
            }
        } else {
            weatherStrip.visibility = View.GONE
        }

        uploadedImagesContainer.addView(card)
        if (uploadedImagesContainer.childCount > 0) hideInstruction()
        scrollToBottom()
    }


    // ─── No-detection mini-card (FEATURE 3) ───────────────────────────────────

    private fun addNoDetectionMiniCard(undetectedUris: List<Uri>) {
        if (undetectedUris.isEmpty()) return
        val card      = layoutInflater.inflate(R.layout.item_no_detection_mini_card, uploadedImagesContainer, false)
        val imageRow  = card.findViewById<LinearLayout>(R.id.miniCardImageRow)
        val titleView = card.findViewById<TextView>(R.id.miniCardTitle)
        titleView.text = "❌ ${undetectedUris.size} Undetected Image${if (undetectedUris.size > 1) "s" else ""}"

        undetectedUris.forEach { uri ->
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(200, 200).apply { setMargins(4, 4, 4, 4) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha     = 0.65f
                setImageResource(R.drawable.circle_gray)
                setOnClickListener { showImageModal(uri) }
            }
            imageRow.addView(img)
            lifecycleScope.launch(Dispatchers.IO) {
                val thumb = loadThumbnail(uri, 200)
                withContext(Dispatchers.Main) { if (thumb != null) img.setImageBitmap(thumb) else img.setImageURI(uri) }
            }
        }
        uploadedImagesContainer.addView(card)
    }

    // ─── Crop age dialog ──────────────────────────────────────────────────────

    private fun showCropAgeDialog(onAgeSelected: (Int) -> Unit) {

        // ── Root container ────────────────────────────────────────────────────
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        // ── Friendly subtitle ─────────────────────────────────────────────────
        root.addView(TextView(this).apply {
            text = "This helps us give you better harvest advice 🌿"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#757575"))
            typeface = nunitoRegular
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
        })

        // ── Choice cards row ──────────────────────────────────────────────────
        val cardRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            weightSum = 2f
        }

        fun makeChoiceCard(emoji: String, line1: String, line2: String): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(0, 0, 8, 0) }
                setPadding(16, 24, 16, 24)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 20f
                    setColor(android.graphics.Color.parseColor("#F5F5F5"))
                    setStroke(2, android.graphics.Color.parseColor("#E0E0E0"))
                }
                isClickable = true
                isFocusable = true

                addView(TextView(this@ConversationsActivity).apply {
                    text = emoji
                    textSize = 28f
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 8) }
                })
                addView(TextView(this@ConversationsActivity).apply {
                    text = line1
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    typeface = nunitoBold
                    setTextColor(android.graphics.Color.parseColor("#212121"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
                addView(TextView(this@ConversationsActivity).apply {
                    text = line2
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER
                    typeface = nunitoRegular
                    setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 4, 0, 0) }
                })
            }
        }

        val cardDatePicker = makeChoiceCard("📅", "I know the date", "I'll pick when I planted")
        val cardEstimate = makeChoiceCard("🤔", "I'll estimate", "Roughly how many weeks").apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(8, 0, 0, 0)
        }
        cardRow.addView(cardDatePicker)
        cardRow.addView(cardEstimate)
        root.addView(cardRow)

        // ── Panel A: Date Picker ──────────────────────────────────────────────
        val panelDate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility  = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }
        }

        val cal = Calendar.getInstance()
        val today = cal.timeInMillis
        cal.add(Calendar.WEEK_OF_YEAR, -22)
        val earliest = cal.timeInMillis

        val datePickerWrapper = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6f * resources.displayMetrics.density  // ← adjust this number
                setColor(android.graphics.Color.parseColor("#F9F9F9"))
            }
            // Clip children to the rounded shape
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    val radius = 20f * resources.displayMetrics.density
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }

        val datePicker = android.widget.DatePicker(this).apply {
            maxDate = today
            minDate = earliest
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        datePickerWrapper.addView(datePicker)
        panelDate.addView(datePickerWrapper)

        panelDate.addView(TextView(this).apply {
            text = "📌 The app will remember this and track the age automatically."
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            typeface = nunitoRegular
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }
        })
        root.addView(panelDate)

        // ── Panel B: Friendly week estimator ──────────────────────────────────
        val panelEstimate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility  = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Groups: label → week range
        data class WeekGroup(val emoji: String, val label: String, val sublabel: String, val range: IntRange)
        val groups = listOf(
            WeekGroup("🌱", "Just started",   "1 – 3 weeks",   1..3),
            WeekGroup("🌿", "Growing",        "4 – 8 weeks",   4..8),
            WeekGroup("🍃", "Well established","9 – 13 weeks", 9..13),
            WeekGroup("🌾", "Almost ready", "14 – 22 weeks", 14..22)
        )

        var selectedWeek = 1
        var expandedGroup: WeekGroup? = null
        val weekButtonRows = mutableMapOf<WeekGroup, LinearLayout>()
        val weekButtons = mutableListOf<TextView>()
        val groupCards = mutableListOf<LinearLayout>()

        val activeGreen  = android.graphics.Color.parseColor("#4CAF50")
        val inactiveGray = android.graphics.Color.parseColor("#F0F0F0")

        fun makeWeekBg(active: Boolean) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(if (active) activeGreen else inactiveGray)
        }

        fun refreshAllWeekButtons() {
            weekButtons.forEach { btn ->
                val w = btn.tag as Int
                btn.background = makeWeekBg(w == selectedWeek)
                btn.setTextColor(
                    if (w == selectedWeek) android.graphics.Color.WHITE
                    else android.graphics.Color.parseColor("#212121")
                )
                btn.typeface = if (w == selectedWeek) nunitoBold else nunitoRegular
            }
        }

        groups.forEach { group ->
            // Group header card
            val groupCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
                setPadding(20, 20, 20, 20)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16f
                    setColor(android.graphics.Color.parseColor("#F9F9F9"))
                    setStroke(2, android.graphics.Color.parseColor("#E0E0E0"))
                }
                isClickable = true
                isFocusable = true
            }

            groupCard.addView(TextView(this).apply {
                text = group.emoji
                textSize = 22f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 16, 0) }
            })

            val groupTextCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            groupTextCol.addView(TextView(this).apply {
                text = group.label
                textSize = 14f
                typeface = nunitoBold
                setTextColor(android.graphics.Color.parseColor("#212121"))
            })
            groupTextCol.addView(TextView(this).apply {
                text = group.sublabel
                textSize = 12f
                typeface = nunitoRegular
                setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
            })
            groupCard.addView(groupTextCol)

            // Chevron
            val chevron = TextView(this).apply {
                text = "›"
                textSize = 22f
                setTextColor(android.graphics.Color.parseColor("#BDBDBD"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            groupCard.addView(chevron)
            groupCards.add(groupCard)
            panelEstimate.addView(groupCard)

            // Week button row (hidden until group is tapped)
            val weekRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
            }

            group.range.forEach { w ->
                val btn = TextView(this).apply {
                    text = if (w == 20) "20+" else "Wk $w"
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    tag = w
                    layoutParams = LinearLayout.LayoutParams(0, 72, 1f).apply {
                        setMargins(4, 0, 4, 0)
                    }
                    background = makeWeekBg(w == selectedWeek)
                    setTextColor(
                        if (w == selectedWeek) android.graphics.Color.WHITE
                        else android.graphics.Color.parseColor("#212121")
                    )
                    typeface = nunitoRegular
                    setOnClickListener {
                        selectedWeek = w
                        refreshAllWeekButtons()
                    }
                }
                weekButtons.add(btn)
                weekRow.addView(btn)
            }
            weekButtonRows[group] = weekRow
            panelEstimate.addView(weekRow)

            // Group card tap: expand/collapse
            groupCard.setOnClickListener {
                val isExpanding = expandedGroup != group
                // Collapse all
                weekButtonRows.values.forEach { it.visibility = View.GONE }
                groupCards.forEach { gc ->
                    gc.setBackgroundResource(0)
                    gc.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 16f
                        setColor(android.graphics.Color.parseColor("#F9F9F9"))
                        setStroke(2, android.graphics.Color.parseColor("#E0E0E0"))
                    }
                }
                chevron.text = "›"
                if (isExpanding) {
                    expandedGroup = group
                    weekRow.visibility = View.VISIBLE
                    groupCard.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 16f
                        setColor(android.graphics.Color.parseColor("#F1F8E9"))
                        setStroke(2, android.graphics.Color.parseColor("#4CAF50"))
                    }
                    chevron.text = "⌄"
                    // Auto-select first week in group if none selected in range
                    if (selectedWeek !in group.range) {
                        selectedWeek = group.range.first
                        refreshAllWeekButtons()
                    }
                } else {
                    expandedGroup = null
                }
            }
        }

        val scrollEstimate = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 420
            )
            addView(panelEstimate)
        }
        root.addView(scrollEstimate)

        // ── Card selection logic ──────────────────────────────────────────────
        var isDateMode = false

        fun selectCard(dateMode: Boolean) {
            isDateMode = dateMode
            val activeStroke  = android.graphics.Color.parseColor("#4CAF50")
            val activeFill    = android.graphics.Color.parseColor("#F1F8E9")
            val inactiveFill  = android.graphics.Color.parseColor("#F5F5F5")
            val inactiveStroke = android.graphics.Color.parseColor("#E0E0E0")

            cardDatePicker.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setColor(if (dateMode) activeFill else inactiveFill)
                setStroke(2, if (dateMode) activeStroke else inactiveStroke)
            }
            cardEstimate.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setColor(if (!dateMode) activeFill else inactiveFill)
                setStroke(2, if (!dateMode) activeStroke else inactiveStroke)
            }
            panelDate.visibility     = if (dateMode) View.VISIBLE else View.GONE
            scrollEstimate.visibility = if (!dateMode) View.VISIBLE else View.GONE
            panelEstimate.visibility  = View.VISIBLE
        }

        cardDatePicker.setOnClickListener { selectCard(true) }
        cardEstimate.setOnClickListener   { selectCard(false) }

        selectCard(false)

// ── Dialog ────────────────────────────────────────────────────────────
        selectCard(false) // pre-select estimate

        val dialog = MaterialAlertDialogBuilder(this, R.style.MyCustomDialogLayout)
            .setTitle("🌱 When did you plant this?")
            .setView(root)
            .setPositiveButton("Confirm", null) // ← null listener, we wire it manually below
            .setCancelable(false)
            .show()

        val confirmButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)

        // ── Initial button state ──────────────────────────────────────────────
        // Estimate panel is pre-selected, and selectedWeek defaults to 1 (always valid)
        // BUT we want the user to explicitly tap a group first, so start disabled
        confirmButton.isEnabled = false

        // ── Re-evaluate button state ──────────────────────────────────────────
        // Call this whenever selection changes
        fun refreshConfirmButton() {
            confirmButton.isEnabled = when {
                isDateMode  -> true                    // date picker always has a value
                else        -> expandedGroup != null   // estimate: only valid after tapping a group
            }
        }

        // ── Wire card selection to also refresh button ────────────────────────
        // Override the card click listeners to call refreshConfirmButton after selectCard
        cardDatePicker.setOnClickListener {
            selectCard(true)
            refreshConfirmButton()
        }
        cardEstimate.setOnClickListener {
            selectCard(false)
            refreshConfirmButton()
        }

        // ── Wire group card taps to refresh button ────────────────────────────
        // We need to patch each group card's click listener to also call refreshConfirmButton
        // Do this by re-setting the listener on each groupCard after the forEach loop
        // Since groupCards is already populated, iterate it again here:
        groups.forEachIndexed { index, group ->
            val groupCard = groupCards[index]
            val weekRow   = weekButtonRows[group]!!
            val chevron   = groupCard.getChildAt(groupCard.childCount - 1) as? TextView

            groupCard.setOnClickListener {
                val isExpanding = expandedGroup != group

                // Collapse all (same logic as before)
                weekButtonRows.values.forEach { it.visibility = View.GONE }
                groupCards.forEach { gc ->
                    gc.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 16f
                        setColor(android.graphics.Color.parseColor("#F9F9F9"))
                        setStroke(2, android.graphics.Color.parseColor("#E0E0E0"))
                    }
                }

                if (isExpanding) {
                    expandedGroup = group
                    weekRow.visibility = View.VISIBLE
                    groupCard.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 16f
                        setColor(android.graphics.Color.parseColor("#F1F8E9"))
                        setStroke(2, android.graphics.Color.parseColor("#4CAF50"))
                    }
                    chevron?.text = "⌄"
                    if (selectedWeek !in group.range) {
                        selectedWeek = group.range.first
                        refreshAllWeekButtons()
                    }
                } else {
                    expandedGroup = null
                    chevron?.text = "›"
                }

                refreshConfirmButton() // ← update button after group tap
            }
        }

        // ── Wire the confirm button manually ─────────────────────────────────
        confirmButton.setOnClickListener {
            if (isDateMode) {
                val picked = Calendar.getInstance().apply {
                    set(datePicker.year, datePicker.month, datePicker.dayOfMonth, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val diffMs       = Calendar.getInstance().timeInMillis - picked.timeInMillis
                val computedWeek = (diffMs / (1000L * 60 * 60 * 24 * 7)).toInt().coerceIn(1, 22)
                lifecycleScope.launch {
                    if (conversationId != -1L)
                        db.conversationDao().updatePlantingDate(conversationId, picked.timeInMillis)
                }
                onAgeSelected(computedWeek)
            } else {
                onAgeSelected(selectedWeek)
            }
            dialog.dismiss()
        }
    }

    // ─── Progression helpers ──────────────────────────────────────────────────

    private suspend fun getProgressionInsight(): String? {
        if (conversationId == -1L) return null
        val history = db.promptDao().getPromptsForConversation(conversationId)
        if (history.size < 2) return null
        val prevStage = extractStageFromDiagnostic(history[history.size - 2].diagnostic)
        val currStage = extractStageFromDiagnostic(history[history.size - 1].diagnostic)
        if (prevStage == null || currStage == null) return null
        if (prevStage == "no_detection" || currStage == "no_detection") return null
        return buildProgressionText(prevStage, currStage)
    }

    private suspend fun getEstimatedCurrentAge(): Int? {
        // Priority 1: planting date (most accurate)
        val plantingDate = db.conversationDao().getPlantingDate(conversationId)
        if (plantingDate != null && plantingDate > 0) {
            val diffMs = System.currentTimeMillis() - plantingDate
            return (diffMs / (1000L * 60 * 60 * 24 * 7)).toInt().coerceIn(1, 22)
        }

        // Priority 2: base age + elapsed weeks since first scan (existing logic)
        val baseAge = db.conversationDao().getCropAge(conversationId) ?: return null
        val history = db.promptDao().getPromptsForConversation(conversationId)
        if (history.isEmpty()) return baseAge
        return try {
            val sdf = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
            val firstDate = sdf.parse(history.first().timestamp) ?: return baseAge
            val weeksElapsed = ((Date().time - firstDate.time) / (1000L * 60 * 60 * 24 * 7)).toInt()
            (baseAge + weeksElapsed).coerceAtMost(22)
        } catch (e: Exception) { baseAge }
    }

    private fun extractStageFromDiagnostic(diagnostic: String): String? {
        if (diagnostic == "no_detection") return "no_detection"
        val parts = diagnostic.split("|")
        return if (parts.isNotEmpty()) parts[0] else null
    }

    private fun buildProgressionText(prevStage: String, currStage: String): String {
        val stageOrder = mapOf("Not Ready" to 0, "Near Harvest" to 1, "Harvest Ready" to 2)
        val prevOrder  = stageOrder[prevStage] ?: return ""
        val currOrder  = stageOrder[currStage] ?: return ""
        return when {
            currOrder > prevOrder  -> "📈 Progression: $prevStage → $currStage\n✅ Crop is progressing normally"
            currOrder == prevOrder -> "📊 Progression: $prevStage → $currStage\n⏳ Crop remains at the same stage"
            else                   -> "📉 Progression: $prevStage → $currStage\n⚠️ Stage regression detected — check crop health"
        }
    }

    // ─── Overload used when loading history from DB ───────────────────────────

    private fun addConversationCard(images: List<Uri>, interpretation: CropInterpretation?, timestamp: String, isNoDetection: Boolean = false, cropAgeWeeks: Int? = null ) {
        addConversationCard(
            images               = images,
            imageResults         = images.map { Triple(it, interpretation, isNoDetection) },
            summaryInterpretation = interpretation,
            notSweetPotatoCount  = if (isNoDetection) images.size else 0,
            lowConfidenceCount   = 0,
            timestamp            = timestamp,
            weather              = if (interpretation?.weatherSummary != null) WeatherData(-999f, 0, 0f, 0, "") else null,
            progressionInsight   = null,
            cropAgeWeeks         = cropAgeWeeks,
            hasConflict          = false,
            hasExtremeConflict   = false,
            perImageStages       = emptyList()
        )
    }

    // FEATURE 4 — same-crop dialog
    private suspend fun checkSameCrop(): Boolean {
        if (conversationId == -1L) return false
        val history = db.promptDao().getPromptsForConversation(conversationId)
        if (history.isEmpty()) return false
        val lastScan  = history.last()
        val lastStage = extractStageFromDiagnostic(lastScan.diagnostic)
        val cropAge   = db.conversationDao().getCropAge(conversationId)
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                AlertDialog.Builder(this@ConversationsActivity)
                    .setTitle("🌿 Same Plant?")
                    .setMessage(buildString {
                        append("Your last scan in this conversation was:\n\n")
                        append("📅 ${lastScan.timestamp}\n")
                        if (lastStage != null && lastStage != "no_detection") append("🌱 Stage: $lastStage\n")
                        if (cropAge != null) append("📆 Crop age: Week $cropAge\n")
                        append("\nAre the image(s) you're about to send of the same plant?")
                    })
                    .setPositiveButton("✅ Yes — Same Plant")      { _, _ -> cont.resume(true)  }
                    .setNegativeButton("🔄 No — Different Plant") { _, _ -> cont.resume(false) }
                    .setCancelable(false).show()
            }
        }
    }

    // ─── Text helpers — for recommendation container ──────────────────────────

    private fun addSectionHeader(container: LinearLayout, text: String) {
        container.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 24, 0, 6) }
            this.text = text
            textSize  = 15f   // fontSize
            typeface  = nunitoBold
            setTextColor(ContextCompat.getColor(this@ConversationsActivity, R.color.black))
        })
    }

    private fun addRecommendationBullet(container: LinearLayout, text: String) {
        container.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(16, 6, 0, 6) }
            this.text = "• $text"
            textSize  = 15f   // fontSize
            typeface  = nunitoRegular
            setTextColor(ContextCompat.getColor(this@ConversationsActivity, R.color.black))
            setLineSpacing(0f, 1.3f)
        })
    }

    /** Same as addSectionHeader but targets a specific container (used for detail panel). */
    private fun addSectionHeaderTo(container: LinearLayout, text: String) = addSectionHeader(container, text)

    /** Same as addRecommendationBullet but targets a specific container (used for detail panel). */
    private fun addBulletTo(container: LinearLayout, text: String) = addRecommendationBullet(container, text)

    private val nunitoRegular by lazy { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.nunito_regular) }
    private val nunitoBold    by lazy { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.nunito_bold)    }

    // ─── Image helpers ────────────────────────────────────────────────────────

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
            } else {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
            bitmap?.let {
                if (it.config != Bitmap.Config.ARGB_8888) { val copy = it.copy(Bitmap.Config.ARGB_8888, false); it.recycle(); copy } else it
            }
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    private fun ensureLocalCopy(uri: Uri): Uri? {
        if (uri.scheme == "file") return uri
        return try {
            val imagesDir = File(filesDir, "images").apply { if (!exists()) mkdirs() }
            val outFile   = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
            val inputStream = contentResolver.openInputStream(uri) ?: run { Log.e("ensureLocalCopy", "Could not open input stream for: $uri"); return null }
            inputStream.use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output) } }
            if (outFile.length() == 0L) { Log.e("ensureLocalCopy", "File was written but is empty: $uri"); return null }
            Uri.fromFile(outFile)
        } catch (e: Exception) { Log.e("ensureLocalCopy", "Failed to copy: ${e.message}", e); null }
    }

    private fun showImageModal(uri: Uri) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.image_modal_layout)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val modalImage = dialog.findViewById<ImageView>(R.id.modalImageView)
        modalImage?.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setImageURI(uri)
            scaleType      = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { dialog.dismiss(); true } else false
        }
        dialog.show()
    }

    private fun addPreviewImage(uri: Uri) {
        uploadedUris.add(uri)
        val previewItem = layoutInflater.inflate(R.layout.image_preview_item, null)
        previewItem.layoutParams = LinearLayout.LayoutParams(
            resources.getDimensionPixelSize(R.dimen.preview_size),
            resources.getDimensionPixelSize(R.dimen.preview_size)
        ).apply { setMargins(4, 4, 4, 4) }
        previewItem.findViewById<ImageView>(R.id.previewImage).setImageURI(uri)
        previewItem.findViewById<ImageButton>(R.id.deleteButton).setOnClickListener { removePreviewImage(previewItem, uri) }
        previewItem.tag = uri
        previewContainer.addView(previewItem)
        updatePreviewVisibility()
    }

    private fun removePreviewImage(previewItem: View, uri: Uri) {
        previewContainer.removeView(previewItem)
        uploadedUris.remove(uri)
        updatePreviewVisibility()
        Toast.makeText(this, "Image removed", Toast.LENGTH_SHORT).show()
    }

    // ─── Conversation management ──────────────────────────────────────────────

    private fun refreshConversationList() {
        lifecycleScope.launch {
            val names = db.conversationDao().getAllConversations().map { it.name }
            runOnUiThread { conversationListView.adapter = ArrayAdapter(this@ConversationsActivity, android.R.layout.simple_list_item_1, names) }
        }
    }

    private fun showCreateConversationDialog() {
        val input = EditText(this).apply { hint = "Enter conversation name"; setSingleLine(true) }
        AlertDialog.Builder(this)
            .setTitle("Create New Conversation")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) createNewConversation(name) else Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun createNewConversation(name: String, showAgeDialog: Boolean = true) {
        isProcessingImages  = false; isSwitchingConversation = false; isHistoryLoaded = true
        if (showAgeDialog) {
            showCropAgeDialog { ageWeeks ->
                lifecycleScope.launch {
                    val newId = db.conversationDao().insertConversation(ConversationEntity(name = name, cropAgeWeeks = if (ageWeeks > 0) ageWeeks else null))
                    conversationId = newId
                    runOnUiThread {
                        conversationTitle.text = name
                        drawerLayout.closeDrawer(GravityCompat.END)
                        refreshConversationList()
                        uploadedImagesContainer.removeAllViews()
                        previewContainer.removeAllViews()
                        clearConversationCards()
                        Toast.makeText(this@ConversationsActivity, if (ageWeeks > 0) "Plantation created — Crop age: Week $ageWeeks" else "Plantation created", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            lifecycleScope.launch {
                val newId = db.conversationDao().insertConversation(ConversationEntity(name = name))
                conversationId = newId
                runOnUiThread {
                    conversationTitle.text = name
                    drawerLayout.closeDrawer(GravityCompat.END)
                    refreshConversationList()
                    uploadedImagesContainer.removeAllViews()
                    previewContainer.removeAllViews()
                    clearConversationCards()
                }
            }
        }
    }

    private fun openCamera() {
        if (conversationId == -1L) { Toast.makeText(this, "Please select or create a plantation first", Toast.LENGTH_SHORT).show(); return }
        val photoFile    = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "photo_${System.currentTimeMillis()}.jpg")
        cameraImageUri   = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", photoFile)
        val intent       = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) != null) startActivityForResult(intent, CAMERA_REQUEST_CODE)
        else Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
    }

    private fun openGallery() {
        lifecycleScope.launch {
            if (conversationId == -1L) {
                val count = db.conversationDao().getConversationCount()
                val name  = "Plantation#${count + 1}"
                conversationId = db.conversationDao().insertConversation(ConversationEntity(name = name))
                runOnUiThread { conversationTitle.text = name }
                refreshConversationList()
            }
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
        if (intent.resolveActivity(packageManager) != null) startActivityForResult(Intent.createChooser(intent, "Select Pictures"), PICK_IMAGES_REQUEST)
        else Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
    }

    private fun showEditDialog(conv: ConversationEntity) {
        val input = EditText(this).apply { setText(conv.name) }
        AlertDialog.Builder(this)
            .setTitle("Edit Conversation Name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        db.conversationDao().updateConversationName(conv.id, newName)
                        if (conv.id == conversationId) runOnUiThread { conversationTitle.text = newName }
                        refreshConversationList()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deleteConversation(conv: ConversationEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Conversation")
            .setMessage("Are you sure you want to delete this conversation?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    db.conversationDao().deleteConversation(conv)
                    if (conv.id == conversationId) {
                        runOnUiThread {
                            conversationTitle.text = "No Plantation Available"
                            uploadedImagesContainer.removeAllViews()
                            previewContainer.removeAllViews()
                            clearConversationCards()
                        }
                        conversationId = -1L
                    }
                    refreshConversationList()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        isHistoryLoaded = true
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            PICK_IMAGES_REQUEST -> {
                val clipData = data?.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) { val uri = clipData.getItemAt(i).uri; if (!uploadedUris.contains(uri)) addPreviewImage(uri) }
                } else {
                    data?.data?.let { if (!uploadedUris.contains(it)) addPreviewImage(it) }
                }
            }
            CAMERA_REQUEST_CODE -> { cameraImageUri?.let { if (!uploadedUris.contains(it)) addPreviewImage(it) } }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cachedWeather     = null
        weatherFetchState = WeatherFetchState.IDLE
        weatherFetchedAt  = 0L
    }
    // ─── Testing ──────────────────────────────────────────────────────────────

    private fun setupTesting() {
        conversationTitle.setOnClickListener {
            testClickCount++
            if (testClickCount >= 3) {
                testClickCount = 0
                // If shimmer is running, stop it
                if (shimmerLayout.visibility == View.VISIBLE) {
                    shimmerLayout.stopShimmer()
                    shimmerLayout.visibility = View.GONE
                    Toast.makeText(this, "Shimmer stopped", Toast.LENGTH_SHORT).show()
                } else {
                    showTestMenu()
                }
            }
        }
    }

    private fun runQuickTest() {
        lifecycleScope.launch {
            showTestProgress("Running Quick Test...")
            try {
                if (!waitForModels()) { showTestResult("❌ Models not loaded"); return@launch }
                val testBitmap = createTestBitmap()
                val result     = cnn?.classify(testBitmap)
                if (result != null) {
                    val interpretation = InterpretationEngine.interpret(result)
                    showTestResult("✅ QUICK TEST PASSED\n\nLabel: ${result.label}\nConfidence: ${"%.1f".format(result.confidence * 100)}%\nStage: ${interpretation.stage}\nHarvest Time: ${interpretation.harvestTime}")
                } else showTestResult("❌ Prediction returned null")
                testBitmap.recycle()
            } catch (e: Exception) { showTestResult("❌ Test Failed: ${e.message}") }
        }
    }

    private fun runFullIntegrationTest() {
        lifecycleScope.launch {
            showTestProgress("Running Full Integration Test...")
            try {
                if (!waitForModels()) { showTestResult("❌ Models not loaded"); return@launch }
                val testBitmap = createTestBitmap()
                val result     = cnn?.classify(testBitmap)
                val sb         = StringBuilder("🧪 FULL INTEGRATION TEST\n\n1. Basic Prediction:\n")
                if (result != null) {
                    val interpretation = InterpretationEngine.interpret(result)
                    sb.append("   ✅ SUCCESS\n   - Label: ${result.label}\n   - Confidence: ${"%.1f".format(result.confidence * 100)}%\n   - Stage: ${interpretation.stage}\n   - Harvest Time: ${interpretation.harvestTime}\n")
                } else sb.append("   ❌ FAILED\n")
                testBitmap.recycle(); showTestResult(sb.toString())
            } catch (e: Exception) { showTestResult("❌ Integration Test Failed: ${e.message}") }
        }
    }

    private fun checkModelStatus() {
        showTestResult("🔍 MODEL STATUS\n\nCNN: ${if (cnn != null) "✅ LOADED" else "❌ NOT LOADED"}\nYOLO: ${if (yolo != null) "✅ LOADED" else "❌ NOT LOADED"}\nAll Ready: ${ModelManager.areModelsAvailable()}")
    }

    private suspend fun testWithRealImage() {
        showTestProgress("Testing with real image...")
        try {
            if (uploadedUris.isNotEmpty()) {
                val testBitmap = loadBitmapFromUri(uploadedUris.first())
                if (testBitmap != null) {
                    val result         = cnn?.classify(testBitmap)
                    val interpretation = result?.let { InterpretationEngine.interpret(it) }
                    showTestResult("📸 REAL IMAGE TEST\n\nLabel: ${result?.label ?: "N/A"}\nConfidence: ${result?.confidence?.let { "%.1f".format(it * 100) } ?: "N/A"}%\nStage: ${interpretation?.stage ?: "N/A"}\nHarvest Time: ${interpretation?.harvestTime ?: "N/A"}\n\n${if (result != null) "✅ Analysis completed" else "❌ Analysis failed"}")
                    testBitmap.recycle()
                } else showTestResult("❌ Could not load image from URI")
            } else showTestResult("ℹ️ No images available.\n\nUpload an image first, then test again.")
        } catch (e: Exception) { showTestResult("❌ Real image test failed: ${e.message}") }
    }

    private fun runComprehensiveTest() {
        lifecycleScope.launch {
            showTestProgress("Running Comprehensive Test...")
            val sb = StringBuilder("🧪 COMPREHENSIVE MODEL TEST\n\n")
            try {
                sb.append("1. MODEL LOADING:\n")
                if (!waitForModels()) { sb.append("   ❌ Models failed to load\n"); showTestResult(sb.toString()); return@launch }
                sb.append("   ✅ Models loaded\n   - YOLO: ${yolo != null}\n   - CNN: ${cnn != null}\n\n")

                sb.append("2. YOLO DETECTION TEST:\n")
                if (yolo != null) {
                    val testBitmap = createTestBitmap()
                    val detections = withContext(Dispatchers.Default) { yolo?.detect(testBitmap) ?: emptyList() }
                    if (detections.isNotEmpty()) { val best = detections.maxByOrNull { it.score }!!; sb.append("   ✅ YOLO working — ${best.label} (${"%.1f".format(best.score * 100)}%)\n") }
                    else sb.append("   ⚠️ YOLO working but no detection\n")
                    testBitmap.recycle()
                } else sb.append("   ❌ YOLO not available\n")

                sb.append("\n3. CNN CLASSIFICATION TEST:\n")
                if (cnn != null) {
                    val testBitmap = createTestBitmap()
                    val result     = withContext(Dispatchers.Default) { cnn?.classify(testBitmap) }
                    if (result != null) {
                        val interpretation = InterpretationEngine.interpret(result)
                        sb.append("   ✅ CNN working\n   - Label: ${result.label}\n   - Confidence: ${"%.1f".format(result.confidence * 100)}%\n   - Stage: ${interpretation.stage}\n   - Harvest Time: ${interpretation.harvestTime}\n")
                    } else sb.append("   ❌ CNN returned null\n")
                    testBitmap.recycle()
                } else sb.append("   ❌ CNN not available\n")

                sb.append("\n4. ASSET FILES CHECK:\n")
                listOf("ml/yolov8.tflite", "ml/sweetpotato_final.tflite", "ml/yolo_labels.json", "ml/labels.txt").forEach { asset ->
                    try { assets.open(asset).close(); sb.append("   ✅ $asset\n") }
                    catch (e: Exception) { sb.append("   ❌ $asset — ${e.message}\n") }
                }
            } catch (e: Exception) { sb.append("\n❌ Comprehensive test failed: ${e.message}") }
            showTestResult(sb.toString())
        }
    }

    private fun createTestBitmap() = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until width) for (y in 0 until height) setPixel(x, y, Color.argb(255, 50, (150 + (x * 100 / width)).coerceIn(0, 255), 50))
    }

    private fun showTestProgress(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    private fun showTestResult(msg: String)   = runOnUiThread { AlertDialog.Builder(this).setTitle("Test Results").setMessage(msg).setPositiveButton("OK", null).show() }

    // ─── Scroll ───────────────────────────────────────────────────────────────

    private fun scrollToBottom() {
        runOnUiThread {
            uploadedImagesContainer.postDelayed({
                try {
                    if (scrollContent.childCount > 0) {
                        val child   = scrollContent.getChildAt(0)
                        val target  = child.bottom - scrollContent.height
                        val current = scrollContent.scrollY
                        if (current < target - 50) {
                            ValueAnimator.ofInt(current, target).apply {
                                duration     = 600
                                interpolator = AccelerateDecelerateInterpolator()
                                addUpdateListener { scrollContent.scrollTo(0, it.animatedValue as Int) }
                                start()
                            }
                        }
                    }
                } catch (e: Exception) { scrollContent.fullScroll(View.FOCUS_DOWN) }
            }, 100)
        }
    }
}