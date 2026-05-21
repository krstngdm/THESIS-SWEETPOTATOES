package com.ai.growsight

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.ybq.android.spinkit.SpinKitView
import android.Manifest
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
import android.view.WindowManager
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
import com.ai.growsight.ai.AnomalyFlag
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
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.Calendar
import com.ai.growsight.util.PlantationVoidChecker

import android.view.ViewOutlineProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.postDelayed
import androidx.core.view.updateLayoutParams

import com.ai.growsight.util.PlantationWeekHelper
import com.ai.growsight.PlantationProfileActivity
import com.facebook.shimmer.BuildConfig
import android.widget.GridView
import com.google.android.material.button.MaterialButton
import android.widget.BaseAdapter
import android.widget.ImageView
import androidx.core.view.doOnLayout

import com.ai.growsight.workers.CheckInNotificationScheduler
import com.ai.growsight.workers.CheckInNotificationWorker
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.marginEnd
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ai.growsight.util.CooldownManager
import androidx.work.workDataOf

import com.ai.growsight.util.WeekEndAlertScheduler

class ConversationsActivity : AppCompatActivity() {

    companion object {
        const val PICK_IMAGES_REQUEST = 1001
        private const val CAMERA_REQUEST_CODE = 2001
        private const val READ_STORAGE_PERMISSION_REQUEST_CODE = 102
        private const val LOCATION_PERMISSION_CODE = 4001
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val CAMERA_PERMISSION_CODE = 3001
        const val EXTRA_HAS_PROCESSED_IMAGES = "extra_has_processed_images"
        val sentUris = mutableSetOf<String>()
        const val EXTRA_IMAGE_URIS = "extra_image_uris"
        const val EXTRA_IS_QUICK_SCAN = "extra_is_quick_scan"
    }

    private lateinit var inputRow: LinearLayout
    private lateinit var sendLockedRow: LinearLayout
    private lateinit var sendLockTimeText: TextView
    private lateinit var sendLockProgressBar: ProgressBar
    private var cooldownTimer: CountDownTimer? = null
    private lateinit var uploadNoteContainer: LinearLayout

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — WorkManager fires regardless */ }

    private val UPDATE_CHECK_PREF = "model_auto_update_prefs"
    private val KEY_LAST_AUTO_CHECK = "last_auto_check_timestamp"

    private val processedUris = mutableSetOf<String>()

    private lateinit var uploadedImagesContainer: LinearLayout
    private lateinit var sendButton: com.google.android.material.button.MaterialButton
    private lateinit var uploadButton: ImageButton
    private lateinit var deleteButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private lateinit var previewContainer: LinearLayout
    private lateinit var scrollContent: RelativeLayout
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

    private var debugShowBoundingBoxes = false
    private val debugBitmaps = mutableListOf<Bitmap>()

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

    private var profilingAlreadyDone: Boolean = false

    private var isQuickScan: Boolean = false

    private var isSendButtonManuallyUnlocked = false
    private var isSendTimerBypassed = false
    private var isRetaking = false


    private var retakingPromptId: Long = -1L

    private var activeDetailSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var dismissImageOverlay: (() -> Unit)? = null

    // ── Grid adapter ──────────────────────────────────────────────────────────

    private lateinit var scanGrid: GridView
    private lateinit var viewAllButton: MaterialButton

    // Data class holding what each grid cell needs
    private data class ScanThumbData(
        val prompt: PromptEntity,
        val interpretation: CropInterpretation?,
        val uris: List<Uri>,
        val perImageInterpretations: List<CropInterpretation?> = emptyList(),
        val majorityStage: String? = null,
        val perImageStatuses: List<String> = emptyList()   // "valid" | "no_detect" | "low_conf" | "outlier"
    )

    private val scanThumbs = mutableListOf<ScanThumbData>()

    private val currentGridSlots = mutableListOf<WeekSlot>()

    private var pendingLocationUris: List<Uri>? = null
    private var pendingLocationDialog: AlertDialog? = null

    /**
     * Represents one week-slot cell in the grid.
     *
     * FILLED  → a real scan exists for this week
     * MISSED  → week has passed with no scan (locked, expandable)
     * OPEN    → current calendar week, no scan submitted yet
     * LOADING → shimmer placeholder while AI is running
     */
    private sealed class WeekSlot {
        data class Filled(val data: ScanThumbData, val weekNumber: Int) : WeekSlot()
        data class Missed(val weekNumber: Int)     : WeekSlot()
        data class Open(val weekNumber: Int)       : WeekSlot()
        object Loading                             : WeekSlot()
    }

    private sealed class DrawerRow {
        data class SectionHeader(val label: String, val count: Int) : DrawerRow()
        data class ConvItem(val conv: ConversationEntity, val lastPrompt: PromptEntity?) : DrawerRow()
    }

    private lateinit var drawerTabLayout: com.google.android.material.tabs.TabLayout
    private lateinit var drawerQuickScanListView: ListView
    private lateinit var drawerEmptyState: LinearLayout
    private lateinit var drawerEmptyIcon: TextView
    private lateinit var drawerEmptyText: TextView

    /** Animators keyed by grid position so we can cancel them on recycle. */
    private val pulseAnimators = mutableMapOf<Int, ObjectAnimator>()

    // ─────────────────────────────────────────────────────────────────────────
    // FEATURE 1 — Week / Stage constraint rules
    // Adjust these ranges to match your agronomic data.
    // ─────────────────────────────────────────────────────────────────────────
    fun expectedStageForWeek(week: Int): String = when {
        week <= 8  -> "Not Ready"
        week <= 13 -> "Near Harvest"
        else       -> "Harvest Ready"
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, conversationId) // read FIRST
        isQuickScan = intent.getBooleanExtra(EXTRA_IS_QUICK_SCAN, false)
        if (isQuickScan && conversationId != -1L) {
            markConversationAsQuickScan(conversationId)
        }
        if (!isQuickScan && conversationId != -1L) {  // now conversationId is correct
            isQuickScan = isConversationQuickScan(conversationId)
        }
        setContentView(R.layout.activity_conversations)

        checkForModelUpdatesOnStart()

        uploadNoteContainer = findViewById(R.id.uploadNoteContainer)

        messagesContainer = findViewById(R.id.messagesContainer)
        instructionText = findViewById(R.id.instructionText)
        loader = findViewById(R.id.wave_loader)

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "prompts-db")
            .addMigrations(
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10
            )
            .build()
        val conversationDao = db.conversationDao()
        com.ai.growsight.workers.PlantationVoidWorker.schedule(this)

        previewScrollView = findViewById(R.id.previewScrollView)
        uploadedImagesContainer = findViewById(R.id.uploadedImagesContainer)
        sendButton = findViewById(R.id.sendButton)
        uploadButton = findViewById(R.id.uploadButton)
        deleteButton = findViewById(R.id.deleteButton)
        cameraButton = findViewById(R.id.cameraButton)
        previewContainer = findViewById(R.id.previewContainer)
        scrollContent = findViewById(R.id.scrollContent)
        conversationTitle = findViewById(R.id.conversationTitle)
        conversationTitle.isSelected = true
        conversationListView = findViewById(R.id.conversationListView)
        drawerLayout = findViewById(R.id.drawerLayout)
        val hamburgerButton = findViewById<ImageButton>(R.id.menuButton)
        val backButton = findViewById<ImageButton>(R.id.logoButton)
        val addConversationButton = findViewById<ImageButton>(R.id.addConversationButton)
        val editTitleButton = findViewById<ImageButton>(R.id.editTitleButton)

        weatherBanner = findViewById(R.id.weatherBanner)
        weatherBannerText = findViewById(R.id.weatherBannerText)
        weatherBannerAction = findViewById(R.id.weatherBannerAction)

        yolo = ModelManager.getYoloDetector()
        cnn = ModelManager.getMaturityClassifier()
        scenarioClassifier = ModelManager.getScenarioClassifier()

        shimmerLayout = findViewById(R.id.shimmerLayout)
        shimmerLayout?.visibility = View.GONE



        drawerTabLayout        = findViewById(R.id.drawerTabLayout)
        drawerQuickScanListView = findViewById(R.id.drawerQuickScanListView)
        drawerEmptyState       = findViewById(R.id.drawerEmptyState)
        drawerEmptyIcon        = findViewById(R.id.drawerEmptyIcon)
        drawerEmptyText        = findViewById(R.id.drawerEmptyText)

        inputRow         = findViewById(R.id.inputRow)
        sendLockedRow    = findViewById(R.id.sendLockedRow)
        sendLockTimeText = findViewById(R.id.sendLockTimeText)
        sendLockProgressBar = findViewById(R.id.sendLockProgressBar)

        drawerTabLayout.addTab(drawerTabLayout.newTab().setText("Plantations"))
        drawerTabLayout.addTab(drawerTabLayout.newTab().setText("Quick Scans"))

        drawerTabLayout.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                refreshConversationList()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        // Bind grid views
        bindGridViews()

        if (yolo == null && cnn == null) Log.w("ConversationsActivity", "No models available - using fallback mode")
        else Log.d("ConversationsActivity", "Models loaded: YOLO=${yolo != null}, CNN=${cnn != null}")

        addConversationButton.setOnClickListener { showCreateConversationDialog() }
        backButton.setOnClickListener { finish() }

        hamburgerButton.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) drawerLayout.closeDrawer(GravityCompat.END)
            else drawerLayout.openDrawer(GravityCompat.END)
        }

        if (uploadedImagesContainer.childCount > 0) hideInstruction()

        val hasProcessed = intent.getBooleanExtra(EXTRA_HAS_PROCESSED_IMAGES, false)
        val incomingUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
                            withContext(Dispatchers.Main) {
                                conversationTitle.text = it.name
                                conversationTitle.isSelected = true
                            }
                            reloadConversationHistory()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        runOnUiThread { Toast.makeText(this@ConversationsActivity, "Error loading conversation", Toast.LENGTH_SHORT).show() }
                    }
                }
            }

            readProfilingFlag()

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

        readProfilingFlag()

        if (conversationId != -1L) {
            lifecycleScope.launch {
                try {
                    val conv = conversationDao.getConversationById(conversationId)
                    conv?.let {
                        withContext(Dispatchers.Main) {
                            conversationTitle.text = it.name
                            conversationTitle.isSelected = true
                        }
                        reloadConversationHistory()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread { Toast.makeText(this@ConversationsActivity, "Error loading conversation", Toast.LENGTH_SHORT).show() }
                }
            }
        }

        refreshConversationList()
        updatePreviewVisibility()

        cameraButton.setOnClickListener {
            if (conversationId == -1L) {
                Toast.makeText(this, "Please select or create a plantation first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isHistoryLoaded = true  // ← prevent onResume from wiping the grid on return
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
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
            isHistoryLoaded = true  // ← prevent onResume from wiping the grid on return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) openGallery()
                else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), READ_STORAGE_PERMISSION_REQUEST_CODE)
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) openGallery()
                else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), READ_STORAGE_PERMISSION_REQUEST_CODE)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        buildSendButtonListener()

        checkAndApplySendLock()
        setupTesting()
        requestLocationPermission()
        checkAndShowWeatherBanner()

        startSendLockChecker()
    }

    // ─── WEEK CALCULATION HELPERS ─────────────────────────────────────────

    /**
     * Returns the current crop week (1-based).
     * Priority: plantingDate → cropAgeWeeks + elapsed weeks since first prompt.
     * Returns null if there is not enough information yet.
     *
     * Must be called from a coroutine (DB access).
     */
    private suspend fun currentCropWeek(): Int? {
        // Try planting date first
        val plantingDate = db.conversationDao().getPlantingDate(conversationId)
        if (plantingDate != null && plantingDate > 0L) {
            val diffMs = System.currentTimeMillis() - plantingDate
            val week = (diffMs / (1000L * 60 * 60 * 24 * 7)).toInt() + 1
            return week.coerceAtLeast(1)
        }

        // Fall back to cropAgeWeeks + elapsed weeks since first prompt
        val baseAge = db.conversationDao().getCropAge(conversationId) ?: return null
        val history = db.promptDao().getPromptsForConversation(conversationId)
        if (history.isEmpty()) return baseAge

        return try {
            val sdf       = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
            val firstDate = sdf.parse(history.first().timestamp) ?: return baseAge
            val elapsed   = ((Date().time - firstDate.time) / (1000L * 60 * 60 * 24 * 7)).toInt()
            (baseAge + elapsed).coerceAtLeast(1)
        } catch (e: Exception) {
            baseAge
        }
    }

    /**
     * Derives the 1-based crop week a prompt belongs to.
     * Uses plantingDate when available, otherwise uses prompt.cropAgeWeeks.
     */
    private suspend fun weekNumberForPrompt(prompt: PromptEntity): Int {
        val plantingDate = db.conversationDao().getPlantingDate(conversationId)
        if (plantingDate != null && plantingDate > 0L) {
            val ts = if (prompt.timestampMs > 0L) prompt.timestampMs
            else {
                try {
                    SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
                        .parse(prompt.timestamp)?.time ?: System.currentTimeMillis()
                } catch (e: Exception) { System.currentTimeMillis() }
            }
            val diff = ts - plantingDate
            return ((diff / (1000L * 60 * 60 * 24 * 7)).toInt() + 1).coerceAtLeast(1)
        }
        return prompt.cropAgeWeeks ?: 1
    }

    /**
     * Builds the full list of WeekSlot items from scanThumbs + current week.
     * Weeks 1 … currentWeek are produced.  Any week with no matching prompt
     * is either MISSED (past) or OPEN (current).
     */
    private suspend fun buildWeekSlots(): List<WeekSlot> {
        val current = currentCropWeek() ?: return scanThumbs.mapIndexed { index, thumb -> WeekSlot.Filled(thumb, index + 1) }
        // Map week → ScanThumbData (most recent scan for that week)
        val weekToThumb = mutableMapOf<Int, ScanThumbData>()
        for (thumb in scanThumbs.toList()) {
            val w = weekNumberForPrompt(thumb.prompt)
            // Keep the most recent scan if multiple fall in the same week
            val existing = weekToThumb[w]
            if (existing == null || thumb.prompt.id > existing.prompt.id) {
                weekToThumb[w] = thumb
            }
        }

        val slots = mutableListOf<WeekSlot>()
        for (w in 1..current) {
            val thumb = weekToThumb[w]
            slots += when {
                thumb != null -> WeekSlot.Filled(thumb, w)
                w < current   -> WeekSlot.Missed(w)
                else          -> WeekSlot.Open(w)
            }
        }

        return slots
    }

    // ─── PULSE INPUT ROW ─────────────────────────────────────────────────

    /**
     * Start a slow pulsing green glow/alpha on the inputRow to signal
     * the open-week scan prompt.  Safe to call multiple times (no-op if running).
     */
    private fun pulseInputRow() {
        if (sendLockedRow.visibility == View.VISIBLE) return
        val ring = findViewById<BorderAnimView>(R.id.inputRowRing) ?: return
        val inputRow = findViewById<LinearLayout>(R.id.inputRow) ?: return

        // Always stop cleanly first before restarting
        ring.stopAnim()
        ring.visibility = View.VISIBLE

        fun startRing() {
            ring.layoutParams = (ring.layoutParams as FrameLayout.LayoutParams).also {
                it.width  = inputRow.width
                it.height = inputRow.height
            }
            ring.requestLayout()
            // doOnNextLayout fires every time, unlike doOnLayout which skips if already laid out
            ring.post {
                if (ring.width > 0 && ring.height > 0) {
                    ring.startAnim()
                }
            }
        }

        if (inputRow.width > 0 && inputRow.height > 0) {
            // Already laid out — start immediately
            startRing()
        } else {
            // Not laid out yet — wait for it
            inputRow.doOnLayout { startRing() }
        }
    }

    private fun stopPulseInputRow() {
        val ring = findViewById<BorderAnimView>(R.id.inputRowRing) ?: return
        ring.stopAnim()
        ring.visibility = View.GONE
    }

    private fun bindRetakeButton(card: View, prompt: PromptEntity, @Suppress("UNUSED_PARAMETER") isLatest: Boolean) {
        val retakeBtn = card.findViewById<MaterialButton>(R.id.retakeButton)
        val eligible  = PlantationWeekHelper.isRetakeEligible(prompt)

        retakeBtn.isEnabled = eligible
        retakeBtn.alpha     = if (eligible) 1f else 0.35f

        if (eligible) {
            retakeBtn.setOnClickListener {
                // dismiss sheet if applicable (showScanDetailSheet / showViewAllSheet only)
                AlertDialog.Builder(this)
                    .setTitle("🔄 Retake this scan?")
                    .setMessage("This will replace the current scan with a new one.")
                    .setPositiveButton("Yes, retake") { _, _ ->
                        lifecycleScope.launch {
                            // Mark hidden so it disappears from view immediately
                            db.promptDao().setHiddenForRetake(prompt.id)
                            retakingPromptId = prompt.id   // remember which one we're replacing
                            withContext(Dispatchers.Main) {
                                scanThumbs.removeAll { it.prompt.id == prompt.id }
                                refreshGrid()
                                isRetaking = true
                                showUnlocked()
                                refreshInstructionVisibility()
                                showRetakeChooser()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun showRetakeChooser() {
        isRetaking = true

        val dialogView = layoutInflater.inflate(R.layout.dialog_retake_chooser, null)

        val retakeTakePhoto     = dialogView.findViewById<LinearLayout>(R.id.retakeTakePhoto)
        val retakeChooseGallery = dialogView.findViewById<LinearLayout>(R.id.retakeChooseGallery)
        val retakeCloseButton   = dialogView.findViewById<TextView>(R.id.retakeCloseButton)
        val retakeCancelButton  = dialogView.findViewById<Button>(R.id.retakeCancelButton)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        retakeTakePhoto.setOnClickListener {
            dialog.dismiss()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                detectionCameraLauncher.launch(Intent(this, CameraDetectionActivity::class.java))
            } else {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
                )
            }
        }

        retakeChooseGallery.setOnClickListener {
            dialog.dismiss()
            openGallery()
        }

        retakeCloseButton.setOnClickListener {
            dialog.dismiss()
            isRetaking = false
        }

        retakeCancelButton.setOnClickListener {
            dialog.dismiss()
            isRetaking = false
        }

        dialog.setOnCancelListener { isRetaking = false }
        dialog.show()
    }

    // Call this in onCreate() after binding views:
    private fun bindGridViews() {
        scanGrid      = findViewById(R.id.scanGrid)
        viewAllButton = findViewById(R.id.viewAllButton)
        viewAllButton.setOnClickListener { showViewAllSheet() }
        scanGrid.numColumns = 3

        // ── Z-index fix: allow expanded cells to draw over siblings ──────────
        scanGrid.clipChildren = false
        scanGrid.clipToPadding = false
        (scanGrid.parent as? ViewGroup)?.clipChildren = false
    }

    // Call instead of / after addConversationCard to also push into grid
    private fun addToGrid(
        uris: List<Uri>,
        interpretation: CropInterpretation?,
        prompt: PromptEntity,
        perImageInterpretations: List<CropInterpretation?> = emptyList(),
        majorityStage: String? = null,
        perImageStatuses: List<String> = emptyList()
    ) {
        scanThumbs.add(
            ScanThumbData(
                prompt = prompt,
                interpretation = interpretation,
                uris = uris,
                perImageInterpretations = if (perImageInterpretations.isNotEmpty())
                    perImageInterpretations
                else
                    uris.map { interpretation },
                majorityStage = majorityStage ?: interpretation?.stage,
                perImageStatuses = perImageStatuses
            )
        )
    }

    private fun refreshGrid() {
        lifecycleScope.launch {
            val slots = buildWeekSlots()
            val hasOpenSlot = slots.any { it is WeekSlot.Open }

            withContext(Dispatchers.Main) {
                stopPulseInputRow()
                currentGridSlots.clear()
                currentGridSlots.addAll(slots)

                scanGrid.adapter = WeekSlotAdapter(slots)

                val filledCount = slots.count { it is WeekSlot.Filled }
                viewAllButton.visibility = if (filledCount > 1) View.VISIBLE else View.GONE

                refreshInstructionVisibility()

                if (hasOpenSlot) {
                    scanGrid.post { pulseInputRow() }
                }

                // ── Scroll to last slot (Open/Loading cell) ───────────────────
                val lastIndex = slots.size - 1
                if (lastIndex >= 0) {
                    scanGrid.postDelayed({ scanGrid.smoothScrollToPosition(lastIndex) }, 1000L)                }
            }
        }
    }

    private fun applyCooldownState(prompts: List<PromptEntity>) {
        if (isQuickScan) { showUnlocked(); return }
        if (PlantationWeekHelper.isSendUnlocked(prompts)) showUnlocked()
        else showLocked(prompts)
    }

    private fun refreshInstructionVisibility() {
        val gridEmpty      = scanThumbs.isEmpty() && !isProcessingImages
        val containerEmpty = uploadedImagesContainer.childCount == 0
        val shimmerVisible = shimmerLayout.visibility == View.VISIBLE

        if (gridEmpty && containerEmpty && !shimmerVisible) {
            instructionText.visibility = View.VISIBLE
        } else {
            instructionText.visibility = View.GONE
        }
    }

    private fun showLocked(prompts: List<PromptEntity>) {
        val remainingMs = PlantationWeekHelper.msUntilUnlock(prompts)
        val totalMs     = PlantationWeekHelper.msUntilUnlock(prompts) +
                (System.currentTimeMillis() - PlantationWeekHelper.mondayOf(System.currentTimeMillis()))

        stopPulseInputRow()
        inputRow.visibility         = View.GONE
        sendLockedRow.visibility    = View.VISIBLE
        uploadNoteContainer.visibility = View.GONE
        findViewById<LinearLayout>(R.id.sendLockBanner)?.visibility = View.GONE

        cooldownTimer?.cancel()
        cooldownTimer = object : CountDownTimer(remainingMs.coerceAtLeast(1000L), 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                sendLockTimeText.text = formatCooldown(millisUntilFinished)
                val weekMs   = TimeUnit.DAYS.toMillis(7)
                val elapsed  = weekMs - millisUntilFinished
                val progress = ((elapsed.toFloat() / weekMs) * 100).toInt().coerceIn(0, 100)
                sendLockProgressBar.progress = progress
            }
            override fun onFinish() {
                showUnlocked()
                if (!isQuickScan) checkAndHandleVoidState()
                refreshGrid()
            }
        }.start()

        refreshInstructionVisibility()
    }

    private fun showUnlocked() {
        cooldownTimer?.cancel()
        sendLockedRow.visibility = View.GONE
        inputRow.visibility      = View.VISIBLE
        uploadNoteContainer.visibility = View.VISIBLE
        refreshInstructionVisibility()
    }

    private fun formatCooldown(ms: Long): String {
        val days    = TimeUnit.MILLISECONDS.toDays(ms)
        val hours   = TimeUnit.MILLISECONDS.toHours(ms) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return when {
            days > 0    -> "${days}d ${hours}h remaining"
            hours > 0   -> "${hours}h ${minutes}m remaining"
            minutes > 0 -> "${minutes}m ${seconds}s remaining"
            else        -> "${seconds}s remaining"
        }
    }

    private inner class WeekSlotAdapter(
        private val slots: List<WeekSlot>
    ) : BaseAdapter() {

        override fun getCount()          = slots.size
        override fun getItem(pos: Int)   = slots[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater
                .inflate(R.layout.item_scan_thumb, parent, false)

            val pulseRing           = view.findViewById<View>(R.id.pulseRing)
            val thumbViewButton     = view.findViewById<LinearLayout>(R.id.thumbViewButton)
            val thumbImage          = view.findViewById<ImageView>(R.id.thumbImage)
            val thumbShimmer        = view.findViewById<com.facebook.shimmer.ShimmerFrameLayout>(R.id.thumbShimmer)
            val tsShimmer           = view.findViewById<com.facebook.shimmer.ShimmerFrameLayout>(R.id.thumbTimestampShimmer)
            val thumbStageBadge     = view.findViewById<TextView>(R.id.thumbStageBadge)
            val thumbTimestamp      = view.findViewById<TextView>(R.id.thumbTimestamp)
            val missedOverlay       = view.findViewById<LinearLayout>(R.id.missedOverlay)
            val thumbTimestampRow   = view.findViewById<View>(R.id.thumbTimestampRow)
            val thumbWeekLabel      = view.findViewById<TextView>(R.id.thumbWeekLabel)
            val missedWeekLabel     = view.findViewById<TextView>(R.id.missedWeekLabel)
            val openWeekOverlay     = view.findViewById<LinearLayout>(R.id.openWeekOverlay)
            val openWeekLabel       = view.findViewById<TextView>(R.id.openWeekLabel)

            pulseAnimators.remove(position)?.cancel()

            // ── Reset to clean slate ──────────────────────────────────────────
            pulseRing.visibility           = View.GONE
            pulseRing.alpha                = 1f
            thumbImage.visibility          = View.INVISIBLE
            thumbShimmer.stopShimmer()
            thumbShimmer.visibility        = View.GONE
            tsShimmer.stopShimmer()
            tsShimmer.visibility           = View.GONE
            thumbTimestampRow.visibility   = View.GONE
            thumbStageBadge.visibility     = View.GONE
            thumbWeekLabel.visibility      = View.GONE
            missedOverlay.visibility       = View.GONE
            openWeekOverlay.visibility     = View.GONE
            thumbViewButton.isEnabled      = true
            thumbViewButton.setOnClickListener(null)
            androidx.core.view.ViewCompat.setZ(view, 0f)   // ← reset elevation on recycle
            view.findViewById<View>(R.id.anomalyDot)?.visibility = View.GONE

            when (val slot = slots[position]) {

                is WeekSlot.Loading -> {
                    thumbShimmer.visibility = View.VISIBLE
                    thumbShimmer.startShimmer()
                    tsShimmer.visibility = View.VISIBLE
                    tsShimmer.startShimmer()
                    thumbViewButton.isEnabled = false
                }

                is WeekSlot.Missed -> {
                    missedWeekLabel.text     = "Week ${slot.weekNumber}"
                    missedOverlay.visibility = View.VISIBLE

                    // Always fixed height — no inline expansion
                    androidx.core.view.ViewCompat.setZ(view, 0f)

                    thumbViewButton.setOnClickListener {
                        showMissedWeekPopup(view, slot.weekNumber)
                    }
                }

                is WeekSlot.Open -> {
                    openWeekLabel.text         = "Week ${slot.weekNumber}"
                    openWeekOverlay.visibility = View.VISIBLE
                    pulseRing.visibility       = View.VISIBLE

                    val ring = pulseRing as? BorderAnimView
                    if (ring != null) {
                        ring.stopAnim()
                        pulseAnimators[position] = ObjectAnimator()
                        ring.doOnLayout { it as BorderAnimView; it.startAnim() }
                        if (ring.width > 0) ring.startAnim()
                    }

                    thumbViewButton.setOnClickListener { showRetakeChooser() }
                }

                is WeekSlot.Filled -> {
                    val data = slot.data

                    val isConflict     = data.interpretation?.scenarioLabel == "Stage Conflict"
                    val isInsufficient = data.interpretation?.scenarioLabel == "Insufficient Batch"
                    val stage          = data.interpretation?.stage

                    when {
                        isConflict -> {
                            thumbStageBadge.text = "⚠️ Inconclusive"
                            thumbStageBadge.visibility = View.VISIBLE
                            thumbStageBadge.setBackgroundResource(R.drawable.rounded_corner_yellow)
                            thumbStageBadge.setTextColor(Color.BLACK)
                        }
                        isInsufficient -> {
                            thumbStageBadge.text = "📊 Insufficient"
                            thumbStageBadge.visibility = View.VISIBLE
                            thumbStageBadge.setBackgroundResource(R.drawable.rounded_corner_gray)
                            thumbStageBadge.setTextColor(Color.WHITE)
                        }
                        stage != null -> {
                            thumbStageBadge.text = stage
                            thumbStageBadge.visibility = View.VISIBLE
                            thumbStageBadge.setBackgroundResource(when (stage) {
                                "Harvest Ready" -> R.drawable.rounded_corner_green
                                "Near Harvest"  -> R.drawable.rounded_corner_orange
                                "Not Ready"     -> R.drawable.rounded_corner_red
                                else            -> R.drawable.rounded_corner_gray
                            })
                        }
                        else -> thumbStageBadge.visibility = View.GONE
                    }

                    thumbShimmer.visibility = View.VISIBLE
                    thumbShimmer.startShimmer()
                    tsShimmer.visibility = View.VISIBLE
                    tsShimmer.startShimmer()

                    val firstUri = data.uris.firstOrNull()
                    if (firstUri != null) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val bmp = loadThumbnail(firstUri, 200)
                            withContext(Dispatchers.Main) {
                                if (data.interpretation == null ||
                                    data.interpretation.scenarioLabel == "Stage Conflict" ||
                                    data.interpretation.scenarioLabel == "Insufficient Batch") {                                    val matrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
                                    thumbImage.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
                                } else {
                                    thumbImage.clearColorFilter()
                                }
                                if (bmp != null) thumbImage.setImageBitmap(bmp)
                                else thumbImage.setImageURI(firstUri)
                                thumbShimmer.stopShimmer()
                                thumbShimmer.visibility  = View.GONE
                                thumbImage.visibility    = View.VISIBLE
                                tsShimmer.stopShimmer()
                                tsShimmer.visibility            = View.GONE
                                thumbWeekLabel.text             = "  Week ${slot.weekNumber}"
                                thumbWeekLabel.visibility       = if (isQuickScan) View.GONE else View.VISIBLE
                                thumbTimestamp.text             = data.prompt.timestamp
                                thumbTimestampRow.visibility    = View.VISIBLE
                            }
                        }
                    } else {
                        thumbShimmer.stopShimmer()
                        thumbShimmer.visibility = View.GONE
                        thumbImage.setImageResource(R.drawable.grow_sight_leaf)
                        thumbImage.visibility   = View.VISIBLE
                        tsShimmer.stopShimmer()
                        tsShimmer.visibility    = View.GONE
                        thumbTimestamp.text      = data.prompt.timestamp
                        thumbTimestamp.visibility = View.VISIBLE
                    }

                    // ── Anomaly dot ───────────────────────────────────────────────────
                    val anomalyDot = view.findViewById<View>(R.id.anomalyDot)
                    val flags = (data.interpretation?.anomalyFlags ?: emptyList())
                        .filter { it.severity in listOf("critical", "high", "medium") }
                    if (flags.isEmpty()) {
                        anomalyDot.visibility = View.GONE
                    } else {
                        val highest = flags.minByOrNull {
                            when (it.severity) { "critical" -> 0; "high" -> 1; "medium" -> 2; else -> 3 }
                        }
                        anomalyDot.visibility = View.VISIBLE
                        anomalyDot.background = ContextCompat.getDrawable(
                            this@ConversationsActivity, when (highest?.severity) {
                                "critical", "high" -> R.drawable.circle_red
                                "medium"           -> R.drawable.circle_yellow
                                else               -> R.drawable.circle_gray
                            }
                        )
                    }

                    // ── Guard: only one detail sheet open at a time ───────────
                    thumbViewButton.setOnClickListener {
                        if (activeDetailSheet?.isShowing == true) activeDetailSheet?.dismiss()
                        showScanDetailSheet(data)
                    }
                }
            }

            return view
        }
    }

    private fun showMissedWeekPopup(anchorView: View, weekNumber: Int) {
        // Build popup content
        val popupView = layoutInflater.inflate(R.layout.popup_missed_week, null)
        val weekLabel    = popupView.findViewById<TextView>(R.id.popupWeekLabel)
        val closeButton  = popupView.findViewById<TextView>(R.id.popupCloseButton)

        weekLabel.text = "Week $weekNumber"

        val popup = android.widget.PopupWindow(
            popupView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true  // focusable — dismisses on outside touch
        )
        popup.elevation = 16f
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(
            androidx.core.content.ContextCompat.getDrawable(
                this, R.drawable.rounded_white_bg
            )
        )

        closeButton.setOnClickListener  { popup.dismiss() }
        uploadButton.setOnClickListener {
            popup.dismiss()
            showRetakeChooser()
        }

        // Show above the cell if possible, otherwise below
        popupView.measure(
            android.view.View.MeasureSpec.UNSPECIFIED,
            android.view.View.MeasureSpec.UNSPECIFIED
        )
        val popupHeight = popupView.measuredHeight
        val loc = IntArray(2)
        anchorView.getLocationOnScreen(loc)
        val spaceAbove = loc[1]
        val yOffset = if (spaceAbove > popupHeight + 16) {
            -(anchorView.height + popupHeight + 8)  // show above
        } else {
            8   // show below
        }

        popup.showAsDropDown(anchorView, 0, yOffset)
    }

    // ── Detail bottom sheet (single scan card) ────────────────────────────────

    private var processingSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null

    private fun showProcessingSheet() {
        processingSheet?.dismiss()

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, R.style.CustomBottomSheetDialog
        )

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        // ── Outer container ───────────────────────────────────────────────────
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(24), dp(10), dp(32))
        }

        // ── Card (mirrors shimmerCard in layout) ──────────────────────────────
        val card = androidx.cardview.widget.CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            radius          = dp(16).toFloat()
            cardElevation   = dp(4).toFloat()
            setCardBackgroundColor(android.graphics.Color.WHITE)
        }

        // ── ShimmerFrameLayout (mirrors shimmerLayout in layout) ──────────────
        val shimmerFrame = com.facebook.shimmer.ShimmerFrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // ── Inner content — identical structure to the XML shimmer child ──────
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 125 × 125 image placeholder
        inner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(125), dp(125)).apply { bottomMargin = dp(5) }
            setBackgroundResource(R.drawable.shimmer_placeholder_rounded_8dp)
        })

        // Horizontal row: dot + label line + spacer + chip
        inner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(View(this@ConversationsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    topMargin = dp(8); rightMargin = dp(12)
                }
                setBackgroundResource(R.drawable.shimmer_placeholder_rounded)
            })
            addView(View(this@ConversationsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(110), dp(16)).apply { topMargin = dp(8) }
                setBackgroundResource(R.color.shimmer_placeholder)
            })
            addView(View(this@ConversationsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
            addView(View(this@ConversationsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(110), dp(21)).apply { topMargin = dp(3) }
                setBackgroundResource(R.drawable.shimmer_placeholder_rounded_8dp)
            })
        })

        // Divider
        inner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)
            ).apply { topMargin = dp(10); bottomMargin = dp(8) }
            setBackgroundColor(ContextCompat.getColor(this@ConversationsActivity, R.color.DCDECA))
        })

        // Short label line
        inner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(10))
            setBackgroundResource(R.color.shimmer_placeholder)
        })

        // Wide rounded bar
        inner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(35)
            ).apply { topMargin = dp(16) }
            setBackgroundResource(R.drawable.shimmer_placeholder_rounded_6dp)
        })

        // Three text lines
        inner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10)
            ).apply { topMargin = dp(12) }
            setBackgroundResource(R.color.shimmer_placeholder)
        })
        inner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(310), dp(10)).apply { topMargin = dp(6) }
            setBackgroundResource(R.color.shimmer_placeholder)
        })
        inner.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(180), dp(10)).apply { topMargin = dp(6) }
            setBackgroundResource(R.color.shimmer_placeholder)
        })
        // ─────────────────────────────────────────────────────────────────────

        shimmerFrame.addView(inner)
        card.addView(shimmerFrame)
        container.addView(card)

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(container)
        }

        sheet.setContentView(scroll)
        sheet.setCanceledOnTouchOutside(false)
        sheet.show()
        sheet.behavior.apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        shimmerFrame.startShimmer()
        processingSheet = sheet
    }

    private fun showScanDetailSheet(data: ScanThumbData) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, R.style.CustomBottomSheetDialog
        )
        activeDetailSheet = sheet                        // ← track it
        sheet.setOnDismissListener { activeDetailSheet = null }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val topPadPx = (24 * resources.displayMetrics.density).toInt()
            val sidePadPx = (0 * resources.displayMetrics.density).toInt()
            val botPadPx = (24 * resources.displayMetrics.density).toInt()
            setPadding(sidePadPx, topPadPx, sidePadPx, botPadPx)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isNestedScrollingEnabled = true
            addView(container)
        }

        // Reuse existing addConversationCard logic by inflating into container
        val card = layoutInflater.inflate(R.layout.item_conversation_card, container, false)
        // Bind the card exactly as addConversationCard does —
        // delegate to a shared bind function:
        bindConversationCard(
            card                  = card,
            prompt                = data.prompt,
            images                = data.uris,
            imageResults          = data.uris.mapIndexed { i, uri ->
                Triple(uri, data.perImageInterpretations.getOrNull(i), false)
            },
            summaryInterpretation = data.interpretation,
            notSweetPotatoCount   = data.perImageStatuses.count { it == "no_detect" },
            lowConfidenceCount    = data.perImageStatuses.count { it == "low_conf" },
            timestamp             = data.prompt.timestamp,
            weather               = null,
            cropAgeWeeks          = data.prompt.cropAgeWeeks,
            majorityStage         = data.majorityStage,
            hasConflict           = data.majorityStage != null &&
                    data.perImageInterpretations.any { it != null && it.stage != data.majorityStage },
            perImageStatuses      = data.perImageStatuses,
            isFirstScan           = scanThumbs.indexOfFirst { it.prompt.id == data.prompt.id } == 0
        )
        container.addView(card)

        // Hide retake button for quick scan
        if (!isQuickScan) {
            val eligible  = PlantationWeekHelper.isRetakeEligible(data.prompt)
            val retakeBtn = card.findViewById<MaterialButton>(R.id.retakeButton)
            retakeBtn.isEnabled = eligible
            retakeBtn.alpha     = if (eligible) 1f else 0.35f
            if (eligible) {
                retakeBtn.setOnClickListener {
                    sheet.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("🔄 Retake this scan?")
                        .setMessage("This will hide the current week's scan and let you upload new images. The scan is restored if you leave without submitting.")
                        .setPositiveButton("Yes, retake") { _, _ ->
                            lifecycleScope.launch {
                                db.promptDao().setHiddenForRetake(data.prompt.id)
                                retakingPromptId = data.prompt.id
                                withContext(Dispatchers.Main) {
                                    scanThumbs.removeAll { it.prompt.id == data.prompt.id }
                                    refreshGrid()
                                    isRetaking = true
                                    showUnlocked()
                                    refreshInstructionVisibility()
                                    showRetakeChooser()
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        } else {
            card.findViewById<MaterialButton>(R.id.retakeButton)?.visibility = View.GONE
        }

        sheet.setContentView(scroll)
        sheet.show()
        sheet.behavior.apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable   = true
            expandedOffset = 50.dpToPx(this@ConversationsActivity)
            addBottomSheetCallback(object :
                com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_DRAGGING) {
                        if (scroll.canScrollVertically(-1)) isDraggable = false
                    }
                    if (newState == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED) {
                        isDraggable = true
                    }
                }
                override fun onSlide(bottomSheet: View, slideOffset: Float) {}
            })
        }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            sheet.behavior.isDraggable = (scrollY == 0)
        }
        sheet.setCanceledOnTouchOutside(true)
    }

    // ── View All bottom sheet (scrollable list of all cards) ──────────────────

    // ── Top-level or file-level extension (outside the function) ──
    fun Int.dpToPx(context: Context): Int =
        (this * context.resources.displayMetrics.density).toInt()

    private fun reEvaluatePrompt(prompt: PromptEntity, card: View) {
        val uris = prompt.imageUris.map { Uri.parse(it) }
        if (uris.isEmpty()) {
            Toast.makeText(this, "No images found for this scan", Toast.LENGTH_SHORT).show()
            return
        }

        val reEvalBtn = card.findViewWithTag<MaterialButton>("reeval_btn")
            ?: card.findViewById<MaterialButton>(R.id.reEvaluateButton)

        // Helper to restore button to a usable state
        fun resetButton(label: String = "Retry") {
            reEvalBtn?.isEnabled = true
            reEvalBtn?.alpha = 1f
            reEvalBtn?.text = label
            isProcessingImages = false
            hideLoader()
        }

        isProcessingImages = true
        showLoader()

        weatherFetchState = WeatherFetchState.IDLE
        cachedWeather = null
        weatherFetchedAt = 0L

        val timeoutJob = lifecycleScope.launch {
            delay(8_000)
            if (weatherFetchState == WeatherFetchState.FETCHING) {
                Log.w("ReEval", "Weather timeout — proceeding without weather")
                weatherFetchState = WeatherFetchState.IDLE
                cachedWeather = null
            }
        }

        weatherFetchState = WeatherFetchState.FETCHING
        LocationWeatherManager.startWeatherFlow(
            activity = this,
            callback = object : LocationWeatherManager.WeatherFlowCallback {
                override fun onWeatherReady(weather: WeatherData?) {
                    timeoutJob.cancel()
                    cachedWeather = weather
                    weatherFetchState = if (weather != null) WeatherFetchState.DONE else WeatherFetchState.IDLE
                    weatherFetchedAt = if (weather != null) System.currentTimeMillis() else 0L
                    lifecycleScope.launch {
                        try {
                            processReEvalWithAI(uris, weather, prompt, card, reEvalBtn)  // ← pass reEvalBtn
                        } catch (e: Exception) {
                            Log.e("ReEval", "processReEvalWithAI failed: ${e.message}", e)
                            withContext(Dispatchers.Main) { resetButton("Retry") }
                        }
                    }
                }
                override fun onSkipped() {
                    timeoutJob.cancel()
                    weatherFetchState = WeatherFetchState.IDLE
                    cachedWeather = null
                    runOnUiThread { resetButton("Retry") }
                }
            }
        )
    }

    private suspend fun processReEvalWithAI(
        uris: List<Uri>,
        weather: WeatherData?,
        prompt: PromptEntity,
        card: View,
        reEvalBtn: MaterialButton?
    ) {
        val imageResults = mutableListOf<ImageResult>()

        for (uri in uris) {
            var bitmap: Bitmap? = null
            var usedCrop: Bitmap? = null
            try {
                bitmap = loadBitmapFromUri(uri)
                if (bitmap == null) {
                    imageResults.add(ImageResult(uri, null, false, false))
                    bitmap?.recycle()
                    usedCrop?.recycle()
                    continue
                }
                var detectedResult: PlantAnalysisResult? = null
                var isNotSweetPotato = false
                var isLowConfidence = false

                if (yolo != null) {
                    val detections = withContext(Dispatchers.Default) { yolo?.detect(bitmap) ?: emptyList() }
                    // ── Detection count guard (same rule as main path) ────────────
                    // <5  → hard reject, skip even rescue
                    // 5–9 → pass only if avg conf ≥ 80%
                    // ≥10 → always pass
                    val confidentDetections = detections.filter { it.score >= 0.60f }
                    val detection = confidentDetections.maxByOrNull { it.score }
                    val detectionAvgConf = if (confidentDetections.isNotEmpty())
                        confidentDetections.map { it.score }.average().toFloat() else 0f
                    val passedSparseGuard = when {
                        confidentDetections.size >= 5  -> true
                        confidentDetections.size >= 3  -> detectionAvgConf >= 0.80f
                        else                           -> false
                    }
                    val isTooFewDetections = confidentDetections.size < 3
                    // ─────────────────────────────────────────────────────────────

                    if ((detection?.score ?: 0f) >= 0.60f && detection != null && passedSparseGuard) {
                        // ── Use cluster crop first, fall back to single-box ───────────
                        usedCrop = computeClusterCrop(bitmap, detections.filter { it.score >= 0.60f })
                        if (usedCrop == null) {
                            val left  = detection.box.left.toInt().coerceIn(0, bitmap.width - 1)
                            val top   = detection.box.top.toInt().coerceIn(0, bitmap.height - 1)
                            val right = detection.box.right.toInt().coerceIn(left + 1, bitmap.width)
                            val bot   = detection.box.bottom.toInt().coerceIn(top + 1, bitmap.height)
                            val w = right - left
                            val h = bot - top
                            if (w > 10 && h > 10) usedCrop = try {
                                Bitmap.createBitmap(bitmap, left, top, w, h)
                            } catch (e: Exception) { null }
                        }
                        // ─────────────────────────────────────────────────────────────
                        val cropResult = if (usedCrop != null) {
                            withContext(Dispatchers.Default) { cnn?.classify(usedCrop) }
                        } else null
                        val bestResult = if (cropResult == null || cropResult.confidence < 0.60f) {
                            val fullResult = withContext(Dispatchers.Default) { cnn?.classify(bitmap) }
                            if (fullResult != null &&
                                (cropResult == null || fullResult.confidence > cropResult.confidence))
                                fullResult else cropResult
                        } else cropResult
                        if (bestResult != null) {
                            if (bestResult.confidence >= 0.60f) detectedResult = bestResult
                            else isLowConfidence = true
                        }
                    } else if ((detection?.score ?: 0f) >= 0.60f && detection != null && !isTooFewDetections) {
                        // ── Sparse-guard rescue (re-eval path) ───────────────────────────
                        val rescueDetections = detections.filter { it.score >= 0.60f }
                        var sparseCrop = computeClusterCrop(bitmap, rescueDetections)
                        if (sparseCrop == null) {
                            val left  = detection.box.left.toInt().coerceIn(0, bitmap.width - 1)
                            val top   = detection.box.top.toInt().coerceIn(0, bitmap.height - 1)
                            val right = detection.box.right.toInt().coerceIn(left + 1, bitmap.width)
                            val bot   = detection.box.bottom.toInt().coerceIn(top + 1, bitmap.height)
                            val w = right - left
                            val h = bot - top
                            if (w > 10 && h > 10) sparseCrop = try {
                                Bitmap.createBitmap(bitmap, left, top, w, h)
                            } catch (e: Exception) { null }
                        }

                        val cropResult = if (sparseCrop != null)
                            withContext(Dispatchers.Default) { cnn?.classify(sparseCrop) } else null
                        val bestResult = if (cropResult == null || cropResult.confidence < 0.60f) {
                            val fullResult = withContext(Dispatchers.Default) { cnn?.classify(bitmap) }
                            if (fullResult != null && (cropResult == null || fullResult.confidence > cropResult.confidence))
                                fullResult else cropResult
                        } else cropResult
                        Log.d("CONF", "Sparse-rescue (re-eval) CNN: ${bestResult?.confidence}")
                        when {
                            // High CNN confidence → sparse detection is real, accept it
                            bestResult != null && bestResult.confidence >= 0.75f ->
                                detectedResult = bestResult
                            // Medium CNN confidence → detected but unreliable, flag as low-conf
                            bestResult != null && bestResult.confidence >= 0.60f ->
                                isLowConfidence = true
                            // Low CNN confidence → truly not a sweet potato
                            else ->
                                isNotSweetPotato = true
                        }
                        sparseCrop?.recycle()
                    } else {
                        isNotSweetPotato = true
                    }
                } else if (cnn != null) {
                    val result = withContext(Dispatchers.Default) { cnn?.classify(bitmap) }
                    if (result != null) {
                        if (result.confidence >= 0.60f) detectedResult = result
                        else isLowConfidence = true
                    }
                }

                val interpretation = detectedResult?.let {
                    val sc = scenarioClassifier
                    when {
                        sc != null -> InterpretationEngine.interpret(it)
                        weather != null -> InterpretationEngine.interpretWithWeather(it, weather)
                        else -> InterpretationEngine.interpret(it)
                    }
                }
                imageResults.add(ImageResult(uri, interpretation, isNotSweetPotato, isLowConfidence))
            } finally {
                bitmap?.recycle()
                usedCrop?.recycle()
            }
        }

        val validResults = imageResults.filter { it.interpretation != null }
        val stageWeights = mutableMapOf<String, Float>()
        validResults.forEach { r ->
            val s = r.interpretation!!.stage
            stageWeights[s] = (stageWeights[s] ?: 0f) + r.interpretation.confidencePercent / 100f
        }
        val majorityStage = stageWeights.maxByOrNull { it.value }?.key
        val summaryInterpretation = validResults
            .firstOrNull { it.interpretation?.stage == majorityStage }?.interpretation

        val sc = scenarioClassifier
        val finalInterpretation: CropInterpretation? = if (summaryInterpretation != null) {
            if (sc != null && weather != null) {
                // Bug 2: compute the week from when this scan was originally taken, not the current week
                val plantingDateForReEval = db.conversationDao().getPlantingDate(conversationId)
                val reEvalCropWeek: Int? = if (plantingDateForReEval != null && plantingDateForReEval > 0L) {
                    val promptTs = PlantationWeekHelper.epochMs(prompt)
                    ((promptTs - plantingDateForReEval) / (1000L * 60 * 60 * 24 * 7))
                        .toInt().plus(1).coerceAtLeast(1)
                } else {
                    prompt.cropAgeWeeks
                }

                val historyEntries: List<ScenarioClassifier.ScanHistoryEntry> =
                    if (isQuickScan) emptyList()
                    else db.promptDao().getPromptsForConversation(conversationId)
                        .filter { p -> p.id != prompt.id }  // Bug 7: exclude the scan being re-evaluated from its own history
                        .mapNotNull { p ->
                            val s = p.diagnostic.split("|").firstOrNull()
                                ?.takeIf {  // Bug 3: exclude non-stage diagnostic labels
                                    it != "no_detection" &&
                                            it != "Stage Conflict" &&
                                            it != "Insufficient Batch" &&
                                            it.isNotBlank()
                                }
                                ?: return@mapNotNull null
                            ScenarioClassifier.ScanHistoryEntry(
                                weekNumber    = PlantationWeekHelper.weekNumberForPrompt(p, plantingDateForReEval),  // Bug 6
                                stage         = s,
                                scenarioLabel = s,
                                timestamp     = p.timestamp
                            )
                        }

                val pName = db.conversationDao().getConversationById(conversationId)?.name
                    ?: "Your plantation"

                val scenarioResult = sc.interpretWithHistory(
                    stage            = summaryInterpretation.stage,
                    confidence       = summaryInterpretation.confidencePercent / 100f,
                    cropWeek         = reEvalCropWeek,  // Bug 2: scan's original week
                    weather          = weather,
                    imageCount       = uris.size,
                    validCount       = validResults.size,
                    hasConflict      = validResults.map { it.interpretation!!.stage }.distinct().size > 1,
                    scanHistory      = historyEntries,
                    conversationName = pName,
                    isQuickScan      = isQuickScan,
                    stageBreakdown   = validResults
                        .mapNotNull { it.interpretation?.stage }
                        .groupingBy { it }
                        .eachCount()
                )
                InterpretationEngine.interpretWithScenario(
                    result = PlantAnalysisResult(
                        label      = encodeStageToLabel(summaryInterpretation.stage),
                        confidence = summaryInterpretation.confidencePercent / 100f,
                        allScores  = emptyMap()
                    ),
                    scenarioResult = scenarioResult,
                    weather        = weather
                )
            } else if (weather != null) {
                InterpretationEngine.interpretWithWeather(
                    PlantAnalysisResult(
                        encodeStageToLabel(summaryInterpretation.stage),
                        summaryInterpretation.confidencePercent / 100f,
                        emptyMap()
                    ), weather
                )
            } else summaryInterpretation
        } else null

        // Build updated diagnostic string
        val updatedDiagnostic = if (finalInterpretation != null) {
            val weatherPart = finalInterpretation.weatherSummary?.replace("|", "~") ?: ""
            val weatherRecs = finalInterpretation.recommendations
                .filter { it.startsWith("🌡️") || it.startsWith("💧") || it.startsWith("🌧️") || it.startsWith("🌦️") || it.startsWith("☀️") }
                .joinToString("^").replace("|", "~")
            val narrative = finalInterpretation.interpretationSummary?.replace("|", "~pipe~") ?: ""
            val bullets   = finalInterpretation.recommendations.joinToString("^").replace("|", "~pipe~")
            "${finalInterpretation.stage}|${finalInterpretation.confidencePercent}|${finalInterpretation.harvestTime}|$weatherPart|$weatherRecs|$narrative|$bullets"
        } else {
            prompt.diagnostic
        }

        // Update DB row in place
        db.promptDao().updateDiagnostic(prompt.id, updatedDiagnostic)

        // Update scanThumbs so grid reflects new interpretation
        val newInterpretation = parseDiagnosticString(updatedDiagnostic)
        val thumbIndex = scanThumbs.indexOfFirst { it.prompt.id == prompt.id }
        if (thumbIndex >= 0) {
            scanThumbs[thumbIndex] = scanThumbs[thumbIndex].copy(
                interpretation = newInterpretation,
                majorityStage  = newInterpretation?.stage
            )
        }

        val updatedPrompt = prompt.copy(diagnostic = updatedDiagnostic)

        withContext(Dispatchers.Main) {
            hideLoader()
            isProcessingImages = false

            if (weather == null) {
                reEvalBtn?.isEnabled = true
                reEvalBtn?.alpha = 1f
                reEvalBtn?.text = "Retry"
                Toast.makeText(
                    this@ConversationsActivity,
                    "⚠️ Could not get location — tap Retry to try again",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // ── Rebind the card in-place instead of reloading history ────────
                bindConversationCard(
                    card                  = card,
                    prompt                = updatedPrompt,
                    images                = uris,
                    imageResults          = uris.map { Triple(it, newInterpretation, false) },
                    summaryInterpretation = newInterpretation,
                    notSweetPotatoCount   = 0,
                    lowConfidenceCount    = 0,
                    timestamp             = prompt.timestamp,
                    weather               = weather,
                    cropAgeWeeks          = prompt.cropAgeWeeks,
                    isFirstScan           = scanThumbs.indexOfFirst { it.prompt.id == prompt.id } == 0
                )

                // ── Also refresh the grid thumbnail badge ─────────────────────────
                refreshGrid()

                Toast.makeText(
                    this@ConversationsActivity,
                    "✅ Re-analyzed with weather data",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showViewAllSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, R.style.CustomBottomSheetDialog
        )
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            isNestedScrollingEnabled = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, 32)
        }
        scroll.addView(container)

        lifecycleScope.launch {
            // getPromptsForConversation already excludes isHiddenForRetake=1 rows
            val cleanPrompts = db.promptDao()
                .getPromptsForConversation(conversationId)
                .sortedBy { it.id }

            withContext(Dispatchers.Main) {
                if (cleanPrompts.isEmpty()) {
                    sheet.setContentView(scroll)
                    sheet.show()
                    return@withContext
                }

                cleanPrompts.forEachIndexed { index, prompt ->
                    val isLast = index == cleanPrompts.size - 1
                    val uris = prompt.imageUris.map { Uri.parse(it) }
                    val interpretation   = parseDiagnosticString(prompt.diagnostic)
                    val perImageStatuses = parseDiagnosticPerImageStatuses(prompt.diagnostic) // ← NEW
                    val card = layoutInflater.inflate(R.layout.item_conversation_card, container, false)
                    bindConversationCard(
                        card = card,
                        prompt = if (isLast) prompt else null,
                        images = uris,
                        // Nullify interpretation for bad images so grayscale logic fires correctly
                        imageResults = uris.mapIndexed { i, uri ->
                            val st = perImageStatuses.getOrNull(i)
                            Triple(
                                uri,
                                if (st == "no_detect" || st == "low_conf") null else interpretation,
                                st == "no_detect"
                            )
                        },
                        summaryInterpretation = interpretation,
                        notSweetPotatoCount = 0,
                        lowConfidenceCount = perImageStatuses.count { it == "low_conf" },
                        timestamp = prompt.timestamp,
                        weather = null,
                        cropAgeWeeks = prompt.cropAgeWeeks,
                        majorityStage = interpretation?.stage,
                        perImageStatuses = perImageStatuses,
                        isFirstScan      = index == 0
                    )
                    container.addView(card)

                    if (!isQuickScan) {
                        val eligible = PlantationWeekHelper.isRetakeEligible(prompt)
                        val retakeBtn = card.findViewById<MaterialButton>(R.id.retakeButton)
                        retakeBtn.isEnabled = eligible
                        retakeBtn.alpha = if (eligible) 1f else 0.35f
                        if (eligible) {
                            retakeBtn.setOnClickListener {
                                sheet.dismiss()
                                AlertDialog.Builder(this@ConversationsActivity)
                                    .setTitle("🔄 Retake this scan?")
                                    .setMessage("This will replace the current scan with a new one.")
                                    .setPositiveButton("Yes, retake") { _, _ ->
                                        lifecycleScope.launch {
                                            db.promptDao().setHiddenForRetake(prompt.id)
                                            retakingPromptId = prompt.id
                                            withContext(Dispatchers.Main) {
                                                scanThumbs.removeAll { it.prompt.id == prompt.id }
                                                refreshGrid()
                                                isRetaking = true
                                                showUnlocked()
                                                refreshInstructionVisibility()
                                                showRetakeChooser()
                                            }
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    } else {
                        card.findViewById<MaterialButton>(R.id.retakeButton)?.visibility = View.GONE
                    }
                }

                sheet.setContentView(scroll)
                sheet.show()
                sheet.behavior.apply {
                    state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                    isDraggable = true
                    expandedOffset = 50.dpToPx(this@ConversationsActivity)
                    addBottomSheetCallback(object : com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
                        override fun onStateChanged(bottomSheet: View, newState: Int) {
                            if (newState == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_DRAGGING) {
                                if (scroll.canScrollVertically(-1)) isDraggable = false
                            }
                            if (newState == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED) {
                                isDraggable = true
                            }
                        }
                        override fun onSlide(bottomSheet: View, slideOffset: Float) {}
                    })
                }
                scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    sheet.behavior.isDraggable = (scrollY == 0)
                }
                sheet.setCanceledOnTouchOutside(true)
            }
        }
    }

    // Helper to bind an existing card view (for bottom sheets)
    private fun bindConversationCard(
        card: View,
        prompt: PromptEntity?,
        images: List<Uri>,
        imageResults: List<Triple<Uri, CropInterpretation?, Boolean>>,
        summaryInterpretation: CropInterpretation?,
        notSweetPotatoCount: Int,
        lowConfidenceCount: Int,
        timestamp: String,
        weather: WeatherData?,
        cropAgeWeeks: Int?,
        majorityStage: String? = null,
        hasConflict: Boolean = false,
        perImageStatuses: List<String> = emptyList(),
        isFirstScan: Boolean = false
    ) {
        val mainImageRow = card.findViewById<LinearLayout>(R.id.cardImageRow)
        val stageLabel = card.findViewById<TextView>(R.id.stageLabel)
        val stageColorDot = card.findViewById<View>(R.id.stageColorDot)
        val confidenceChip = card.findViewById<TextView>(R.id.confidenceChip)
        val harvestTime = card.findViewById<TextView>(R.id.harvestTime)
        val harvestTimeTitle = card.findViewById<TextView>(R.id.harvestTimeTitle)
        val recommendationContainer = card.findViewById<LinearLayout>(R.id.recommendationContainer)
        val interpretationText = card.findViewById<TextView>(R.id.interpretationSummaryText)
        val cardTimestamp = card.findViewById<TextView>(R.id.cardTimestamp)
        val lowConfidenceWarning = card.findViewById<TextView>(R.id.lowConfidenceWarning)
        val scenarioBadge = card.findViewById<TextView>(R.id.scenarioBadge)
        val toggleSummary = card.findViewById<LinearLayout>(R.id.toggleSummary)
        val toggleFullDetail = card.findViewById<LinearLayout>(R.id.toggleFullDetail)
        val weatherStrip = card.findViewById<View>(R.id.weatherStrip)
        val weatherTemp = card.findViewById<TextView>(R.id.weatherTemp)
        val weatherHumidity = card.findViewById<TextView>(R.id.weatherHumidity)
        val weatherPrecip = card.findViewById<TextView>(R.id.weatherPrecip)

        cardTimestamp.text = timestamp

        // ── Anomaly banner ─────────────────────────────────────────────────────
        val anomalyBanner = card.findViewWithTag<LinearLayout>("anomalyBanner")
        val anomalyFlags = summaryInterpretation?.anomalyFlags ?: emptyList()
        val bannerFlags  = anomalyFlags.filter { it.severity in listOf("critical", "high", "medium") }
        if (anomalyBanner != null && bannerFlags.isNotEmpty()) {
            val highest = bannerFlags.minByOrNull {
                when (it.severity) { "critical" -> 0; "high" -> 1; "medium" -> 2; else -> 3 }
            }
            val (bgHex, textHex, _) = when (highest?.severity) {
                "critical" -> Triple("#B71C1C", "#FFFFFF", "🔴")
                "high"     -> Triple("#BF360C", "#FFFFFF", "🟠")
                "medium"   -> Triple("#F57F17", "#212121", "🟡")
                else       -> Triple("#1565C0", "#FFFFFF", "🔵")
            }
            val lightBgHex = when (highest?.severity) {
                "critical" -> "#FCE4EC"
                "high"     -> "#FBE9E7"
                "medium"   -> "#FFF3E0"
                else       -> "#E3F2FD"
            }
            val d = resources.displayMetrics.density
            val cornerR = 10f * d
            val solidBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor(bgHex))
                cornerRadius = cornerR
            }
            anomalyBanner.background = solidBg
            anomalyBanner.visibility = View.VISIBLE
            anomalyBanner.removeAllViews()
            val count    = bannerFlags.size
            val sevLabel = (highest?.severity ?: "medium").replaceFirstChar { it.uppercase() }
            val badgeSize = (24 * d).toInt()

            val countCircle = TextView(this).apply {
                text = count.toString()
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor(bgHex))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize).also {
                    it.marginEnd = (10 * d).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.WHITE)
                }
            }
            val label = TextView(this).apply {
                text = "$sevLabel  ·  $count ${if (count == 1) "issue" else "issues"} detected"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor(textHex))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sevPill = TextView(this).apply {
                text = (highest?.severity ?: "medium").uppercase()
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor(textHex))
                setPadding((10 * d).toInt(), (3 * d).toInt(), (10 * d).toInt(), (3 * d).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke((1.5f * d).toInt(), android.graphics.Color.parseColor(textHex))
                    cornerRadius = 20f * d
                }
            }
            anomalyBanner.addView(countCircle)
            anomalyBanner.addView(label)
            anomalyBanner.addView(sevPill)
        } else {
            anomalyBanner?.visibility = View.GONE
        }

        // Critical/High anomalies suppress and reorder normal output
        val isFocusMode = !isQuickScan && bannerFlags.any { it.severity in listOf("critical", "high") }

        // Clear existing images
        mainImageRow.removeAllViews()

        var outlierCount = 0

        imageResults.forEachIndexed { index, (uri, interpretation, isNoDetection) ->
            val wrapper = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(4, 4, 4, 4) }
            }
            val sizeInDp = 125
            val sizeInPx = (sizeInDp * resources.displayMetrics.density).toInt()

            // Resolve per-image status — prefer stored status, fall back to live flags
            val status = perImageStatuses.getOrNull(index) ?: when {
                isNoDetection                                                            -> "no_detect"
                interpretation == null                                                   -> "low_conf"
                majorityStage != null && interpretation.stage != majorityStage           -> "outlier"
                else                                                                     -> "valid"
            }
            val isOutlier = status != "valid"
            if (isOutlier) outlierCount++

            val img = ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(sizeInPx, sizeInPx)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.circle_gray)

                val radiusPx = (5 * resources.displayMetrics.density)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
                clipToOutline = true

                if (isOutlier) {
                    val matrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
                    colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
                }

                setOnClickListener { showImageModal(uri) }
            }
            wrapper.addView(img)

            // Centered icon overlay per failure reason
            if (isOutlier) {
                val iconRes = when (status) {
                    "no_detect" -> R.drawable.ic_no_detect
                    "low_conf"  -> R.drawable.ic_low_conf
                    "outlier"   -> R.drawable.ic_stage_outlier
                    else        -> R.drawable.ic_no_detect
                }
                val bgColor = when (status) {
                    "no_detect" -> android.graphics.Color.argb(180, 183, 28, 28)   // dark red
                    "low_conf"  -> android.graphics.Color.argb(180, 55, 71, 79)    // dark slate
                    "outlier"   -> android.graphics.Color.argb(180, 230, 81, 0)    // deep orange
                    else        -> android.graphics.Color.argb(180, 33, 33, 33)
                }
                val iconSizePx = (32 * resources.displayMetrics.density).toInt()
                val badgeIcon = ImageView(this).apply {
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(
                        (6 * resources.displayMetrics.density).toInt(),
                        (6 * resources.displayMetrics.density).toInt(),
                        (6 * resources.displayMetrics.density).toInt(),
                        (6 * resources.displayMetrics.density).toInt()
                    )
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(bgColor)
                    }
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        iconSizePx, iconSizePx
                    ).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                }
                wrapper.addView(badgeIcon)
            }

            mainImageRow.addView(wrapper)
            lifecycleScope.launch(Dispatchers.IO) {
                val thumbnail = loadThumbnail(uri, 300)
                withContext(Dispatchers.Main) {
                    if (thumbnail != null) img.setImageBitmap(thumbnail) else img.setImageURI(uri)
                }
            }
        }

        // ── Conflict / outlier notice ─────────────────────────────────────────
        val conflictNotice = card.findViewById<TextView>(R.id.conflictNoticeText)
        if (conflictNotice != null) {
            if (outlierCount > 0 && images.size > 1) {
                // Count each failure reason for a specific message
                val noDetectCount = imageResults.indices.count {
                    (perImageStatuses.getOrNull(it) ?: "") == "no_detect"
                }
                val lowConfCount = imageResults.indices.count {
                    (perImageStatuses.getOrNull(it) ?: "") == "low_conf"
                }
                val stageConflictCount = imageResults.indices.count {
                    (perImageStatuses.getOrNull(it) ?: "") == "outlier"
                }

                val parts = mutableListOf<String>()
                if (noDetectCount > 0)
                    parts += if (noDetectCount == 1) "1 image had no sweet potato detected (❌)"
                    else "$noDetectCount images had no sweet potato detected (❌)"
                if (lowConfCount > 0)
                    parts += if (lowConfCount == 1) "1 image was too unclear to classify (🌫️)"
                    else "$lowConfCount images were too unclear to classify (🌫️)"
                if (stageConflictCount > 0)
                    parts += if (stageConflictCount == 1) "1 image showed a different maturity stage (⚠️)"
                    else "$stageConflictCount images showed a different maturity stage (⚠️)"

                val reason = when (parts.size) {
                    0    -> "$outlierCount of ${images.size} images showed unclear results"
                    1    -> parts[0]
                    2    -> "${parts[0]} and ${parts[1]}"
                    else -> "${parts.dropLast(1).joinToString(", ")}, and ${parts.last()}"
                }
                val grayLabel = if (outlierCount == 1) "Grayscale image" else "Grayscale images"
                conflictNotice.text = "$grayLabel: $reason. Submit clearer images for a more accurate result."
                conflictNotice.visibility = View.VISIBLE
            } else {
                conflictNotice.visibility = View.GONE
            }
        }

        if (summaryInterpretation == null) {
            scenarioBadge.visibility = View.GONE
            confidenceChip.visibility = View.GONE
            harvestTimeTitle.visibility = View.GONE
            harvestTime.visibility = View.GONE
            lowConfidenceWarning.visibility = View.GONE
            weatherStrip.visibility = View.GONE
            card.findViewById<LinearLayout>(R.id.viewModeToggleBar).visibility = View.GONE

            val noDetectCount = maxOf(notSweetPotatoCount, perImageStatuses.count { it == "no_detect" })
            val lowConfCount  = maxOf(lowConfidenceCount,  perImageStatuses.count { it == "low_conf" })
            val singular      = images.size == 1
            val onlyLowConf   = lowConfCount > 0 && noDetectCount == 0
            val onlyNoDetect  = noDetectCount > 0 && lowConfCount == 0

            stageLabel.text = if (onlyLowConf) "Images too unclear to classify" else "No sweet potato detected"
            stageLabel.textSize = 20f
            stageColorDot.setBackgroundResource(R.drawable.circle_gray)

            interpretationText.text = when {
                onlyLowConf ->
                    if (singular) "The plant was detected but the image was too unclear to determine the maturity stage. Please retake in natural daylight with the leaf canopy filling the frame."
                    else "The plants were detected but the images were too unclear to determine the maturity stage. Please retake in natural daylight with the leaf canopy filling the frame."
                onlyNoDetect ->
                    if (singular) "No sweet potato plant was found in the submitted image. Make sure the leaf canopy clearly fills the frame."
                    else "No sweet potato plant was found in the submitted images. Make sure the leaf canopy clearly fills the frame."
                lowConfCount > 0 ->
                    "Some images had no plant detected and others were too unclear to classify. Please retake with clearer, well-lit photos of the leaf canopy."
                else ->
                    if (singular) "The submitted image could not be classified. Please try again with a clearer photo."
                    else "The submitted images could not be classified. Please try again with clearer photos."
            }
            interpretationText.visibility = View.VISIBLE
            recommendationContainer.removeAllViews()
            when {
                onlyLowConf -> {
                    addRecommendationBullet(recommendationContainer, "📷 Retake in natural daylight — avoid shadows and blur")
                    addRecommendationBullet(recommendationContainer, "🌿 Move closer so the leaf canopy fills most of the frame")
                    addRecommendationBullet(recommendationContainer, "🔍 Avoid heavy shade, overexposure, or motion blur")
                }
                else -> {
                    addRecommendationBullet(recommendationContainer, "🌿 Ensure sweet potato leaves are clearly visible in the frame")
                    addRecommendationBullet(recommendationContainer, "📐 Move the camera closer — the plant should fill the frame")
                    addRecommendationBullet(recommendationContainer, "📷 Use natural daylight and keep the camera steady")
                }
            }

            // Hide retake button for quick scan
            if (!isQuickScan && prompt != null) {
                val retakeBtn = card.findViewById<MaterialButton>(R.id.retakeButton)
                retakeBtn?.isEnabled = true
                retakeBtn?.alpha = 1f
                retakeBtn?.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("🔄 Retake this scan?")
                        .setMessage("This will remove the current scan and let you upload new images.")
                        .setPositiveButton("Yes, retake") { _, _ ->
                            lifecycleScope.launch {
                                db.promptDao().setHiddenForRetake(prompt.id)  // ← correct here
                                retakingPromptId = prompt.id
                                withContext(Dispatchers.Main) {
                                    scanThumbs.removeAll { it.prompt.id == prompt.id }
                                    refreshGrid()
                                    isRetaking = true
                                    showUnlocked()
                                    refreshInstructionVisibility()
                                    showRetakeChooser()
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } else if (!isQuickScan) {
                card.findViewById<MaterialButton>(R.id.retakeButton)
                    ?.let { it.isEnabled = false; it.alpha = 0.35f }
            }

            return
        }

        // ── Insufficient Batch — too few valid images in the submitted batch ─────────
        if (summaryInterpretation.scenarioLabel == "Insufficient Batch") {
            scenarioBadge.text = "📊 Insufficient Batch"
            scenarioBadge.visibility = View.VISIBLE
            scenarioBadge.setBackgroundResource(R.drawable.rounded_corner_gray)
            scenarioBadge.setTextColor(Color.WHITE)

            stageLabel.text     = "📊 Inconclusive Result"
            stageLabel.textSize = 18f
            stageColorDot.visibility = View.GONE

            confidenceChip.visibility       = View.GONE
            lowConfidenceWarning.visibility = View.GONE
            weatherStrip.visibility         = View.GONE
            card.findViewById<LinearLayout>(R.id.viewModeToggleBar).visibility = View.GONE

            harvestTimeTitle.visibility = View.GONE
            harvestTime.visibility      = View.GONE

            interpretationText.text       = summaryInterpretation.interpretationSummary
                ?: "Too few valid images to make an assessment."
            interpretationText.visibility = View.VISIBLE

            recommendationContainer.removeAllViews()
            addSectionHeader(recommendationContainer, "📋 What does this mean:")
            addRecommendationBullet(recommendationContainer, buildWhatItMeansLine("Insufficient Batch", perImageStatuses, images.size))
            summaryInterpretation.recommendations.forEachIndexed { i, rec ->
                if (i != 0 && i != 2) addRecommendationBullet(recommendationContainer, rec)
            }
            recommendationContainer.visibility = View.VISIBLE

            // Prominent retake button — same style as Stage Conflict
            val retakeBtn = card.findViewById<MaterialButton>(R.id.retakeButton)
            if (!isQuickScan && prompt != null) {
                val eligible = PlantationWeekHelper.isRetakeEligible(prompt)
                retakeBtn?.isEnabled = eligible
                retakeBtn?.alpha     = if (eligible) 1f else 0.35f
                if (eligible) {
                    retakeBtn?.apply {
                        text = "📸 Retake Scan"
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 15f
                        insetTop = 0
                        insetBottom = 0
                        minimumHeight = 0
                        val shape = com.google.android.material.shape.ShapeAppearanceModel.builder()
                            .setAllCornerSizes(24f * resources.displayMetrics.density)
                            .build()
                        background = com.google.android.material.shape.MaterialShapeDrawable(shape).apply {
                            fillColor = android.content.res.ColorStateList.valueOf(
                                android.graphics.Color.parseColor("#37474F") // dark slate — distinct from Stage Conflict orange
                            )
                        }
                        setPadding(
                            (24 * resources.displayMetrics.density).toInt(),
                            (14 * resources.displayMetrics.density).toInt(),
                            (24 * resources.displayMetrics.density).toInt(),
                            (14 * resources.displayMetrics.density).toInt()
                        )
                        val lp = layoutParams as? ViewGroup.MarginLayoutParams
                        lp?.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        lp?.setMargins(
                            (16 * resources.displayMetrics.density).toInt(),
                            (16 * resources.displayMetrics.density).toInt(),
                            (16 * resources.displayMetrics.density).toInt(),
                            (16 * resources.displayMetrics.density).toInt()
                        )
                        layoutParams = lp
                        setOnClickListener {
                            AlertDialog.Builder(this@ConversationsActivity)
                                .setTitle("📸 Retake this scan?")
                                .setMessage("Too few valid images were detected. Submit new images with clear sweet potato plants.")
                                .setPositiveButton("Yes, retake") { _, _ ->
                                    lifecycleScope.launch {
                                        db.promptDao().setHiddenForRetake(prompt.id)
                                        retakingPromptId = prompt.id
                                        withContext(Dispatchers.Main) {
                                            scanThumbs.removeAll { it.prompt.id == prompt.id }
                                            refreshGrid()
                                            isRetaking = true
                                            showUnlocked()
                                            refreshInstructionVisibility()
                                            showRetakeChooser()
                                        }
                                    }
                                }
                                .setNegativeButton("Cancel", null).show()
                        }
                    }
                }
            } else {
                retakeBtn?.visibility = View.GONE
            }

            val grayMatrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
            val grayFilter = android.graphics.ColorMatrixColorFilter(grayMatrix)
            for (i in 0 until mainImageRow.childCount) {
                val wrapper = mainImageRow.getChildAt(i) as? android.widget.FrameLayout ?: continue
                for (j in 0 until wrapper.childCount) {
                    (wrapper.getChildAt(j) as? ImageView)?.colorFilter = grayFilter
                }
            }

            card.findViewById<MaterialButton>(R.id.reEvaluateButton)?.visibility = View.GONE
            return
        }

        // ── Stage conflict — inconclusive result (week/stage mismatch) ──────────────
        if (summaryInterpretation.scenarioLabel == "Stage Conflict") {
            // Badge
            scenarioBadge.text = "⚠️ Stage Conflict"
            scenarioBadge.visibility = View.VISIBLE
            scenarioBadge.setBackgroundResource(R.drawable.rounded_corner_yellow)
            scenarioBadge.setTextColor(Color.BLACK)

            // Stage row
            stageLabel.text     = "⚠️ Inconclusive Result"
            stageLabel.textSize = 18f
            //stageColorDot.setBackgroundResource(R.drawable.circle_yellow)
            stageColorDot.visibility      = View.GONE

            // Hide chip / warning / weather / toggle bar
            confidenceChip.visibility     = View.GONE
            lowConfidenceWarning.visibility = View.GONE
            weatherStrip.visibility       = View.GONE
            card.findViewById<LinearLayout>(R.id.viewModeToggleBar).visibility = View.GONE

            // Harvest time
            harvestTimeTitle.visibility = View.GONE
            harvestTime.visibility      = View.GONE

            // Narrative
            interpretationText.text       = summaryInterpretation.interpretationSummary
                ?: "Result could not be determined. Please verify your crop manually."
            interpretationText.visibility = View.VISIBLE

            // Recommendations always visible (no toggle)
            recommendationContainer.removeAllViews()
            addSectionHeader(recommendationContainer, "📋 What does this mean:")
            if (images.size > 1) {
                addRecommendationBullet(recommendationContainer, buildWhatItMeansLine("Stage Conflict", perImageStatuses, images.size))
            }
            summaryInterpretation.recommendations.forEachIndexed { i, rec ->
                if (i != 0 && i != 2) addRecommendationBullet(recommendationContainer, rec)
            }
            recommendationContainer.visibility = View.VISIBLE

            // Retake button — elevated prominence for Stage Conflict
            val retakeBtn = card.findViewById<MaterialButton>(R.id.retakeButton)
            if (!isQuickScan && prompt != null) {
                val eligible = PlantationWeekHelper.isRetakeEligible(prompt)
                retakeBtn?.isEnabled = eligible
                retakeBtn?.alpha     = if (eligible) 1f else 0.35f

                if (eligible) {
                    retakeBtn?.apply {
                        text = "📸 Retake Scan"
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 15f

                        // Zero out MaterialButton's internal insets that cause text clipping
                        insetTop = 0
                        insetBottom = 0
                        minimumHeight = 0

                        // Rounded orange background
                        val shape = com.google.android.material.shape.ShapeAppearanceModel.builder()
                            .setAllCornerSizes(24f * resources.displayMetrics.density)
                            .build()
                        val bgDrawable = com.google.android.material.shape.MaterialShapeDrawable(shape).apply {
                            fillColor = android.content.res.ColorStateList.valueOf(
                                android.graphics.Color.parseColor("#E65100")
                            )
                        }
                        background = bgDrawable

                        setPadding(
                            (24 * resources.displayMetrics.density).toInt(),
                            (14 * resources.displayMetrics.density).toInt(),
                            (24 * resources.displayMetrics.density).toInt(),
                            (14 * resources.displayMetrics.density).toInt()
                        )

                        val lp = layoutParams as? ViewGroup.MarginLayoutParams
                        lp?.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        lp?.setMargins(
                            (16 * resources.displayMetrics.density).toInt(),
                            (16 * resources.displayMetrics.density).toInt(),
                            (16 * resources.displayMetrics.density).toInt(),
                            (16 * resources.displayMetrics.density).toInt()
                        )
                        layoutParams = lp

                        // Pulse animation
                        val scaleX = ObjectAnimator.ofFloat(this, "scaleX", 1f, 1.04f, 1f)
                        val scaleY = ObjectAnimator.ofFloat(this, "scaleY", 1f, 1.04f, 1f)
                        scaleX.duration = 900
                        scaleY.duration = 900
                        scaleX.repeatCount = 3
                        scaleY.repeatCount = 3
                        scaleX.interpolator = AccelerateDecelerateInterpolator()
                        scaleY.interpolator = AccelerateDecelerateInterpolator()
                        android.animation.AnimatorSet().apply {
                            playTogether(scaleX, scaleY)
                            start()
                        }

                        setOnClickListener {
                            AlertDialog.Builder(this@ConversationsActivity)
                                .setTitle("🔄 Retake this scan?")
                                .setMessage("This will replace the current scan so you can submit new images.")
                                .setPositiveButton("Yes, retake") { _, _ ->
                                    lifecycleScope.launch {
                                        db.promptDao().setHiddenForRetake(prompt.id)
                                        retakingPromptId = prompt.id
                                        withContext(Dispatchers.Main) {
                                            scanThumbs.removeAll { it.prompt.id == prompt.id }
                                            refreshGrid()
                                            isRetaking = true
                                            showUnlocked()
                                            refreshInstructionVisibility()
                                            showRetakeChooser()
                                        }
                                    }
                                }
                                .setNegativeButton("Cancel", null).show()
                        }
                    }
                }

            } else {
                retakeBtn?.visibility = View.GONE
            }

            // ── Grayscale all images in the row ──────────────────────────────────────
            val grayMatrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
            val grayFilter = android.graphics.ColorMatrixColorFilter(grayMatrix)
            for (i in 0 until mainImageRow.childCount) {
                val wrapper = mainImageRow.getChildAt(i) as? android.widget.FrameLayout ?: continue
                for (j in 0 until wrapper.childCount) {
                    (wrapper.getChildAt(j) as? ImageView)?.colorFilter = grayFilter
                }
            }

            // Re-evaluate button — not applicable for a blocked result
            card.findViewById<MaterialButton>(R.id.reEvaluateButton)?.visibility = View.GONE

            return
        }
        // ─────────────────────────────────────────────────────────────────────────────

        if (!summaryInterpretation.scenarioLabel.isNullOrBlank()) {
            scenarioBadge.text = summaryInterpretation.scenarioLabel.replace("_", " ")
            scenarioBadge.visibility = View.VISIBLE
        } else {
            scenarioBadge.visibility = View.GONE
        }

        stageLabel.text = "${summaryInterpretation.stageEmoji} ${summaryInterpretation.stage}"
        stageLabel.textSize = 20f
        stageColorDot.setBackgroundResource(when (summaryInterpretation.stageColor) {
            "green" -> R.drawable.circle_green
            "yellow" -> R.drawable.circle_yellow
            "red" -> R.drawable.circle_red
            else -> R.drawable.circle_gray
        })
        confidenceChip.visibility = View.VISIBLE
        confidenceChip.text = "${summaryInterpretation.confidencePercent}% confidence"
        confidenceChip.textSize = 14f
        val (chipBg, chipText) = when {
            summaryInterpretation.confidencePercent >= 75 ->
                Pair(ContextCompat.getDrawable(this, R.drawable.rounded_corner_green_conf), Color.WHITE)
            summaryInterpretation.confidencePercent >= 65 ->
                Pair(ContextCompat.getDrawable(this, R.drawable.rounded_corner_orange_conf), Color.WHITE)
            else ->
                Pair(ContextCompat.getDrawable(this, R.drawable.rounded_corner_red_conf), Color.WHITE)
        }
        confidenceChip.background = chipBg
        confidenceChip.setTextColor(chipText)
        val hasLowConfFlag = summaryInterpretation.anomalyFlags.any { it.badgeLabel == "Low Confidence" }
        lowConfidenceWarning.visibility = if (summaryInterpretation.lowConfidenceWarning && !hasLowConfFlag) {
            lowConfidenceWarning.textSize = 14f
            View.VISIBLE
        } else View.GONE

        val htRaw = summaryInterpretation.harvestTime
        val htVisible = InterpretationEngine.isHarvestTimeDisplayable(htRaw) && !isFocusMode
        harvestTimeTitle.visibility = if (htVisible) View.VISIBLE else View.GONE
        harvestTime.visibility      = if (htVisible) View.VISIBLE else View.GONE
        if (htVisible) {
            // Quick scan has no elapsed-time context — "remaining" is misleading there
            val htDisplay = if (isQuickScan)
                htRaw.replace(" remaining", " to harvest from this stage", ignoreCase = true)
            else htRaw
            harvestTimeTitle.text = if (isQuickScan) "⏱️ Typical Time to Harvest"
            else             "🗓️ Estimated Harvest Time"
            harvestTime.text = "→ $htDisplay"
            harvestTime.setTextColor(ContextCompat.getColor(this, R.color.black))
            harvestTime.textSize = 16f
        }

        val narrative = summaryInterpretation.interpretationSummary
        if (!narrative.isNullOrBlank()) {
            val progressMarker = "🌱 Growth progress:"
            val progressIdx = narrative.indexOf(progressMarker)
            if (progressIdx >= 0) {
                val spannable = android.text.SpannableStringBuilder(narrative)
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#2E7D32")),
                    progressIdx, narrative.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    progressIdx, narrative.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                interpretationText.text = spannable
            } else {
                interpretationText.text = narrative
            }
            interpretationText.visibility = View.VISIBLE
        } else {
            interpretationText.text = "No summary interpretation available."
            interpretationText.visibility = View.VISIBLE
        }
        recommendationContainer.removeAllViews()

        if (images.size > 1) {
            val validCount = imageResults.count { it.second != null }
            fun imgW(n: Int) = if (n == 1) "image" else "images"
            addSectionHeader(recommendationContainer, "📊 Analysis Summary")
            addRecommendationBullet(recommendationContainer,
                "$validCount of ${images.size} ${imgW(images.size)} contributed to this result")
            if (notSweetPotatoCount > 0)
                addRecommendationBullet(recommendationContainer,
                    if (notSweetPotatoCount == 1) "1 image had no sweet potato detected (❌)"
                    else "$notSweetPotatoCount images had no sweet potato detected (❌)")
            if (lowConfidenceCount > 0)
                addRecommendationBullet(recommendationContainer,
                    if (lowConfidenceCount == 1) "1 image was too unclear to classify (🌫️)"
                    else "$lowConfidenceCount images were too unclear to classify (🌫️)")
            if (validCount == 1)
                addRecommendationBullet(recommendationContainer,
                    "⚠️ Result based on a single image — submit more photos for higher reliability")
        }

        if (cropAgeWeeks != null && cropAgeWeeks > 0) {
            addSectionHeader(recommendationContainer, "🌱 Crop Age")
            addRecommendationBullet(
                recommendationContainer,
                if (cropAgeWeeks >= 16) "Week 16 or older" else "Week $cropAgeWeeks since planting"
            )
        }

        summaryInterpretation.weatherSummary?.let { ws ->
            addSectionHeader(recommendationContainer, "🌤️ Weather Conditions")
            addRecommendationBullet(
                recommendationContainer,
                ws.replace("🌤️ Weather: ", "").replace("🌤️ ", "")
            )
        }

        // Focus mode: anomaly flag cards BEFORE recommendations — not buried at the bottom
        if (isFocusMode && summaryInterpretation.anomalyFlags.isNotEmpty()) {
            addAnomalyFlagsSection(recommendationContainer, summaryInterpretation.anomalyFlags)
        }

        addSectionHeader(recommendationContainer, "📋 Recommendations")
        summaryInterpretation.recommendations.forEach {
            addRecommendationBullet(recommendationContainer, it)
        }

        // Normal mode: anomaly flag cards after recommendations
        if (!isFocusMode && summaryInterpretation.anomalyFlags.isNotEmpty()) {
            addAnomalyFlagsSection(recommendationContainer, summaryInterpretation.anomalyFlags)
        }

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

        val summaryIcon = toggleSummary.getChildAt(0) as? ImageView
        val summaryText = toggleSummary.getChildAt(1) as? TextView
        val detailIcon = toggleFullDetail.getChildAt(0) as? ImageView
        val detailText = toggleFullDetail.getChildAt(1) as? TextView

        fun applyViewMode(isSummary: Boolean) {
            val bulletsView = card.findViewById<TextView>(R.id.progressBulletsText)
            if (isSummary) {
                toggleSummary.setBackgroundResource(R.drawable.toggle_active_bg)
                summaryText?.setTextColor(Color.parseColor("#212121"))
                summaryText?.setTypeface(null, android.graphics.Typeface.BOLD)
                summaryIcon?.setColorFilter(
                    Color.parseColor("#212121"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )

                toggleFullDetail.setBackgroundColor(Color.TRANSPARENT)
                detailText?.setTextColor(Color.parseColor("#9E9E9E"))
                detailText?.setTypeface(null, android.graphics.Typeface.NORMAL)
                detailIcon?.setColorFilter(
                    Color.parseColor("#9E9E9E"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )

                interpretationText.visibility = View.VISIBLE
                bulletsView?.visibility = if (!isQuickScan && summaryInterpretation != null)
                    View.VISIBLE else View.GONE
                recommendationContainer.visibility = View.GONE

            } else {
                toggleFullDetail.setBackgroundResource(R.drawable.toggle_active_bg)
                detailText?.setTextColor(Color.parseColor("#212121"))
                detailText?.setTypeface(null, android.graphics.Typeface.BOLD)
                detailIcon?.setColorFilter(
                    Color.parseColor("#212121"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )

                toggleSummary.setBackgroundColor(Color.TRANSPARENT)
                summaryText?.setTextColor(Color.parseColor("#9E9E9E"))
                summaryText?.setTypeface(null, android.graphics.Typeface.NORMAL)
                summaryIcon?.setColorFilter(
                    Color.parseColor("#9E9E9E"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )

                interpretationText.visibility = View.GONE
                bulletsView?.visibility = View.GONE
                recommendationContainer.visibility = View.VISIBLE
            }
        }

        if (!isQuickScan && summaryInterpretation != null) {
            buildProgressBulletsAsync(card, summaryInterpretation, cropAgeWeeks, weather, prompt)
        }
        applyViewMode(isSummary = bannerFlags.isEmpty())

        toggleSummary.setOnClickListener { applyViewMode(isSummary = true) }
        toggleFullDetail.setOnClickListener { applyViewMode(isSummary = false) }

        if (weather != null && weather.temperatureCelsius > -900f) {
            weatherTemp.text = "🌡️ ${"%.1f".format(weather.temperatureCelsius)}°C"
            weatherHumidity.text = "💧 ${weather.humidity}%"
            weatherPrecip.text = "🌧️ ${weather.precipitationMm}mm"
            weatherStrip.visibility = View.VISIBLE
        } else if (!summaryInterpretation.weatherSummary.isNullOrBlank()) {
            try {
                val afterPipe = summaryInterpretation.weatherSummary.substringAfter("|").trim()
                val tempPart = afterPipe.substringBefore(",").trim()
                val humidPart = afterPipe.substringAfter(",").trim().replace("humidity", "").trim()
                weatherTemp.text = "🌡️ $tempPart"
                weatherHumidity.text = "💧 $humidPart"
                weatherPrecip.visibility = View.GONE
                weatherStrip.visibility = View.VISIBLE
            } catch (e: Exception) {
                weatherStrip.visibility = View.GONE
            }
        } else {
            weatherStrip.visibility = View.GONE
        }
        // Re-analyzing button — only on most recent card, only if no weather, only within 10 mins
        val reEvalBtn = card.findViewById<MaterialButton>(R.id.reEvaluateButton)
        if (reEvalBtn != null) {
            if (prompt != null && isReEvaluateEligible(prompt)) {
                reEvalBtn.tag = "reeval_btn"   // ← add this
                reEvalBtn.visibility = View.VISIBLE
                reEvalBtn.isEnabled = true
                reEvalBtn.setOnClickListener {
                    reEvalBtn.isEnabled = false
                    reEvalBtn.text = "Fetching location…"   // ← better in-progress text
                    reEvaluatePrompt(prompt, card)
                }
            } else {
                reEvalBtn.visibility = View.GONE
            }
        }
    }

    private fun buildProgressBulletsAsync(
        card: View,
        interpretation: CropInterpretation,
        cropAgeWeeks: Int?,
        weather: WeatherData?,
        prompt: PromptEntity?
    ) {
        val bulletsView = card.findViewById<TextView>(R.id.progressBulletsText) ?: return

        // ── Synchronous bullets (built immediately) ───────────────────────
        val bullets = mutableListOf<String>()

        // Stage + scenario status
        when (interpretation.scenarioId) {
            0  -> bullets += "✅ On track for Week ${cropAgeWeeks ?: "?"}"
            1  -> bullets += "⏳ Slightly behind schedule"
            2  -> bullets += "⚠️ Behind schedule — monitor closely"
            3  -> bullets += "🚨 Critically behind — action needed now"
            4  -> bullets += "🌾 Approaching harvest — prepare now"
            5  -> bullets += "🌡️ Approaching harvest — heat risk"
            6  -> bullets += "🌧️ Approaching harvest — rain risk"
            7  -> bullets += "⏰ Overdue — begin harvest assessment"
            8  -> bullets += "✅ Harvest ready — optimal timing"
            9  -> bullets += "⚡ Early harvest signal — verify physically"
            10 -> bullets += "🚨 Harvest overdue — act immediately"
            11 -> bullets += "❌ No plant detected"
            else -> when (interpretation.stage) {
                "Not Ready"     -> bullets += "🌱 Still growing"
                "Near Harvest"  -> bullets += "🌿 Approaching harvest"
                "Harvest Ready" -> bullets += "🌾 Ready to harvest"
            }
        }

        // Crop age
        if (cropAgeWeeks != null && cropAgeWeeks > 0) {
            val label = if (cropAgeWeeks >= 20) "20+" else "$cropAgeWeeks"
            bullets += "📅 Week $label since planting"
        }

        // Harvest time
        val ht = interpretation.harvestTime
        if (ht.isNotBlank() && !ht.contains("Unknown", ignoreCase = true)) {
            bullets += "🗓️ $ht"
        }

        // Weather risk bullets
        weather?.let { w ->
            when {
                interpretation.scenarioId == 5 ||
                        w.temperatureCelsius > 35f -> bullets += "🌡️ Heat risk (${w.temperatureCelsius.toInt()}°C) — irrigate"
                interpretation.scenarioId == 6 ||
                        w.precipitationMm > 20f    -> bullets += "🌧️ Heavy rain (${w.precipitationMm.toInt()}mm) — check drainage"
                w.humidity > 85            -> bullets += "💧 High humidity (${w.humidity}%) — disease risk"
                w.precipitationMm in 5f..19f -> bullets += "🌦️ Moderate rain — monitor drainage"
            }
        }

        // Confidence warning
        if (interpretation.lowConfidenceWarning) {
            bullets += "⚠️ Low confidence — submit clearer images"
        }

        bulletsView.text = bullets.joinToString("\n")
        bulletsView.visibility = if (bullets.isNotEmpty()) View.VISIBLE else View.GONE

        // ── Async: append progression vs previous scan ────────────────────
        if (prompt != null && prompt.id > 0L) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val history = db.promptDao().getPromptsForConversation(prompt.conversationId)
                    val thisIndex = history.indexOfFirst { it.id == prompt.id }
                    val prevPrompt = if (thisIndex > 0) history[thisIndex - 1] else null

                    if (prevPrompt != null) {
                        val prevStage = extractStageFromDiagnostic(prevPrompt.diagnostic)
                        val currStage = interpretation.stage
                        val stageOrder = mapOf("Not Ready" to 0, "Near Harvest" to 1, "Harvest Ready" to 2)
                        val prevOrd = stageOrder[prevStage] ?: -1
                        val currOrd = stageOrder[currStage] ?: -1

                        if (prevOrd >= 0 && currOrd >= 0) {
                            val progressionBullet = when {
                                currOrd > prevOrd  -> "\n📈 Progressed from $prevStage since last scan"
                                currOrd == prevOrd -> "\n➡️ Same stage as last scan"
                                else               -> "\n📉 Regressed from $prevStage — check crop health"
                            }
                            withContext(Dispatchers.Main) {
                                if (bulletsView.isAttachedToWindow) {
                                    bulletsView.text = "${bulletsView.text}$progressionBullet"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ProgressBullets", "Failed to load progression: ${e.message}")
                }
            }
        }
    }

    // A. Read profiling flag
    fun readProfilingFlag() {
        profilingAlreadyDone = intent.getBooleanExtra(
            PlantationProfileActivity.EXTRA_PROFILING_DONE, false
        )
    }

    // ─── Plantation Void System ───────────────────────────────────────────────

    private fun checkAndHandleVoidState() {
        if (conversationId == -1L || isQuickScan) return

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                PlantationVoidChecker.evaluate(db, conversationId)
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is PlantationVoidChecker.VoidResult.Clean -> { /* nothing */ }
                    is PlantationVoidChecker.VoidResult.SubjectToVoid ->
                        showSubjectToVoidDialog(
                            flaggedPromptId = result.flaggedPromptId,
                            flaggedWeek     = result.flaggedWeek,
                            flagReason      = result.flagReason
                        )
                    is PlantationVoidChecker.VoidResult.HardVoid ->
                        showHardVoidDialog(missedWindow = result.missedWindow)
                }
            }
        }
    }

    private fun showSubjectToVoidDialog(
        flaggedPromptId: Long,
        flaggedWeek: Int,
        flagReason: String
    ) {
        val convName = conversationTitle.text.toString()
        MaterialAlertDialogBuilder(this, R.style.MyCustomDialogLayout)
            .setTitle("⚠️ Plantation Flagged — Subject to Void")
            .setMessage(
                "The last week's output for \"$convName\" was flagged as $flagReason " +
                        "and that week has now expired.\n\n" +
                        "To avoid accuracy deficiency, GrowSight needs to delete the Week $flaggedWeek " +
                        "output and mark it as Missed before you can continue.\n\n" +
                        "Do you want to continue?"
            )
            .setCancelable(false)
            .setPositiveButton("Delete & Continue") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.promptDao().deletePromptById(flaggedPromptId)
                    }
                    withContext(Dispatchers.Main) {
                        scanThumbs.removeAll { it.prompt.id == flaggedPromptId }
                        isSendButtonManuallyUnlocked = true
                        isSendTimerBypassed = true
                        showUnlocked()
                        refreshGrid()
                        refreshInstructionVisibility()
                        Toast.makeText(
                            this@ConversationsActivity,
                            "✅ Week $flaggedWeek output removed — marked as Missed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()  // user cannot enter without accepting
            }
            .show()
    }

    private fun showHardVoidDialog(missedWindow: IntRange) {
        val convName = conversationTitle.text.toString()
        MaterialAlertDialogBuilder(this, R.style.MyCustomDialogLayout)
            .setTitle("🚨 Plantation Voided")
            .setMessage(
                "\"$convName\" cannot continue.\n\n" +
                        "Reason: The last scan output is flagged AND all weeks in the critical " +
                        "transition window (Week ${missedWindow.first}–${missedWindow.last}) were " +
                        "missed with no valid scan.\n\n" +
                        "GrowSight will archive this plantation to avoid accuracy deficiency. " +
                        "You will need to create a new plantation to continue monitoring.\n\n" +
                        "This action cannot be undone."
            )
            .setCancelable(false)
            .setPositiveButton("Archive Plantation") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.conversationDao().markAsVoided(conversationId)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ConversationsActivity,
                            "Plantation archived. Please create a new plantation.",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }
            }
            .show()
    }

    // D. Periodic send lock checker
    private fun startSendLockChecker() {
        if (isQuickScan) return
        lifecycleScope.launch {
            while (true) {
                delay(60_000L)
                if (conversationId == -1L) continue
                if (isRetaking) continue
                val prompts = db.promptDao().getPromptsForConversation(conversationId)
                withContext(Dispatchers.Main) {
                    if (prompts.isEmpty() || PlantationWeekHelper.isSendUnlocked(prompts))
                        showUnlocked()
                    else
                        showLocked(prompts)
                    refreshInstructionVisibility()
                }
            }
        }
    }

    // E. Check and apply send lock immediately - MODIFIED for quick scan
    fun checkAndApplySendLock() {
        if (conversationId == -1L) { showUnlocked(); refreshInstructionVisibility(); return }
        if (isQuickScan)            { showUnlocked(); refreshInstructionVisibility(); return }
        if (isSendButtonManuallyUnlocked) return
        if (isRetaking) return

        lifecycleScope.launch {
            // If a retake was abandoned (user backed out without submitting),
            // hard-delete the hidden prompt — never restore it
            if (!isProcessingImages && retakingPromptId != -1L) {
                db.promptDao().deletePromptById(retakingPromptId)
                db.promptDao().deleteAllHiddenForConversation(conversationId)
                retakingPromptId = -1L
                isRetaking = false
            }

            val prompts = db.promptDao().getPromptsForConversation(conversationId)
            withContext(Dispatchers.Main) {
                if (prompts.isEmpty()) showUnlocked()
                else if (PlantationWeekHelper.isSendUnlocked(prompts)) showUnlocked()
                else showLocked(prompts)
                refreshInstructionVisibility()
            }
        }
    }

    // MODIFIED: buildSendButtonListener with quick scan support
    fun buildSendButtonListener() {
        sendButton.setOnClickListener {
            isHistoryLoaded = true
            if (uploadedUris.isEmpty()) {
                Toast.makeText(this, "Add at least one image", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            hasProcessedIncomingImages = false
            processedUris.clear()
            isProcessingImages = true
            if (!isQuickScan) {
                stopPulseInputRow()
                inputRow.visibility      = View.GONE      // ← hide input immediately on send
                sendLockedRow.visibility = View.VISIBLE   // ← show lock immediately on send
                sendLockTimeText.text    = "Analyzing…"   // ← placeholder until timer starts
            }
            showLoader()
            addShimmerPlaceholderCard()
            showProcessingSheet()

            val persisted = uploadedUris.mapNotNull { ensureLocalCopy(it) }
            if (persisted.isEmpty()) {
                Toast.makeText(this, "Could not persist images", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            uploadedUris.clear()
            previewContainer.removeAllViews()
            hidePreviewSection()

            if (!areModelsAvailable()) {
                val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(Date())
                lifecycleScope.launch {
                    db.promptDao().insertPrompt(
                        PromptEntity(
                            conversationId = conversationId,
                            imageUris = persisted.map { it.toString() },
                            diagnostic = "no_detection",
                            timestamp = timestamp,
                            weekNumber = null,
                            cropAgeWeeks = db.conversationDao().getCropAge(conversationId)
                        )
                    )
                    withContext(Dispatchers.Main) {
                        addConversationCard(persisted, null, timestamp, true)
                        scrollToBottom()
                        Toast.makeText(this@ConversationsActivity, "Images saved (AI unavailable)", Toast.LENGTH_SHORT).show()
                    }
                }
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.Main) {
                val history = db.promptDao().getPromptsForConversation(conversationId)
                val existingAge = db.conversationDao().getCropAge(conversationId)

                // Skip 7-day send-lock check for quick scan
                if (!isQuickScan && !isRetaking && !isSendTimerBypassed
                    && !PlantationWeekHelper.isSendUnlocked(history)) {
                    val countdown = PlantationWeekHelper.formatUnlockCountdown(history)
                    hideLoader()
                    isProcessingImages = false
                    AlertDialog.Builder(this@ConversationsActivity)
                        .setTitle("⏳ Monitoring not due yet")
                        .setMessage(
                            "Your next weekly check-in isn't available yet.\n\n" +
                                    "$countdown\n\n" +
                                    "Weekly monitoring helps track your plantation's progress accurately."
                        )
                        .setPositiveButton("OK", null)
                        .apply {
                            if (BuildConfig.DEBUG) {
                                setNeutralButton("Override (dev)") { _, _ ->
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        isSendTimerBypassed = true
                                        proceedWithSend(persisted)
                                    }
                                }
                            }
                        }
                        .show()
                    return@launch
                }

                // Skip crop-age dialog for quick scan
                if (!isQuickScan && !profilingAlreadyDone && history.isEmpty() && existingAge == null) {
                    val age = suspendCancellableCoroutine<Int> { cont ->
                        showCropAgeDialog { selectedAge -> cont.resume(selectedAge) }
                    }
                    if (age > 0) db.conversationDao().updateCropAge(conversationId, age)
                }

                proceedWithSend(persisted)
            }
            scrollToBottom()
        }
    }

    private fun addShimmerPlaceholderCard() {
        scanThumbs.removeAll { it.prompt.id == -999L }
        viewAllButton.visibility = View.GONE
        isProcessingImages = true
        hideInstruction()

        // Use the already-displayed slots as the source of truth.
        // If currentGridSlots is empty (e.g. very first send before refreshGrid
        // has run), fall back to a single Loading slot so the shimmer always shows.
        val slots = if (currentGridSlots.isNotEmpty()) {
            currentGridSlots.toMutableList().also { list ->
                val openIndex = list.indexOfFirst { it is WeekSlot.Open }
                if (openIndex >= 0) list[openIndex] = WeekSlot.Loading
                else list.add(WeekSlot.Loading)
            }
        } else {
            mutableListOf(WeekSlot.Loading)
        }

        currentGridSlots.clear()
        currentGridSlots.addAll(slots)
        scanGrid.adapter = WeekSlotAdapter(slots)
    }

    // Helper extracted so the dev-override can also call it
    private suspend fun proceedWithSend(persisted: List<Uri>) {
        isSendButtonManuallyUnlocked = false
        isSendTimerBypassed = false
        if (!isQuickScan) {
            if (isRetaking) {
                // Retake: cooldown was already reset when user confirmed retake.
                // Don't call startCooldown yet — it will be called normally below
                // after the new prompt is inserted. Clear the flag now.
                isRetaking = false
            }
            withContext(Dispatchers.Main) {
                inputRow.visibility      = View.GONE
                sendLockedRow.visibility = View.VISIBLE
                val totalMs     = TimeUnit.DAYS.toMillis(7)
                val remainingMs = totalMs
                cooldownTimer?.cancel()
                cooldownTimer = object : CountDownTimer(remainingMs, 1_000) {
                    override fun onTick(millisUntilFinished: Long) {
                        sendLockTimeText.text = formatCooldown(millisUntilFinished)
                        val elapsed  = totalMs - millisUntilFinished
                        val progress = ((elapsed.toFloat() / totalMs) * 100).toInt().coerceIn(0, 100)
                        sendLockProgressBar.progress = progress
                    }
                    override fun onFinish() {
                        showUnlocked()
                        if (!isQuickScan) checkAndHandleVoidState()
                        refreshGrid()
                    }
                }.start()
            }
        }
        val savedWeather = getConversationWeather()
        if (savedWeather != null) {
            cachedWeather      = savedWeather
            weatherFetchState  = WeatherFetchState.DONE
            weatherFetchedAt   = System.currentTimeMillis()
            processImagesWithAI(persisted, savedWeather)
        } else {
            fetchWeatherThenProcess(persisted)
        }
    }
    override fun onResume() {
        super.onResume()

        // If user went to location settings and came back, re-check and resume
        val pendingUris = pendingLocationUris
        val pendingDialog = pendingLocationDialog
        if (pendingUris != null) {
            val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val isNowEnabled =
                locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                        locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

            if (isNowEnabled) {
                pendingLocationUris   = null
                pendingLocationDialog = null
                pendingDialog?.dismiss()
                showLocationDialogThenProcess(pendingUris) // re-enter in "finding" state
            }
            // If still off, the dialog stays open where they left it
        }
        checkAndShowWeatherBanner()

        if (!isProcessingImages) {
            isSwitchingConversation = false
            loader.visibility = View.GONE
            shimmerLayout.stopShimmer()
            shimmerLayout.visibility = View.GONE
            sendButton.isEnabled = true  // always re-enable first
            sendButton.alpha = 1f
            uploadButton.isEnabled = true
            cameraButton.isEnabled = true
        }

        val cardsAlreadyShown = uploadedImagesContainer.childCount > 0

        if (conversationId != -1L && !isProcessingImages && !isRetaking && !isHistoryLoaded
            && uploadedUris.isEmpty() && previewContainer.childCount == 0) {
            reloadConversationHistory()
        }

        if (!isQuickScan) {
            checkAndApplySendLock()   // now handles retake recovery internally
            checkAndHandleVoidState()
        }
        refreshInstructionVisibility()
        stopPulseInputRow()  // Pulse will be restarted by the adapter if needed
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        val incomingConvId = intent?.getLongExtra(EXTRA_CONVERSATION_ID, -1L) ?: -1L
        if (incomingConvId != -1L && incomingConvId != conversationId) {
            conversationId  = incomingConvId
            isHistoryLoaded = false
            isQuickScan     = isConversationQuickScan(conversationId)

            lifecycleScope.launch {
                val conv = db.conversationDao().getConversationById(conversationId)
                conv?.let {
                    withContext(Dispatchers.Main) {
                        conversationTitle.text = it.name
                        conversationTitle.isSelected = true
                        clearConversationCards()
                        drawerLayout.closeDrawer(GravityCompat.END)
                    }
                    reloadConversationHistory()
                    withContext(Dispatchers.Main) {
                        if (!isQuickScan) checkAndApplySendLock()
                    }
                }
            }
            return
        }

        // ── Existing URI handling (unchanged) ────────────────────────────────
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
        outState.putBoolean("is_quick_scan", isQuickScan)


    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        hasProcessedIncomingImages = savedInstanceState.getBoolean("has_processed_images", false)
        conversationId = savedInstanceState.getLong("current_conversation_id", -1L)
        isHistoryLoaded = false
        isQuickScan = savedInstanceState.getBoolean("is_quick_scan", isQuickScan)  // ← add this
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
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    val pendingUris = pendingLocationUris
                    val pendingDialog = pendingLocationDialog
                    if (pendingUris != null && pendingDialog != null && pendingDialog.isShowing) {
                        pendingLocationUris   = null
                        pendingLocationDialog = null
                        pendingDialog.dismiss()
                        showLocationDialogThenProcess(pendingUris)
                    }
                } else {
                    // Permission denied — proceed without weather if there's a pending analysis
                    val pendingUris = pendingLocationUris
                    pendingLocationUris   = null
                    pendingLocationDialog?.dismiss()
                    pendingLocationDialog = null
                    if (pendingUris != null) {
                        lifecycleScope.launch { processImagesWithAI(pendingUris, null) }
                    }
                }
            }
        }
    }

    // MODIFIED: detectionCameraLauncher with quick scan support - removed weather extras
    private val detectionCameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uriString = result.data?.getStringExtra(CameraDetectionActivity.RESULT_IMAGE_URI)
                if (uriString != null) {
                    val uri = Uri.parse(uriString)
                    val persisted = ensureLocalCopy(uri) ?: return@registerForActivityResult
                    isHistoryLoaded = true
                    // ← Just add to preview, let user send manually
                    addPreviewImage(persisted)
                    showPreviewSection()
                }
            }
        }

    // MODIFIED: handleIncomingImages with quick scan support
    private suspend fun handleIncomingImages(incomingUris: ArrayList<Uri>?) {
        Log.d("HandleIncoming", "Called with ${incomingUris?.size} URIs, conversationId=$conversationId")
        val urisHash = incomingUris?.joinToString(",") { it.toString() }?.hashCode()?.toString() ?: return

        if (db.promptDao().existsByUriHash(urisHash)) {
            Log.d("HandleIncoming", "URIs already in DB — skipping")
            hideLoader()
            return
        }
        if (incomingUris.isNullOrEmpty()) {
            hideLoader()
            return
        }
        if (isSwitchingConversation) {
            hideLoader()
            return
        }

        if (conversationId != -1L) {
            val existingPrompts = db.promptDao().getPromptsForConversation(conversationId)
            if (existingPrompts.isNotEmpty()) {
                Log.d("HandleIncoming", "Conversation already has prompts - skipping")
                hideLoader()
                return
            }
        }

        if (processedUris.contains(urisHash)) {
            hideLoader()
            return
        }
        if (hasProcessedIncomingImages) {
            hideLoader()
            return
        }
        hasProcessedIncomingImages = true

        if (conversationId == -1L) {
            try {
                val count = db.conversationDao().getConversationCount()
                val defaultName = "Quick Scan#${count + 1}"
                var cropAge: Int? = null

                if (!isQuickScan && db.conversationDao().getCropAge(conversationId) == null) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            showCropAgeDialog { age ->
                                cropAge = if (age > 0) age else null
                                cont.resume(Unit)
                            }
                        }
                    }
                } else {
                    cropAge = db.conversationDao().getCropAge(conversationId)
                }

                val newId = db.conversationDao().insertConversation(
                    ConversationEntity(name = defaultName, cropAgeWeeks = cropAge)
                )
                conversationId = newId
                runOnUiThread { conversationTitle.text = defaultName }

                if (isQuickScan) {
                    runOnUiThread { conversationTitle.text = "Quick Scan" }
                }

                refreshConversationList()

                if (isQuickScan) {
                    val firstUri = incomingUris.firstOrNull()?.toString() ?: ""
                    QuickScanManager.save(
                        ctx = this,
                        scan = QuickScanManager.QuickScan(
                            conversationId = newId,
                            imageUri = firstUri,
                            diagnosis = "Scanning…",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    supportActionBar?.title = "Quick Scan"
                }

                val persistedUris = incomingUris.mapNotNull { ensureLocalCopy(it) }
                if (persistedUris.isNotEmpty() && areModelsAvailable()) {
                    isProcessingImages = true
                    showLoader()
                    withContext(Dispatchers.Main) {
                        fetchWeatherThenProcess(persistedUris)
                    }
                } else throw IllegalStateException("No images could be persisted")
            } catch (e: Exception) {
                Log.e("HandleIncoming", "Failed: ${e.message}", e)
                hideLoader()
                fallbackSaveImages(incomingUris)
            }
        }
        processedUris.add(urisHash)
    }

    private fun isConversationQuickScan(conversationId: Long): Boolean {
        return getSharedPreferences("quick_scan_prefs", Context.MODE_PRIVATE)
            .getBoolean("is_quick_scan_$conversationId", false)
    }

    private fun markConversationAsQuickScan(conversationId: Long) {
        getSharedPreferences("quick_scan_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_quick_scan_$conversationId", true)
            .apply()
    }

    private fun checkForModelUpdatesOnStart() {
        lifecycleScope.launch {
            try {
                if (!shouldAutoCheckForUpdates()) {
                    val days = getDaysUntilNextAutoCheck()
                    if (days > 0) Log.d("ModelUpdate", "Next auto-check in $days days")
                    return@launch
                }

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
            .apply()
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
            isIndeterminate = true
            visibility = View.VISIBLE
        }

        val progressMessage = TextView(this@ConversationsActivity).apply {
            text = "Please wait... Checking for model updates…"
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
            R.style.MyCustomDialogLayout
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


    private fun showTestMenu() {
        AlertDialog.Builder(this)
            .setTitle("🧪 Developer Tools")
            .setItems(arrayOf(
                "🎬 Simulate Processing State",
                "⚙️ Update Settings",
                "🔄 Check for Model Updates",
                "🔁 Reset Send Button",
                "⏱️ Trigger Cooldown (lock this week)",
                "🔓 Clear Cooldown + Unlock",
                "📅 Shift All Card Dates -1 Week",
                "🔔 Fire Week-End Alert Now",
                "🤖 Check Model Status",
                "🧪 Run Comprehensive AI Test",
                "🔲 Bounding Boxes: ${if (debugShowBoundingBoxes) "ON ✅" else "OFF"}"  // ← ADD THIS
            )) { _, which ->
                when (which) {
                    0 -> testShimmerAnimation()
                    1 -> showUpdateInfoDialog()
                    2 -> lifecycleScope.launch { manualModelUpdateCheck() }
                    3 -> resetSendButton()
                    4 -> devTriggerCooldown()
                    5 -> devClearCooldown()
                    6 -> devShiftDatesOneWeekBack()
                    7 -> devFireWeekEndAlert()
                    8 -> checkModelStatus()
                    9 -> runComprehensiveTest()
                    10 -> {   // ← ADD THIS CASE
                        debugShowBoundingBoxes = !debugShowBoundingBoxes
                        Toast.makeText(
                            this,
                            "Bounding boxes: ${if (debugShowBoundingBoxes) "ON — next send will show boxes" else "OFF"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun devFireWeekEndAlert() {
        if (conversationId == -1L) {
            Toast.makeText(this, "Open a plantation first", Toast.LENGTH_SHORT).show()
            return
        }
        if (isQuickScan) {
            Toast.makeText(this, "⚠️ Week-end alert is not applicable to quick scans", Toast.LENGTH_SHORT).show()
            return
        }
        val request = androidx.work.OneTimeWorkRequestBuilder<com.ai.growsight.util.WeekEndAlertWorker>()
            .setInitialDelay(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setInputData(
                androidx.work.workDataOf(
                    com.ai.growsight.util.WeekEndAlertWorker.KEY_CONVERSATION_ID to conversationId,
                    com.ai.growsight.util.WeekEndAlertWorker.KEY_FORCE_FIRE to true
                )
            )
            .build()

        androidx.work.WorkManager.getInstance(this)
            .enqueueUniqueWork("dev_week_end_test", androidx.work.ExistingWorkPolicy.REPLACE, request)
        Toast.makeText(this, "🔔 Week-End alert worker queued — check notifications", Toast.LENGTH_SHORT).show()
    }

    private fun resetSendButton() {
        lifecycleScope.launch {
            // Restore any hidden retake prompt so the DB stays consistent.
            val hiddenId = retakingPromptId
            if (hiddenId != -1L) {
                db.promptDao().deletePromptById(hiddenId)
                db.promptDao().deleteAllHiddenForConversation(conversationId)
                retakingPromptId = -1L
            }
            withContext(Dispatchers.Main) {
                isProcessingImages           = false
                isSendButtonManuallyUnlocked = true
                isSendTimerBypassed          = true
                isRetaking                   = false
                shimmerLayout.stopShimmer()
                shimmerLayout.visibility = View.GONE
                loader.visibility        = View.GONE
                showUnlocked()
                uploadButton.isEnabled = true
                cameraButton.isEnabled = true
                refreshInstructionVisibility()
                Toast.makeText(this@ConversationsActivity, "✅ Send button reset", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun devTriggerCooldown() {
        if (conversationId == -1L) {
            Toast.makeText(this, "Open a conversation first", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val now       = System.currentTimeMillis()
            val timestamp = java.text.SimpleDateFormat("MM/dd/yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(now))

            val fake = PromptEntity(
                conversationId = conversationId,
                imageUris      = emptyList(),
                diagnostic     = "Not Ready|80",
                timestamp      = timestamp,
                timestampMs    = now,
                weekNumber     = null,
                cropAgeWeeks   = null
            )
            db.promptDao().insertPrompt(fake)

            val prompts = db.promptDao().getPromptsForConversation(conversationId)
            withContext(Dispatchers.Main) {
                showLocked(prompts)
                // ← removed reloadConversationHistory() — fake prompt has no images
                Toast.makeText(
                    this@ConversationsActivity,
                    "⏱️ Cooldown triggered — bottom bar locked for this calendar week",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun devClearCooldown() {
        lifecycleScope.launch {
            val thisWeekIds = mutableListOf<Long>()
            if (conversationId != -1L) {
                db.promptDao().deleteAllHiddenForConversation(conversationId)
                val prompts = db.promptDao().getPromptsForConversation(conversationId)
                prompts
                    .filter { PlantationWeekHelper.isRetakeEligible(it) }
                    .forEach { p ->
                        thisWeekIds.add(p.id)
                        db.promptDao().deletePromptById(p.id)
                    }
            }
            retakingPromptId = -1L
            isRetaking = false
            CooldownManager.resetCooldown(this@ConversationsActivity)
            withContext(Dispatchers.Main) {
                cooldownTimer?.cancel()
                isSendButtonManuallyUnlocked = true
                isSendTimerBypassed          = true
                scanThumbs.removeAll { thisWeekIds.contains(it.prompt.id) }
                refreshGrid()
                showUnlocked()
                refreshInstructionVisibility()
                Toast.makeText(
                    this@ConversationsActivity,
                    "🔓 Cooldown cleared + bottom bar unlocked",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun devShiftDatesOneWeekBack() {
        if (conversationId == -1L) {
            Toast.makeText(this, "Open a conversation first", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val oneWeekMs = TimeUnit.DAYS.toMillis(7)

            // Safety: purge any abandoned hidden retake stubs before doing anything
            db.promptDao().deleteAllHiddenForConversation(conversationId)

            // 1. Shift all visible prompt timestamps back 1 week
            db.promptDao().shiftAllTimestampsMsBackOneWeek(conversationId)

            // 2. Rewrite display strings — visible rows only
            val sdf = java.text.SimpleDateFormat("MM/dd/yyyy HH:mm", java.util.Locale.getDefault())
            val visibleRows = db.promptDao().getPromptsForConversation(conversationId)  // ← was getRawPromptsForConversation
            visibleRows.forEach { prompt ->
                if (prompt.timestampMs > 0L) {
                    val newDisplay = sdf.format(java.util.Date(prompt.timestampMs))
                    db.promptDao().updateDisplayTimestamp(prompt.id, newDisplay)
                }
            }

            // 3. Shift plantingDate back 1 week
            val currentPlantingDate = db.conversationDao().getPlantingDate(conversationId)
            if (currentPlantingDate != null && currentPlantingDate > 0L) {
                db.conversationDao().updatePlantingDate(conversationId, currentPlantingDate - oneWeekMs)
            } else {
                val currentAge = db.conversationDao().getCropAge(conversationId)
                if (currentAge != null) {
                    db.conversationDao().updateCropAge(conversationId, currentAge + 1)
                }
            }

            // 4. Re-evaluate lock state and refresh UI
            val prompts = db.promptDao().getPromptsForConversation(conversationId)
            withContext(Dispatchers.Main) {
                scanThumbs.clear()
                viewAllButton.visibility = View.GONE
                if (PlantationWeekHelper.isSendUnlocked(prompts)) showUnlocked()
                else showLocked(prompts)
                reloadConversationHistory()

                // Fire the check-in notification after 5 seconds to simulate week rollover
                val devNotifWork = OneTimeWorkRequestBuilder<CheckInNotificationWorker>()
                    .setInitialDelay(5, TimeUnit.SECONDS)
                    .setInputData(
                        androidx.work.workDataOf(
                            CheckInNotificationWorker.KEY_CONVERSATION_ID to conversationId
                        )
                    )
                    .build()
                WorkManager.getInstance(this@ConversationsActivity)
                    .enqueueUniqueWork("dev_notif_test", ExistingWorkPolicy.REPLACE, devNotifWork)
                Toast.makeText(
                    this@ConversationsActivity,
                    "Dates shifted -1 week. Notification fires in 5s.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun testShimmerAnimation() {
        if (isProcessingImages) {
            Toast.makeText(this, "Already processing — use Reset Send Button first", Toast.LENGTH_SHORT).show()
            return
        }
        isProcessingImages = true
        showLoader()
        addShimmerPlaceholderCard()   // ← Loading cell in the grid
        showProcessingSheet()          // ← bottom sheet with shimmer card

        Handler(Looper.getMainLooper()).postDelayed({
            processingSheet?.dismiss()
            processingSheet = null
            isProcessingImages = false
            currentGridSlots.removeAll { it is WeekSlot.Loading }
            hideLoader()
            refreshGrid()
            Toast.makeText(
                this@ConversationsActivity,
                "✅ Shimmer test complete",
                Toast.LENGTH_SHORT
            ).show()
        }, 3000L)
    }

    private fun showUpdateCropAgeDialog(onDone: () -> Unit) {
        lifecycleScope.launch {
            val currentAge = db.conversationDao().getCropAge(conversationId)
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

    // ─── LOCATION   ───────────────────────────────────────────────────

    private fun showLocationDialogThenProcess(uris: List<Uri>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_location_weather, null)

        val locationOffSection     = dialogView.findViewById<LinearLayout>(R.id.locationOffSection)
        val findingLocationSection = dialogView.findViewById<LinearLayout>(R.id.findingLocationSection)
        val noInternetSection      = dialogView.findViewById<LinearLayout>(R.id.noInternetSection)
        val btnOpenSettings        = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenSettings)
        val btnSkipLocation        = dialogView.findViewById<android.widget.Button>(R.id.btnSkipLocation)
        val btnCancelLocation      = dialogView.findViewById<android.widget.Button>(R.id.btnCancelLocation)
        val btnContinueNoInternet  = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnContinueNoInternet)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun showSection(section: String) {
            locationOffSection.visibility     = if (section == "off")      View.VISIBLE else View.GONE
            findingLocationSection.visibility = if (section == "finding")  View.VISIBLE else View.GONE
            noInternetSection.visibility      = if (section == "internet") View.VISIBLE else View.GONE
        }

        fun proceedWithoutWeather() {
            dialog.dismiss()
            lifecycleScope.launch { processImagesWithAI(uris, null) }
        }

        fun startDetection() {
            showSection("finding")

            val isOnline = try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.activeNetworkInfo?.isConnected == true
            } catch (e: Exception) { false }

            if (!isOnline) {
                showSection("internet")
                return
            }

            LocationWeatherManager.startWeatherFlow(
                activity = this,
                callback = object : LocationWeatherManager.WeatherFlowCallback {
                    override fun onWeatherReady(weather: WeatherData?) {
                        cachedWeather     = weather
                        weatherFetchState = if (weather != null) WeatherFetchState.DONE else WeatherFetchState.IDLE
                        weatherFetchedAt  = if (weather != null) System.currentTimeMillis() else 0L
                        dialog.dismiss()
                        lifecycleScope.launch { processImagesWithAI(uris, weather) }
                    }
                    override fun onSkipped() {
                        dialog.dismiss()
                        lifecycleScope.launch { processImagesWithAI(uris, null) }
                    }
                }
            )
        }

        // ── Button listeners ──────────────────────────────────────────────────────
        btnOpenSettings.setOnClickListener {
            // Open location settings, then re-check when user comes back
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            // Watch for location being turned on via onResume
            pendingLocationUris = uris
            pendingLocationDialog = dialog
        }

        btnSkipLocation.setOnClickListener    { proceedWithoutWeather() }
        btnCancelLocation.setOnClickListener  { proceedWithoutWeather() }
        btnContinueNoInternet.setOnClickListener { proceedWithoutWeather() }

        // ── Decide initial state ──────────────────────────────────────────────────
        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val isLocationEnabled =
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        when {
            !hasPermission -> {
                // Ask for permission, dialog waits
                showSection("off")
                dialog.show()
                pendingLocationUris    = uris
                pendingLocationDialog  = dialog
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    LOCATION_PERMISSION_CODE
                )
            }
            !isLocationEnabled -> {
                showSection("off")
                dialog.show()
            }
            else -> {
                showSection("finding")
                dialog.show()
                startDetection()
            }
        }
    }

    // ─── Diagnostic parsing ───────────────────────────────────────────────────

    private fun parseDiagnosticString(diagnostic: String): CropInterpretation? {
        if (diagnostic == "no_detection" || diagnostic == "No Detection" || diagnostic.isBlank()) return null
        // ── Stage conflict: AI stage doesn't match expected crop-week stage ──────
        if (diagnostic.startsWith("Stage Conflict|")) {
            val parts          = diagnostic.split("|")
            val detectedStage  = parts.getOrNull(1) ?: "Unknown"
            val cropWeek       = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val expectedStage  = if (cropWeek > 0) expectedStageForWeek(cropWeek) else null

            val perImgPart  = parts.firstOrNull { it.startsWith("per_img:") } ?: ""
            val imageCount  = if (perImgPart.isNotEmpty())
                perImgPart.removePrefix("per_img:").split(",").filter { it.isNotBlank() }.size
            else 1
            val imageWord   = if (imageCount == 1) "image" else "images"

            val base = InterpretationEngine.interpret(
                PlantAnalysisResult(label = "near_harvest", confidence = 0.5f, allScores = emptyMap())
            )
            return base.copy(
                harvestTime = "Cannot estimate — Sent $imageWord may not be from the same crop as earlier scans",
                weatherSummary = null,
                recommendations = buildList {
                    add("🔍 Walk your plantation and inspect the crop in person")
                    if (expectedStage != null)
                        add("📅 At Week $cropWeek, \"$expectedStage\" is typically expected")
                    add("📸 AI detected: \"$detectedStage\" — flagged as inconsistent with crop age")
                    add("📆 Re-scan next week for a fresh assessment")
                },
                interpretationSummary = buildString {
                    append("The AI detected \"$detectedStage\" in your $imageWord")
                    if (cropWeek > 0) {
                        append(", but this does not align with your crop at Week $cropWeek")
                        if (expectedStage != null) append(" (expected: \"$expectedStage\")")
                    }
                    append(". The result has been flagged as inconclusive. Please verify your crop manually.")
                },
                scenarioLabel = "Stage Conflict"
            )
        }
        // ── Insufficient Batch abnormality ────────────────────────────────────
        if (diagnostic.startsWith("Insufficient Batch|")) {
            val parts      = diagnostic.split("|")
            val validCount = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val totalCount = parts.getOrNull(2)?.toIntOrNull() ?: 0

            // Parse per-image statuses stored at processing time
            val perImgPart = parts.firstOrNull { it.startsWith("per_img:") } ?: ""
            val statuses   = if (perImgPart.isNotEmpty())
                perImgPart.removePrefix("per_img:").split(",").filter { it.isNotBlank() }
            else emptyList()
            val nNoDetect  = statuses.count { it == "no_detect" }
            val nLowConf   = statuses.count { it == "low_conf" }
            fun img(n: Int) = if (n == 1) "image" else "images"

            val detectionNote = when {
                nLowConf > 0 && nNoDetect == 0 ->
                    "Sweet potato was detected in all $totalCount ${img(totalCount)} but CNN maturity confidence was too low to classify $nLowConf ${img(nLowConf)} reliably"
                nNoDetect > 0 && nLowConf == 0 ->
                    "YOLO found no sweet potato plant in $nNoDetect of $totalCount ${img(totalCount)} — only $validCount ${img(validCount)} passed detection"
                nNoDetect > 0 && nLowConf > 0 ->
                    "$nNoDetect ${img(nNoDetect)} had no plant detected by YOLO, and $nLowConf ${img(nLowConf)} had detection but CNN confidence was too low — only $validCount of $totalCount were usable"
                else ->
                    "Only $validCount of $totalCount ${img(totalCount)} contained a detectable sweet potato"
            }

            return CropInterpretation(
                stage                = "Inconclusive",
                stageEmoji           = "📊",
                stageColor           = "gray",
                confidencePercent    = 0,
                harvestTime          = "Cannot determine — insufficient data",
                recommendations      = listOf(
                    detectionNote,
                    "Submit at least 3 clear images of your crop",
                    "Ensure images clearly show sweet potato leaves or plant",
                    "Avoid uploading unrelated images in the same batch"
                ),
                lowConfidenceWarning = true,
                scenarioLabel        = "Insufficient Batch",
                interpretationSummary = "$detectionNote. The system cannot make a reliable assessment from this batch. Please retake with clearer, focused images of your crop.",
                anomalyFlags         = emptyList()
            )
        }
        // ─────────────────────────────────────────────────────────────────────

        return try {
            val parts = diagnostic.split("|")
            if (parts.size < 2) return null
            val stage = parts[0]
            val confidencePercent = parts[1].toIntOrNull() ?: 0
            val savedHarvestTime = if (parts.size >= 3 && parts[2].isNotBlank()) parts[2] else null
            val weatherSummary = if (parts.size >= 4 && parts[3].isNotBlank()) parts[3].replace("~", "|") else null
            val savedWeatherRecs = if (parts.size >= 5 && parts[4].isNotBlank()) parts[4].replace("~", "|").split("^").filter { it.isNotBlank() } else emptyList()
            val savedNarrative = if (parts.size >= 6 && parts[5].isNotBlank()) parts[5].replace("~pipe~", "|") else null
            val savedBullets = if (parts.size >= 7 && parts[6].isNotBlank())
                parts[6].replace("~pipe~", "|").split("^").filter { it.isNotBlank() }
            else emptyList()

            val label = when (stage) {
                "Not Ready" -> "not_ready"
                "Near Harvest" -> "near_harvest"
                "Harvest Ready" -> "harvest_ready"
                else -> return null
            }
            val fakeResult = PlantAnalysisResult(label = label, confidence = confidencePercent / 100f, allScores = emptyMap())
            val base = InterpretationEngine.interpret(fakeResult)

            val restoredRecs = if (savedBullets.isNotEmpty()) savedBullets else {
                val baseFiltered = base.recommendations.filter { rec ->
                    !rec.startsWith("🌡️") && !rec.startsWith("💧") &&
                            !rec.startsWith("🌧️") && !rec.startsWith("🌦️") && !rec.startsWith("☀️")
                }
                baseFiltered + savedWeatherRecs
            }

            val flagsPart = parts.firstOrNull { it.startsWith("flags:") } ?: ""
            val restoredFlags = if (flagsPart.isNotEmpty()) {
                flagsPart.removePrefix("flags:").split("^^^")
                    .filter { it.contains(";;;") }
                    .mapNotNull { flagStr ->
                        val f = flagStr.split(";;;")
                        if (f.size >= 4) AnomalyFlag(
                            badgeLabel = f[0].replace("~PIPE~", "|").replace("~C3~", "^^^").replace("~S3~", ";;;"),
                            severity   = f[1],
                            detail     = f[2].replace("~PIPE~", "|").replace("~C3~", "^^^").replace("~S3~", ";;;"),
                            suggestion = f[3].replace("~PIPE~", "|").replace("~C3~", "^^^").replace("~S3~", ";;;")
                        ) else null
                    }
            } else emptyList()

            base.copy(
                harvestTime = savedHarvestTime ?: base.harvestTime,
                weatherSummary = weatherSummary,
                recommendations = restoredRecs,
                interpretationSummary = savedNarrative,
                anomalyFlags = restoredFlags
            )
        } catch (e: Exception) {
            Log.e("parseDiagnostic", "Failed to parse: $diagnostic", e)
            null
        }
    }

    // EDIT 3: Add parseDiagnosticPerImageStatuses helper
    private fun parseDiagnosticPerImageStatuses(diagnostic: String): List<String> {
        val parts = diagnostic.split("|")
        val perImgPart = parts.firstOrNull { it.startsWith("per_img:") } ?: return emptyList()
        return perImgPart.removePrefix("per_img:").split(",").filter { it.isNotBlank() }
    }

    // ─── UI helpers ───────────────────────────────────────────────────────────

    private fun encodeStageToLabel(stage: String): String = when (stage) {
        "Not Ready" -> "not_ready"
        "Near Harvest" -> "near_harvest"
        "Harvest Ready" -> "harvest_ready"
        else -> "unknown"
    }

    private fun showPreviewSection() { previewScrollView.visibility = View.VISIBLE }
    private fun hidePreviewSection() { previewScrollView.visibility = View.GONE }
    private fun hideInstruction() { refreshInstructionVisibility() }
    private fun showInstruction() { refreshInstructionVisibility() }

    private fun updatePreviewVisibility() {
        if (previewContainer.childCount > 0) {
            showPreviewSection()
            viewAllButton.visibility = View.GONE
        } else {
            hidePreviewSection()
            // restore based on how many filled scans exist
            viewAllButton.visibility =
                if (scanThumbs.size > 1) View.VISIBLE else View.GONE
        }
    }

    private fun showLoader() {
        runOnUiThread {
            if (isProcessingImages && !isSwitchingConversation) {
                loader.visibility = View.GONE
                findViewById<androidx.cardview.widget.CardView>(R.id.shimmerCard)?.visibility = View.GONE
                shimmerLayout.stopShimmer()
                shimmerLayout.visibility = View.GONE
                hideInstruction()
                sendButton.isEnabled  = false
                uploadButton.isEnabled = false
                cameraButton.isEnabled = false
            }
        }
    }

    private fun hideLoader() {
        runOnUiThread {
            loader.visibility = View.GONE
            findViewById<androidx.cardview.widget.CardView>(R.id.shimmerCard)
                ?.visibility = View.GONE
            shimmerLayout.stopShimmer()
            shimmerLayout.visibility = View.GONE
            sendButton.isEnabled = true
            uploadButton.isEnabled = true
            cameraButton.isEnabled = true
            isProcessingImages = false
            // Safety: dismiss processing sheet if it's still showing
            processingSheet?.dismiss()
            processingSheet = null
            refreshInstructionVisibility()
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
                val sizeInDp = 37
                val sizeInPx = (sizeInDp * resources.displayMetrics.density).toInt()
                topMargin = sizeInPx
            }

            if (weatherFetchState == WeatherFetchState.DONE) {
                weatherFetchState = WeatherFetchState.IDLE
                cachedWeather = null
                weatherFetchedAt = 0L
                Log.d("Weather", "Cache invalidated — location turned off")
            }

        } else {
            weatherBanner.visibility = View.GONE
            scrollContent.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                val sizeInDp = 5
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
        scanThumbs.clear()
        currentGridSlots.clear()
        viewAllButton.visibility = View.GONE
        refreshInstructionVisibility()
    }

    private fun reloadConversationHistory() {
        lifecycleScope.launch {
            if (conversationId == -1L) return@launch
            val history = db.promptDao().getPromptsForConversation(conversationId)
            db.promptDao().deleteAllHiddenForConversation(conversationId)

            withContext(Dispatchers.Main) {
                uploadedImagesContainer.removeAllViews()
                scanThumbs.clear()
                viewAllButton.visibility = View.GONE

                history.forEach { prompt ->
                    if (prompt.imageUris.isEmpty()) return@forEach
                    val uris             = prompt.imageUris.map { Uri.parse(it) }
                    val interpretation   = parseDiagnosticString(prompt.diagnostic)
                    val perImageStatuses = parseDiagnosticPerImageStatuses(prompt.diagnostic)
                    addConversationCard(
                        uris, interpretation, prompt.timestamp,
                        prompt.diagnostic.startsWith("no_detection"), prompt.cropAgeWeeks,
                        isFirstScan      = scanThumbs.isEmpty(),   // ← NEW: true only for the very first entry
                        perImageStatuses = perImageStatuses
                    )
                    addToGrid(
                        uris, interpretation, prompt,
                        perImageStatuses = perImageStatuses
                    )
                }
                refreshGrid()
                stopPulseInputRow()
                isHistoryLoaded = true
                refreshInstructionVisibility()
            }
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
        } catch (e: Exception) {
            Log.e("Thumbnail", "Failed to load thumbnail: ${e.message}")
            null
        }
    }

    // ─── AI Processing ────────────────────────────────────────────────────────

    private data class ImageResult(
        val uri: Uri,
        val interpretation: CropInterpretation?,
        val isNotSweetPotato: Boolean,
        val isLowConfidence: Boolean
    )

    private fun drawDebugBoundingBoxes(
        source: Bitmap,
        detections: List<com.ai.growsight.ai.YoloDetector.Detection>
    ): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)

        val boxPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            color = android.graphics.Color.RED
            strokeWidth = (result.width * 0.006f).coerceAtLeast(4f)
            isAntiAlias = true
        }
        val labelBgPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.argb(180, 0, 0, 0)
        }
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = (result.width * 0.04f).coerceAtLeast(28f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        detections.forEach { det ->
            canvas.drawRect(det.box, boxPaint)

            val label = "${det.label} ${"%.0f".format(det.score * 100)}%"
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)

            val labelX = det.box.left
            val labelY = (det.box.top - 8f).coerceAtLeast(textBounds.height().toFloat())

            canvas.drawRect(
                labelX,
                labelY - textBounds.height(),
                labelX + textBounds.width() + 16f,
                labelY + 8f,
                labelBgPaint
            )
            canvas.drawText(label, labelX + 8f, labelY, textPaint)
        }

        return result
    }

    private fun showDebugBoundingBoxSheet(bitmaps: List<Bitmap>) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, R.style.CustomBottomSheetDialog
        )

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 48)
        }

        container.addView(TextView(this).apply {
            text = "🔲 YOLO Bounding Boxes — ${bitmaps.size} image(s)"
            textSize = 16f
            typeface = nunitoBold
            setTextColor(android.graphics.Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        })

        bitmaps.forEachIndexed { index, bmp ->
            container.addView(TextView(this).apply {
                text = "Image ${index + 1}"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#757575"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }
            })
            container.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 32 }
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            })
        }

        scroll.addView(container)
        sheet.setContentView(scroll)
        sheet.show()
        sheet.behavior.apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        // Recycle bitmaps when sheet closes
        sheet.setOnDismissListener {
            bitmaps.forEach { it.recycle() }
        }
    }

    private suspend fun processImagesWithAI(persistedUris: List<Uri>, weather: WeatherData?) {

        val existingPrompts = db.promptDao().getPromptsForConversation(conversationId)
        val lastPrompt = existingPrompts.lastOrNull()
        if (lastPrompt != null) {
            val timeDiff = try {
                val sdf = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
                val lastTime = sdf.parse(lastPrompt.timestamp)
                if (lastTime != null) Date().time - lastTime.time else Long.MAX_VALUE
            } catch (e: Exception) {
                Long.MAX_VALUE
            }
            if (timeDiff < 10000) {
                withContext(Dispatchers.Main) { Toast.makeText(this@ConversationsActivity, "Images already analyzed recently", Toast.LENGTH_SHORT).show() }
                hideLoader()
                return
            }
        }

        val urisHash = persistedUris.joinToString(",") { it.toString() }.hashCode()
        if (processedUris.contains(urisHash.toString())) {
            hideLoader()
            return
        }
        processedUris.add(urisHash.toString())

        if (!isProcessingImages) {
            isProcessingImages = true
            showLoader()
        }
        withContext(Dispatchers.Main) { Toast.makeText(this@ConversationsActivity, "Starting AI analysis...", Toast.LENGTH_SHORT).show() }
        if (!waitForModels()) {
            hideLoader()
            fallbackSaveImages(persistedUris)
            return
        }

        val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(Date())
        val OBJECT_DETECTION_THRESHOLD = 0.60f
        val MATURITY_CONFIDENCE_THRESHOLD = 0.60f

        val imageResults = mutableListOf<ImageResult>()

        for ((index, uri) in persistedUris.withIndex()) {
            var bitmap: Bitmap? = null
            var usedCrop: Bitmap? = null
            try {
                bitmap = loadBitmapFromUri(uri)
                if (bitmap == null) {
                    imageResults.add(ImageResult(uri, null, false, false))
                    continue
                }

                var detectedResult: PlantAnalysisResult? = null
                var isNotSweetPotato = false
                var isLowConfidence = false

                if (yolo != null) {
                    val detections = withContext(Dispatchers.Default) { yolo?.detect(bitmap) ?: emptyList() }

                    // ── Debug bounding boxes ──────────────────────────────────────────────
                    if (debugShowBoundingBoxes) {
                        val debugBmp = drawDebugBoundingBoxes(bitmap, detections)
                        debugBitmaps.add(debugBmp)
                    }
                    // ─────────────────────────────────────────────────────────────────────

                    // ── Detection count guard ─────────────────────────────────────
                    // <5  → hard reject, skip even rescue
                    // 5–9 → pass only if avg conf ≥ 80%
                    // ≥10 → always pass
                    val confidentDetections = detections.filter { it.score >= OBJECT_DETECTION_THRESHOLD }
                    val detection = confidentDetections.maxByOrNull { it.score }
                    val detectionConfidence = detection?.score ?: 0f
                    val detectionAvgConf = if (confidentDetections.isNotEmpty())
                        confidentDetections.map { it.score }.average().toFloat() else 0f
                    val passedSparseGuard = when {
                        confidentDetections.size >= 5  -> true
                        confidentDetections.size >= 3  -> detectionAvgConf >= 0.80f
                        else                           -> false
                    }
                    val isTooFewDetections = confidentDetections.size < 3
                    // ─────────────────────────────────────────────────────────────

                    if (detectionConfidence >= OBJECT_DETECTION_THRESHOLD && detection != null && passedSparseGuard) {
                        usedCrop = computeClusterCrop(bitmap, detections)
                        if (usedCrop == null) {
                            val left   = detection.box.left.toInt().coerceIn(0, bitmap.width - 1)
                            val top    = detection.box.top.toInt().coerceIn(0, bitmap.height - 1)
                            val right  = detection.box.right.toInt().coerceIn(left + 1, bitmap.width)
                            val bottom = detection.box.bottom.toInt().coerceIn(top + 1, bitmap.height)
                            val w = right - left
                            val h = bottom - top
                            if (w > 10 && h > 10) usedCrop = try {
                                Bitmap.createBitmap(bitmap, left, top, w, h)
                            } catch (e: Exception) { null }
                        }
                        if (cnn != null) {
                            val cropResult = if (usedCrop != null) {
                                withContext(Dispatchers.Default) { cnn?.classify(usedCrop) }
                            } else null
                            Log.d("CONF", "CNN crop: ${cropResult?.confidence}")

                            val bestResult = if (cropResult == null ||
                                cropResult.confidence < MATURITY_CONFIDENCE_THRESHOLD) {
                                val fullResult = withContext(Dispatchers.Default) { cnn?.classify(bitmap) }
                                Log.d("CONF", "CNN full fallback: ${fullResult?.confidence}")
                                // Keep whichever gave higher confidence
                                if (fullResult != null &&
                                    (cropResult == null || fullResult.confidence > cropResult.confidence))
                                    fullResult else cropResult
                            } else cropResult

                            if (bestResult != null) {
                                if (bestResult.confidence >= MATURITY_CONFIDENCE_THRESHOLD) detectedResult = bestResult
                                else isLowConfidence = true
                            }
                        }
                    } else if (detectionConfidence >= OBJECT_DETECTION_THRESHOLD && detection != null && !isTooFewDetections) {
                        // ── Sparse-guard rescue ───────────────────────────────────────────
                        val rescueDetections = detections.filter { it.score >= OBJECT_DETECTION_THRESHOLD }
                        var sparseCrop = computeClusterCrop(bitmap, rescueDetections)
                        if (sparseCrop == null) {
                            val left   = detection.box.left.toInt().coerceIn(0, bitmap.width - 1)
                            val top    = detection.box.top.toInt().coerceIn(0, bitmap.height - 1)
                            val right  = detection.box.right.toInt().coerceIn(left + 1, bitmap.width)
                            val bottom = detection.box.bottom.toInt().coerceIn(top + 1, bitmap.height)
                            val w = right - left
                            val h = bottom - top
                            if (w > 10 && h > 10) sparseCrop = try {
                                Bitmap.createBitmap(bitmap, left, top, w, h)
                            } catch (e: Exception) { null }
                        }

                        if (cnn != null) {
                            val cropResult = if (sparseCrop != null)
                                withContext(Dispatchers.Default) { cnn?.classify(sparseCrop) } else null
                            val bestResult = if (cropResult == null || cropResult.confidence < MATURITY_CONFIDENCE_THRESHOLD) {
                                val fullResult = withContext(Dispatchers.Default) { cnn?.classify(bitmap) }
                                if (fullResult != null && (cropResult == null || fullResult.confidence > cropResult.confidence))
                                    fullResult else cropResult
                            } else cropResult
                            Log.d("CONF", "Sparse-rescue CNN: ${bestResult?.confidence}")
                            Log.d("GUARD", "Rescue accepted: detections=${confidentDetections.size}, avgConf=$detectionAvgConf, cnnConf=${bestResult?.confidence}")
                            when {
                                // High CNN confidence → sparse detection is real, accept it
                                bestResult != null && bestResult.confidence >= 0.75f ->
                                    detectedResult = bestResult.copy(
                                        confidence = bestResult.confidence * 0.85f
                                    )
                                // Medium CNN confidence → detected but unreliable, flag as low-conf
                                bestResult != null && bestResult.confidence >= MATURITY_CONFIDENCE_THRESHOLD ->
                                    isLowConfidence = true
                                // Low CNN confidence → truly not a sweet potato
                                else ->
                                    isNotSweetPotato = true
                            }
                        } else {
                            isNotSweetPotato = true
                        }
                        sparseCrop?.recycle()
                    } else {
                        isNotSweetPotato = true
                    }
                } else if (cnn != null) {
                    val result = withContext(Dispatchers.Default) { cnn?.classify(bitmap) }
                    if (result != null) {
                        if (result.confidence >= MATURITY_CONFIDENCE_THRESHOLD) detectedResult = result
                        else isLowConfidence = true
                    }
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
            } finally {
                bitmap?.recycle()
                usedCrop?.recycle()
            }
        }

        val validResults = imageResults.filter { it.interpretation != null }
        val notSweetPotatoCount = imageResults.count { it.isNotSweetPotato }
        val lowConfidenceCount = imageResults.count { it.isLowConfidence }

        val stageWeights = mutableMapOf<String, Float>()
        val stageVoteCounts = mutableMapOf<String, Int>()
        validResults.forEach { result ->
            val stage = result.interpretation!!.stage
            val weight = result.interpretation.confidencePercent / 100f
            stageWeights[stage] = (stageWeights[stage] ?: 0f) + weight
            stageVoteCounts[stage] = (stageVoteCounts[stage] ?: 0) + 1
        }
        val majorityStage = stageWeights.maxByOrNull { it.value }?.key

        // Weighted average confidence: sum of (conf * conf) / sum of conf — emphasizes high-confidence votes
        val majorityResults = validResults.filter { it.interpretation?.stage == majorityStage }
        val weightedConfidence: Int = if (majorityResults.isNotEmpty()) {
            val totalWeight = majorityResults.sumOf { it.interpretation!!.confidencePercent.toDouble() }
            val weightedSum = majorityResults.sumOf {
                it.interpretation!!.confidencePercent.toDouble() * it.interpretation.confidencePercent
            }
            if (totalWeight > 0.0) (weightedSum / totalWeight).toInt().coerceIn(0, 100) else 0
        } else 0

        val batchValidRatio = if (persistedUris.isNotEmpty()) validResults.size.toFloat() / persistedUris.size else 0f
        val isBatchInsufficient = when {
            persistedUris.size >= 5 -> batchValidRatio < 0.30f   // 5+ images: allow up to 70% failure
            persistedUris.size >= 3 -> batchValidRatio < 0.50f   // 3–4 images: majority must pass
            else                    -> false
        }

        // e.g. 1 valid out of 10 = 0.10 → insufficient; 3 valid out of 5 = 0.60 → fine
        val insufficientBatchInterpretation: CropInterpretation? = if (isBatchInsufficient) {
            val ibTotal     = persistedUris.size
            val ibValid     = validResults.size
            val ibNoDetect  = notSweetPotatoCount
            val ibLowConf   = lowConfidenceCount
            fun ibImg(n: Int) = if (n == 1) "image" else "images"

            val detectionNote = when {
                ibLowConf > 0 && ibNoDetect == 0 ->
                    "Sweet potato was detected in all $ibTotal ${ibImg(ibTotal)} but CNN maturity confidence was too low to classify $ibLowConf ${ibImg(ibLowConf)} reliably"
                ibNoDetect > 0 && ibLowConf == 0 ->
                    "YOLO found no sweet potato plant in $ibNoDetect of $ibTotal ${ibImg(ibTotal)} — only $ibValid ${ibImg(ibValid)} passed detection"
                ibNoDetect > 0 && ibLowConf > 0 ->
                    "$ibNoDetect ${ibImg(ibNoDetect)} had no plant detected by YOLO, and $ibLowConf ${ibImg(ibLowConf)} had detection but CNN confidence was too low — only $ibValid of $ibTotal were usable"
                else ->
                    "Only $ibValid of $ibTotal ${ibImg(ibTotal)} contained a detectable sweet potato"
            }

            CropInterpretation(
                stage                = "Inconclusive",
                stageEmoji           = "📊",
                stageColor           = "gray",
                confidencePercent    = 0,
                harvestTime          = "Cannot determine — insufficient data",
                recommendations      = listOf(
                    detectionNote,
                    "Submit at least 3 clear images of your crop",
                    "Ensure images clearly show sweet potato leaves or plant",
                    "Avoid uploading unrelated images in the same batch"
                ),
                lowConfidenceWarning = true,
                scenarioLabel        = "Insufficient Batch",
                interpretationSummary = "$detectionNote. The system cannot make a reliable assessment from this batch. Please retake with clearer, focused images of your crop.",
                anomalyFlags         = emptyList()
            )
        } else null

        var summaryInterpretation: CropInterpretation? = if (isBatchInsufficient) {
            insufficientBatchInterpretation
        } else {
            majorityResults
                .maxByOrNull { it.interpretation!!.confidencePercent }?.interpretation
                ?.let { best ->
                    val lowBatch = batchValidRatio < 0.5f && persistedUris.size >= 3
                    best.copy(
                        confidencePercent    = weightedConfidence,
                        lowConfidenceWarning = best.lowConfidenceWarning || lowBatch
                    )
                }
        }

        // Build per-image status list for outlier tagging
        val perImageStatusList = imageResults.map { ir ->
            when {
                ir.isNotSweetPotato                                                          -> "no_detect"
                ir.isLowConfidence                                                           -> "low_conf"
                ir.interpretation == null                                                    -> "invalid"
                majorityStage != null && ir.interpretation.stage != majorityStage            -> "outlier"
                else                                                                         -> "valid"
            }
        }

        Log.d("CONF", "CNN raw confidence: ${validResults.firstOrNull()?.interpretation?.confidencePercent}")
        Log.d("CONF", "summaryInterpretation confidence: ${summaryInterpretation?.confidencePercent}")

        val uniqueStages = validResults.map { it.interpretation!!.stage }.distinct()
        val hasConflict = uniqueStages.size > 1
        val stageOrder = mapOf("Not Ready" to 0, "Near Harvest" to 1, "Harvest Ready" to 2)
        val plantingDate = db.conversationDao().getPlantingDate(conversationId)
        val cropAgeForValidation = if (plantingDate != null && plantingDate > 0L) {
            ((System.currentTimeMillis() - plantingDate) / (1000L * 60 * 60 * 24 * 7))
                .toInt().plus(1).coerceAtLeast(1)
        } else {
            db.conversationDao().getCropAge(conversationId)
        }
        if (summaryInterpretation != null && majorityResults.isNotEmpty()) {
            val mergedFlags = majorityResults
                .flatMap { it.interpretation?.anomalyFlags ?: emptyList() }
                .distinctBy { it.badgeLabel }                       // deduplicate by code field
                .sortedBy { when (it.severity) { "critical" -> 0; "high" -> 1; "medium" -> 2; else -> 3 } }
            if (mergedFlags.isNotEmpty()) {
                summaryInterpretation = summaryInterpretation.copy(anomalyFlags = mergedFlags)
            }
        }

        val finalInterpretation: CropInterpretation? = if (isBatchInsufficient) {
            insufficientBatchInterpretation
        } else if (summaryInterpretation != null) {
            val sc = scenarioClassifier
            if (sc != null) {

                // ── Add these two before scenarioResult ──────────────────────────
                val scanHistoryEntries: List<ScenarioClassifier.ScanHistoryEntry> =
                    if (isQuickScan) emptyList()
                    else withContext(Dispatchers.IO) {
                        db.promptDao().getPromptsForConversation(conversationId)
                            .mapNotNull { prompt ->
                                val stagePart = prompt.diagnostic
                                    .split("|").firstOrNull()
                                    ?.takeIf {
                                        it != "no_detection" &&
                                                it != "Stage Conflict" &&
                                                it != "Insufficient Batch" &&
                                                it.isNotBlank()
                                    }
                                    ?: return@mapNotNull null
                                ScenarioClassifier.ScanHistoryEntry(
                                    weekNumber    = PlantationWeekHelper.weekNumberForPrompt(prompt, plantingDate),
                                    stage         = stagePart,
                                    scenarioLabel = stagePart
                                        .replace("Not Ready", "on_track_early")
                                        .replace("Near Harvest", "approaching_harvest")
                                        .replace("Harvest Ready", "harvest_on_time"),
                                    timestamp     = prompt.timestamp
                                )
                            }
                    }

                val plantationName = withContext(Dispatchers.IO) {
                    db.conversationDao().getConversationById(conversationId)?.name ?: "Your plantation"
                }
                // ─────────────────────────────────────────────────────────────────

                val scenarioResult = sc.interpretWithHistory(
                    stage            = summaryInterpretation.stage,
                    confidence       = summaryInterpretation.confidencePercent / 100f,
                    cropWeek         = cropAgeForValidation,
                    weather          = weather,
                    imageCount       = persistedUris.size,
                    validCount       = validResults.size,
                    hasConflict      = hasConflict,
                    scanHistory      = scanHistoryEntries,
                    conversationName = plantationName,
                    isQuickScan      = isQuickScan,
                    stageBreakdown   = validResults
                        .mapNotNull { it.interpretation?.stage }
                        .groupingBy { it }
                        .eachCount()
                )


                InterpretationEngine.interpretWithScenario(
                    result = PlantAnalysisResult(
                        label      = encodeStageToLabel(summaryInterpretation.stage),
                        confidence = summaryInterpretation.confidencePercent / 100f,
                        allScores  = emptyMap()
                    ),
                    scenarioResult = scenarioResult,
                    weather        = weather
                )

            } else {
                if (weather != null) InterpretationEngine.interpretWithWeather(
                    PlantAnalysisResult(
                        encodeStageToLabel(summaryInterpretation.stage),
                        summaryInterpretation.confidencePercent / 100f,
                        emptyMap()
                    ), weather
                ) else summaryInterpretation
            }
        } else null
        Log.d("CONF", "finalInterpretation confidence: ${finalInterpretation?.confidencePercent}")

        val perImgPart = "per_img:${perImageStatusList.joinToString(",")}"
        val diagnosticForDb = when {
            isBatchInsufficient -> {
                "Insufficient Batch|${validResults.size}|${persistedUris.size}|per_img:${perImageStatusList.joinToString(",")}"
            }
            finalInterpretation != null -> {
                val weatherPart = finalInterpretation.weatherSummary?.replace("|", "~") ?: ""
                val weatherRecs = finalInterpretation.recommendations
                    .filter { it.startsWith("🌡️") || it.startsWith("💧") || it.startsWith("🌧️") || it.startsWith("🌦️") || it.startsWith("☀️") }
                    .joinToString("^").replace("|", "~")
                val narrative = finalInterpretation.interpretationSummary?.replace("|", "~pipe~") ?: ""
                val bullets = finalInterpretation.recommendations.joinToString("^").replace("|", "~pipe~")
                val flagsPart = if (finalInterpretation.anomalyFlags.isNotEmpty())
                    "|flags:" + finalInterpretation.anomalyFlags.joinToString("^^^") { flag ->
                        listOf(
                            flag.badgeLabel.replace("|", "~PIPE~").replace("^^^", "~C3~").replace(";;;", "~S3~"),
                            flag.severity,
                            flag.detail.replace("|", "~PIPE~").replace("^^^", "~C3~").replace(";;;", "~S3~"),
                            flag.suggestion.replace("|", "~PIPE~").replace("^^^", "~C3~").replace(";;;", "~S3~")
                        ).joinToString(";;;")
                    }
                else ""
                "${finalInterpretation.stage}|${finalInterpretation.confidencePercent}|${finalInterpretation.harvestTime}|$weatherPart|$weatherRecs|$narrative|$bullets|$perImgPart$flagsPart"
            }
            summaryInterpretation != null -> {
                val weatherPart = summaryInterpretation.weatherSummary?.replace("|", "~") ?: ""
                val weatherRecs = summaryInterpretation.recommendations
                    .filter { it.startsWith("🌡️") || it.startsWith("💧") || it.startsWith("🌧️") || it.startsWith("🌦️") || it.startsWith("☀️") }
                    .joinToString("^").replace("|", "~")
                val bullets = summaryInterpretation.recommendations.joinToString("^").replace("|", "~pipe~")
                "${summaryInterpretation.stage}|${summaryInterpretation.confidencePercent}|${summaryInterpretation.harvestTime}|${weatherPart}|${weatherRecs}||${bullets}|$perImgPart"
            }
            else -> if (isBatchInsufficient)
                "No Detection|insufficient_batch:${validResults.size}/${persistedUris.size}|per_img:${perImageStatusList.joinToString(",")}"
            else
                "No Detection|per_img:${perImageStatusList.joinToString(",")}"
        }

        val progressionInsight = getProgressionInsight()
        val estimatedAge = getEstimatedCurrentAge()

        // ── Show debug sheet if bounding box mode is on ───────────────────────
        if (debugShowBoundingBoxes && debugBitmaps.isNotEmpty()) {
            val snapshot = debugBitmaps.toList()
            debugBitmaps.clear()
            withContext(Dispatchers.Main) { showDebugBoundingBoxSheet(snapshot) }
        }
        // ─────────────────────────────────────────────────────────────────────

        val promptEntity = PromptEntity(
            conversationId = conversationId,
            imageUris = persistedUris.map { it.toString() },
            diagnostic = diagnosticForDb,
            timestamp = timestamp,
            timestampMs    = System.currentTimeMillis(),
            weekNumber = null,
            cropAgeWeeks = cropAgeForValidation,
            uriHash = persistedUris.joinToString(",") { it.toString() }.hashCode().toString()
        )

        val interpretationForGrid = finalInterpretation ?: summaryInterpretation
        val perImageForGrid = imageResults.map { it.interpretation }
        val majorityForGrid = majorityStage

        val replacedId = retakingPromptId

        if (replacedId != -1L) {
            // ── RETAKE PATH ────────────────────────────────────────────────────
            // Single atomic query: updates all data columns AND clears
            // isHiddenForRetake = 0 together, so no stale visible row can exist.
            db.promptDao().updatePromptInPlaceAndClearHidden(
                id           = replacedId,
                imageUris    = persistedUris.joinToString(",") { it.toString() },
                diagnostic   = diagnosticForDb,
                timestamp    = timestamp,
                timestampMs  = System.currentTimeMillis(),
                cropAgeWeeks = cropAgeForValidation,
                uriHash      = persistedUris.joinToString(",") { it.toString() }
                    .hashCode().toString()
            )
            // Purge any other orphaned hidden rows (safety net)
            db.promptDao().deleteAllHiddenForConversation(conversationId)

            retakingPromptId = -1L
            // ... rest of retake path unchanged

            retakingPromptId = -1L

            // Update the in-memory promptEntity to reflect the overwritten ID
            // so the rest of the function (addToGrid, showScanDetailSheet) uses
            // the correct (original) prompt ID.
            val overwrittenPrompt = promptEntity.copy(id = replacedId)

            withContext(Dispatchers.Main) {
                scanThumbs.removeAll { it.prompt.id == replacedId }
            }

            // Trigger notification scheduler with the corrected prompt id
            if (!isQuickScan) {
                CheckInNotificationScheduler.schedule(this@ConversationsActivity, conversationId)
                WeekEndAlertScheduler.schedule(this@ConversationsActivity, conversationId)
            }

            val savedPrompts = db.promptDao().getPromptsForConversation(conversationId)
            withContext(Dispatchers.Main) {
                if (!isQuickScan && !isRetaking) showLocked(savedPrompts)
            }

            if (isQuickScan) {
                val diagnosisLabel = finalInterpretation?.stage
                    ?: summaryInterpretation?.stage
                    ?: "No detection"
                QuickScanManager.updateDiagnosis(this, conversationId, diagnosisLabel)
            }

            withContext(Dispatchers.Main) {
                scanThumbs.removeAll { it.prompt.id == -999L }
                isProcessingImages = false
                addToGrid(
                    persistedUris,
                    interpretationForGrid,
                    overwrittenPrompt,
                    perImageInterpretations = perImageForGrid,
                    majorityStage = majorityForGrid,
                    perImageStatuses = perImageStatusList
                )
                hideLoader()
                refreshInstructionVisibility()
                hideInstruction()
                isRetaking = false

                processingSheet?.dismiss()
                processingSheet = null

                val newThumb = scanThumbs.lastOrNull()
                if (newThumb != null) showScanDetailSheet(newThumb)

                refreshGrid()
                scrollToBottom()

                val toast = when {
                    isBatchInsufficient -> "⚠️ Too few valid images (${validResults.size}/${persistedUris.size}) — submit clearer images"
                    finalInterpretation != null ->
                        "✅ ${finalInterpretation.stage} — ${validResults.size}/${persistedUris.size} valid images"
                    notSweetPotatoCount == persistedUris.size -> "❌ No sweet potato plants detected"
                    else -> "⚠️ Could not classify images"
                }
                Toast.makeText(this@ConversationsActivity, toast, Toast.LENGTH_LONG).show()
            }
        } else {
            val insertedId = db.promptDao().insertPrompt(promptEntity)        // ← capture real ID
            val savedPromptEntity = promptEntity.copy(id = insertedId)        // ← build entity with real ID
            db.promptDao().deleteAllHiddenForConversation(conversationId)

            if (!isQuickScan) {
                CheckInNotificationScheduler.schedule(this@ConversationsActivity, conversationId)
                WeekEndAlertScheduler.schedule(this@ConversationsActivity, conversationId)
            }

            val savedPrompts = db.promptDao().getPromptsForConversation(conversationId)
            withContext(Dispatchers.Main) {
                if (!isQuickScan && !isRetaking) showLocked(savedPrompts)
            }

            if (isQuickScan) {
                val diagnosisLabel = finalInterpretation?.stage
                    ?: summaryInterpretation?.stage
                    ?: "No detection"
                QuickScanManager.updateDiagnosis(this, conversationId, diagnosisLabel)
            }

            withContext(Dispatchers.Main) {
                scanThumbs.removeAll { it.prompt.id == -999L }
                isProcessingImages = false
                addToGrid(
                    persistedUris,
                    interpretationForGrid,
                    savedPromptEntity,
                    perImageInterpretations = perImageForGrid,
                    majorityStage = majorityForGrid,
                    perImageStatuses = perImageStatusList
                )
                hideLoader()
                refreshInstructionVisibility()
                hideInstruction()
                isRetaking = false

                processingSheet?.dismiss()
                processingSheet = null

                val newThumb = scanThumbs.lastOrNull()
                if (newThumb != null) showScanDetailSheet(newThumb)

                refreshGrid()
                scrollToBottom()

                val toast = when {
                    isBatchInsufficient -> "⚠️ Too few valid images (${validResults.size}/${persistedUris.size}) — submit clearer images"
                    finalInterpretation != null ->
                        "✅ ${finalInterpretation.stage} — ${validResults.size}/${persistedUris.size} valid images"
                    notSweetPotatoCount == persistedUris.size -> "❌ No sweet potato plants detected"
                    else -> "⚠️ Could not classify images"
                }
                Toast.makeText(this@ConversationsActivity, toast, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun computeClusterCrop(
        bitmap: Bitmap,
        detections: List<YoloDetector.Detection>,
        clusterThreshold: Float = 0.30f
    ): Bitmap? {
        val candidates = detections.filter { it.score >= clusterThreshold }
        if (candidates.isEmpty()) return null

        val totalScore = candidates.sumOf { it.score.toDouble() }.toFloat()
        val centroidX = candidates.sumOf {
            ((it.box.left + it.box.right) / 2f * it.score).toDouble()
        }.toFloat() / totalScore
        val centroidY = candidates.sumOf {
            ((it.box.top + it.box.bottom) / 2f * it.score).toDouble()
        }.toFloat() / totalScore

        val unionLeft   = candidates.minOf { it.box.left }
        val unionTop    = candidates.minOf { it.box.top }
        val unionRight  = candidates.maxOf { it.box.right }
        val unionBottom = candidates.maxOf { it.box.bottom }

        val halfW = (unionRight - unionLeft) / 2f * 1.10f
        val halfH = (unionBottom - unionTop) / 2f * 1.10f

        val left   = (centroidX - halfW).toInt().coerceIn(0, bitmap.width - 1)
        val top    = (centroidY - halfH).toInt().coerceIn(0, bitmap.height - 1)
        val right  = (centroidX + halfW).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (centroidY + halfH).toInt().coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top

        if (w <= 10 || h <= 10) return null
        return try {
            Bitmap.createBitmap(bitmap, left, top, w, h)
        } catch (e: Exception) {
            Log.e("ClusterCrop", "Failed to create cluster crop: ${e.message}")
            null
        }
    }

    private fun isReEvaluateEligible(prompt: PromptEntity): Boolean {
        // Use timestampMs if available, otherwise parse the display timestamp
        val ts = if (prompt.timestampMs > 0L) {
            prompt.timestampMs
        } else {
            try {
                SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
                    .parse(prompt.timestamp)?.time ?: return false
            } catch (e: Exception) {
                return false
            }
        }
        if (System.currentTimeMillis() - ts > 10 * 60 * 1000L) return false

        // Only show when no weather was saved
        val parts = prompt.diagnostic.split("|")
        val weatherPart = if (parts.size >= 4) parts[3] else ""
        return weatherPart.isBlank()
    }

    private fun fetchWeatherThenProcess(uris: List<Uri>) {
        val now = System.currentTimeMillis()
        val cacheExpired = (now - weatherFetchedAt) > WEATHER_CACHE_DURATION_MS

        // Use cache if still valid
        if (weatherFetchState == WeatherFetchState.DONE && !cacheExpired) {
            lifecycleScope.launch { processImagesWithAI(uris, cachedWeather) }
            return
        }

        // Check location permission and services — show dialog if needed
        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val isLocationEnabled =
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission || !isLocationEnabled) {
            showLocationDialogThenProcess(uris)
            return
        }

        // Permission + location on → fetch normally
        weatherFetchState = WeatherFetchState.FETCHING
        val timeoutJob = lifecycleScope.launch {
            delay(8_000)
            if (weatherFetchState == WeatherFetchState.FETCHING) {
                cachedWeather     = null
                weatherFetchState = WeatherFetchState.IDLE
                weatherFetchedAt  = 0L
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
                    lifecycleScope.launch { processImagesWithAI(uris, weather) }
                }
                override fun onSkipped() {
                    if (weatherFetchState != WeatherFetchState.FETCHING) return
                    timeoutJob.cancel()
                    cachedWeather     = null
                    weatherFetchState = WeatherFetchState.IDLE
                    weatherFetchedAt  = 0L
                    lifecycleScope.launch { processImagesWithAI(uris, null) }
                }
            }
        )
    }

    // ── fallbackSaveImages ────────────────────────────────────────────────────
    private suspend fun fallbackSaveImages(uris: List<Uri>) {
        try {
            val persistedUris = uris.mapNotNull { ensureLocalCopy(it) }
            if (persistedUris.isNotEmpty()) {
                val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(Date())
                val promptEntity = PromptEntity(
                    conversationId = conversationId,
                    imageUris = persistedUris.map { it.toString() },
                    diagnostic = "No Detection",
                    timestamp = timestamp,
                    weekNumber = null,
                    cropAgeWeeks = db.conversationDao().getCropAge(conversationId),
                    uriHash = persistedUris.joinToString(",") { it.toString() }.hashCode().toString()
                )
                val insertedId = db.promptDao().insertPrompt(promptEntity)       // ← capture real ID
                val savedPromptEntity = promptEntity.copy(id = insertedId)       // ← build entity with real ID

                withContext(Dispatchers.Main) {
                    addToGrid(persistedUris, null, savedPromptEntity)            // ← was: promptEntity (id=0)
                    refreshGrid()
                    hideInstruction()

                    val newThumb = scanThumbs.lastOrNull()
                    if (newThumb != null) showScanDetailSheet(newThumb)

                    Toast.makeText(this@ConversationsActivity, "Images saved (AI unavailable)", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e("Fallback", "Fallback save failed", e)
        }
    }

    private suspend fun waitForModels(): Boolean = withContext(Dispatchers.IO) {
        delay(100)
        ModelManager.areModelsAvailable()
    }

    private fun areModelsAvailable() = ModelManager.areModelsAvailable()

    // ─── Card UI ──────────────────────────────────────────────────────────────

    private fun stageColorDrawable(stage: String?): Int = when (stage) {
        "Not Ready" -> R.drawable.circle_red
        "Near Harvest" -> R.drawable.circle_yellow
        "Harvest Ready" -> R.drawable.circle_green
        else -> R.drawable.circle_gray
    }

    // ── Content fingerprint for duplicate scan detection ──────────────────────
    private suspend fun computeImageFingerprint(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val md = java.security.MessageDigest.getInstance("MD5")
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            Log.w("DupCheck", "Fingerprint failed for $uri: ${e.message}")
            null
        }
    }

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

        val dotSize = (thumbSize * 0.22).toInt().coerceAtLeast(16)
        val dot = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                setMargins(0, 0, 4, 4)
            }
            setBackgroundResource(stageColorDrawable(stage))
        }
        wrapper.addView(dot)

        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = loadThumbnail(uri, thumbSize)
            withContext(Dispatchers.Main) { if (bmp != null) img.setImageBitmap(bmp) else img.setImageURI(uri) }
        }
        return wrapper
    }

    private fun addConversationCard(
        images: List<Uri>,
        imageResults: List<Triple<Uri, CropInterpretation?, Boolean>>,
        summaryInterpretation: CropInterpretation?,
        notSweetPotatoCount: Int,
        lowConfidenceCount: Int,
        timestamp: String,
        weather: WeatherData?,
        progressionInsight: String? = null,
        cropAgeWeeks: Int? = null,
        hasConflict: Boolean = false,
        perImageStages: List<Pair<Uri, String?>> = emptyList(),
        perImageStatuses: List<String> = emptyList(),
        isFirstScan: Boolean = false
    ) {
        val card = layoutInflater.inflate(R.layout.item_conversation_card, uploadedImagesContainer, false)

        val mainImageRow = card.findViewById<LinearLayout>(R.id.cardImageRow)
        val stageLabel = card.findViewById<TextView>(R.id.stageLabel)
        val stageColorDot = card.findViewById<View>(R.id.stageColorDot)
        val confidenceChip = card.findViewById<TextView>(R.id.confidenceChip)
        val harvestTime = card.findViewById<TextView>(R.id.harvestTime)
        val harvestTimeTitle = card.findViewById<TextView>(R.id.harvestTimeTitle)
        val recommendationContainer = card.findViewById<LinearLayout>(R.id.recommendationContainer)
        val interpretationText = card.findViewById<TextView>(R.id.interpretationSummaryText)
        val cardTimestamp = card.findViewById<TextView>(R.id.cardTimestamp)
        val lowConfidenceWarning = card.findViewById<TextView>(R.id.lowConfidenceWarning)
        val scenarioBadge = card.findViewById<TextView>(R.id.scenarioBadge)
        val toggleSummary = card.findViewById<LinearLayout>(R.id.toggleSummary)
        val toggleFullDetail = card.findViewById<LinearLayout>(R.id.toggleFullDetail)
        val weatherStrip = card.findViewById<View>(R.id.weatherStrip)
        val weatherTemp = card.findViewById<TextView>(R.id.weatherTemp)
        val weatherHumidity = card.findViewById<TextView>(R.id.weatherHumidity)
        val weatherPrecip = card.findViewById<TextView>(R.id.weatherPrecip)

        cardTimestamp.text = timestamp

        imageResults.forEachIndexed { index, (uri, imgInterpretation, isNoDetection) ->
            val wrapper = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(4, 4, 4, 4) }
            }
            val sizeInDp = 125
            val sizeInPx = (sizeInDp * resources.displayMetrics.density).toInt()

            // ── Resolve per-image status (stored status wins; fall back to live flags) ──
            val status = perImageStatuses.getOrNull(index) ?: when {
                isNoDetection          -> "no_detect"
                imgInterpretation == null -> "low_conf"
                else                   -> "valid"
            }
            val isOutlier = status != "valid"

            val img = ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(sizeInPx, sizeInPx)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.circle_gray)

                val radiusPx = (5 * resources.displayMetrics.density)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
                clipToOutline = true

                // Grayscale outlier / undetected / low-confidence images
                if (isOutlier) {
                    val matrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
                    colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
                }

                setOnClickListener { showImageModal(uri) }
            }
            wrapper.addView(img)

            // Badge overlay for non-valid images
            if (isOutlier) {
                val badgeEmoji = when (status) {
                    "no_detect" -> "❌"
                    "low_conf"  -> "🌫️"
                    "outlier"   -> "⚠️"
                    else        -> "⚠️"
                }
                wrapper.addView(TextView(this).apply {
                    text = badgeEmoji
                    textSize = 11f
                    setPadding(2, 2, 2, 2)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.END
                        setMargins(0, 6, 6, 0)
                    }
                })
            }

            mainImageRow.addView(wrapper)
            lifecycleScope.launch(Dispatchers.IO) {
                val thumbnail = loadThumbnail(uri, 300)
                withContext(Dispatchers.Main) {
                    if (thumbnail != null) img.setImageBitmap(thumbnail) else img.setImageURI(uri)
                }
            }
        }

        if (summaryInterpretation == null) {
            scenarioBadge.visibility = View.GONE
            confidenceChip.visibility = View.GONE
            harvestTimeTitle.visibility = View.GONE
            harvestTime.visibility = View.GONE
            lowConfidenceWarning.visibility = View.GONE
            weatherStrip.visibility = View.GONE
            card.findViewById<LinearLayout>(R.id.viewModeToggleBar).visibility = View.GONE

            val noDetectCount = maxOf(notSweetPotatoCount, perImageStatuses.count { it == "no_detect" })
            val lowConfCount  = maxOf(lowConfidenceCount,  perImageStatuses.count { it == "low_conf" })
            val singular      = images.size == 1
            val onlyLowConf   = lowConfCount > 0 && noDetectCount == 0
            val onlyNoDetect  = noDetectCount > 0 && lowConfCount == 0

            stageLabel.text = if (onlyLowConf) "Images too unclear to classify" else "No sweet potato detected"
            stageLabel.textSize = 20f
            stageColorDot.setBackgroundResource(R.drawable.circle_gray)

            interpretationText.text = when {
                onlyLowConf ->
                    if (singular) "The plant was detected but the image was too unclear to determine the maturity stage. Please retake in natural daylight with the leaf canopy filling the frame."
                    else "The plants were detected but the images were too unclear to determine the maturity stage. Please retake in natural daylight with the leaf canopy filling the frame."
                onlyNoDetect ->
                    if (singular) "No sweet potato plant was found in the submitted image. Make sure the leaf canopy clearly fills the frame."
                    else "No sweet potato plant was found in the submitted images. Make sure the leaf canopy clearly fills the frame."
                lowConfCount > 0 ->
                    "Some images had no plant detected and others were too unclear to classify. Please retake with clearer, well-lit photos of the leaf canopy."
                else ->
                    if (singular) "The submitted image could not be classified. Please try again with a clearer photo."
                    else "The submitted images could not be classified. Please try again with clearer photos."
            }
            interpretationText.visibility = View.VISIBLE
            recommendationContainer.removeAllViews()
            when {
                onlyLowConf -> {
                    addRecommendationBullet(recommendationContainer, "📷 Retake in natural daylight — avoid shadows and blur")
                    addRecommendationBullet(recommendationContainer, "🌿 Move closer so the leaf canopy fills most of the frame")
                    addRecommendationBullet(recommendationContainer, "🔍 Avoid heavy shade, overexposure, or motion blur")
                }
                else -> {
                    addRecommendationBullet(recommendationContainer, "🌿 Ensure sweet potato leaves are clearly visible in the frame")
                    addRecommendationBullet(recommendationContainer, "📐 Move the camera closer — the plant should fill the frame")
                    addRecommendationBullet(recommendationContainer, "📷 Use natural daylight and keep the camera steady")
                }
            }

            // Hide retake button for quick scan
            if (!isQuickScan) {
                for (i in 0 until uploadedImagesContainer.childCount) {
                    uploadedImagesContainer.getChildAt(i)
                        .findViewById<MaterialButton>(R.id.retakeButton)
                        ?.let { it.isEnabled = false; it.alpha = 0.35f }
                }
                card.findViewById<MaterialButton>(R.id.retakeButton)
                    ?.let { it.isEnabled = true; it.alpha = 1f }
            } else {
                card.findViewById<MaterialButton>(R.id.retakeButton)?.visibility = View.GONE
            }

            val placeholder = uploadedImagesContainer.findViewWithTag<View>("shimmer_placeholder")
            if (placeholder != null) uploadedImagesContainer.removeView(placeholder)
            uploadedImagesContainer.addView(card)
            if (uploadedImagesContainer.childCount > 0) {
                hideInstruction()
            }
            scrollToBottom()
            return
        }

        // Stage conflict — inconclusive result
        if (summaryInterpretation.scenarioLabel == "Stage Conflict") {
            scenarioBadge.text = "⚠️ Stage Conflict"
            scenarioBadge.visibility = View.VISIBLE
            //scenarioBadge.setBackgroundResource(R.drawable.rounded_corner_orange)

            stageLabel.text     = "⚠️ Inconclusive Result"
            stageLabel.textSize = 18f
            stageColorDot.setBackgroundResource(R.drawable.circle_gray)

            confidenceChip.visibility      = View.GONE
            lowConfidenceWarning.visibility = View.GONE
            weatherStrip.visibility        = View.GONE
            card.findViewById<LinearLayout>(R.id.viewModeToggleBar).visibility = View.GONE

            harvestTimeTitle.visibility = View.GONE
            harvestTime.visibility      = View.GONE

            interpretationText.text       = summaryInterpretation.interpretationSummary ?: ""
            interpretationText.visibility = View.VISIBLE

            recommendationContainer.removeAllViews()
            addSectionHeader(recommendationContainer, "📋 What does this mean:")
            if (images.size > 1) {
                addRecommendationBullet(recommendationContainer, buildWhatItMeansLine("Stage Conflict", perImageStatuses, images.size))
            }
            summaryInterpretation.recommendations.forEachIndexed { i, rec ->
                if (i != 0 && i != 2) addRecommendationBullet(recommendationContainer, rec)
            }
            recommendationContainer.visibility = View.VISIBLE

            val placeholder = uploadedImagesContainer.findViewWithTag<View>("shimmer_placeholder")
            if (placeholder != null) uploadedImagesContainer.removeView(placeholder)
            card.findViewById<MaterialButton>(R.id.retakeButton)?.visibility = View.GONE

            uploadedImagesContainer.addView(card)
            if (uploadedImagesContainer.childCount > 0) hideInstruction()
            scrollToBottom()
            return
        }

// ── Anomaly banner + focus mode ──────────────────────────────────────────
        val addCardAnomalyBanner = card.findViewWithTag<LinearLayout>("anomalyBanner")
        val allAnomalyFlags = summaryInterpretation.anomalyFlags
        val addCardBannerFlags = allAnomalyFlags.filter { it.severity in listOf("critical", "high", "medium") }
        val addCardHighest = addCardBannerFlags.minByOrNull {
            when (it.severity) { "critical" -> 0; "high" -> 1; "medium" -> 2; else -> 3 }
        }
        val isFocusMode = !isQuickScan && addCardHighest?.severity in listOf("critical", "high")
        if (addCardAnomalyBanner != null && addCardBannerFlags.isNotEmpty()) {
            val (bgHex, textHex, _) = when (addCardHighest?.severity) {
                "critical" -> Triple("#B71C1C", "#FFFFFF", "🔴")
                "high"     -> Triple("#BF360C", "#FFFFFF", "🟠")
                "medium"   -> Triple("#F57F17", "#212121", "🟡")
                else       -> Triple("#1565C0", "#FFFFFF", "🔵")
            }
            val lightBgHex = when (addCardHighest?.severity) {
                "critical" -> "#FCE4EC"
                "high"     -> "#FBE9E7"
                "medium"   -> "#FFF3E0"
                else       -> "#E3F2FD"
            }
            val d2 = resources.displayMetrics.density
            val cornerR2 = 10f * d2
            val lightBg2 = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor(lightBgHex))
                cornerRadius = cornerR2
            }
            val accentStripe2 = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor(bgHex))
                cornerRadii = floatArrayOf(cornerR2, cornerR2, 0f, 0f, 0f, 0f, cornerR2, cornerR2)
            }
            val layerBg2 = android.graphics.drawable.LayerDrawable(arrayOf(lightBg2, accentStripe2))
            layerBg2.setLayerGravity(1, android.view.Gravity.START or android.view.Gravity.FILL_VERTICAL)
            layerBg2.setLayerWidth(1, (4 * d2).toInt())
            addCardAnomalyBanner.background = layerBg2
            addCardAnomalyBanner.visibility = View.VISIBLE
            addCardAnomalyBanner.removeAllViews()
            val count = addCardBannerFlags.size
            val sevLabel = (addCardHighest?.severity ?: "medium").replaceFirstChar { it.uppercase() }
            val badgeSize2 = (24 * d2).toInt()

            val countCircle2 = TextView(this).apply {
                text = count.toString()
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor(bgHex))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(badgeSize2, badgeSize2).also {
                    it.marginEnd = (10 * d2).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.WHITE)
                }
            }
            val label2 = TextView(this).apply {
                text = "$sevLabel  ·  $count ${if (count == 1) "issue" else "issues"} detected"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor(bgHex))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sevPill2 = TextView(this).apply {
                text = (addCardHighest?.severity ?: "medium").uppercase()
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor(bgHex))
                setPadding((10 * d2).toInt(), (3 * d2).toInt(), (10 * d2).toInt(), (3 * d2).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke((1.5f * d2).toInt(), android.graphics.Color.parseColor(bgHex))
                    cornerRadius = 20f * d2
                }
            }
            addCardAnomalyBanner.addView(countCircle2)
            addCardAnomalyBanner.addView(label2)
            addCardAnomalyBanner.addView(sevPill2)
        } else {
            addCardAnomalyBanner?.visibility = View.GONE
        }
        // ─────────────────────────────────────────────────────────────────────────

        if (!summaryInterpretation.scenarioLabel.isNullOrBlank()) {
            scenarioBadge.text = summaryInterpretation.scenarioLabel.replace("_", " ")
            scenarioBadge.visibility = View.VISIBLE
        } else {
            scenarioBadge.visibility = View.GONE
        }

        stageLabel.text = "${summaryInterpretation.stageEmoji} ${summaryInterpretation.stage}"
        stageLabel.textSize = 20f
        stageColorDot.setBackgroundResource(when (summaryInterpretation.stageColor) {
            "green" -> R.drawable.circle_green
            "yellow" -> R.drawable.circle_yellow
            "red" -> R.drawable.circle_red
            else -> R.drawable.circle_gray
        })
        confidenceChip.visibility = View.VISIBLE
        confidenceChip.text = "${summaryInterpretation.confidencePercent}% confidence"
        confidenceChip.textSize = 14f
        val (chipBg, chipText) = when {
            summaryInterpretation.confidencePercent >= 75 ->
                Pair(ContextCompat.getDrawable(this, R.drawable.rounded_corner_green_conf), Color.WHITE)
            summaryInterpretation.confidencePercent >= 65 ->
                Pair(ContextCompat.getDrawable(this, R.drawable.rounded_corner_orange_conf), Color.WHITE)
            else ->
                Pair(ContextCompat.getDrawable(this, R.drawable.rounded_corner_red_conf), Color.WHITE)
        }
        confidenceChip.background = chipBg
        confidenceChip.setTextColor(chipText)
        val hasLowConfFlag = summaryInterpretation.anomalyFlags.any { it.badgeLabel == "Low Confidence" }
        lowConfidenceWarning.visibility = if (summaryInterpretation.lowConfidenceWarning && !hasLowConfFlag) {
            lowConfidenceWarning.textSize = 14f
            View.VISIBLE
        } else View.GONE

        val htRaw = summaryInterpretation.harvestTime
        val htVisible = InterpretationEngine.isHarvestTimeDisplayable(htRaw) && !isFocusMode
        harvestTimeTitle.visibility = if (htVisible) View.VISIBLE else View.GONE
        harvestTime.visibility      = if (htVisible) View.VISIBLE else View.GONE
        if (htVisible) {
            val htDisplay = if (isQuickScan)
                htRaw.replace(" remaining", " to harvest from this stage", ignoreCase = true)
            else htRaw
            harvestTimeTitle.text = if (isQuickScan) "⏱️ Typical Time to Harvest"
            else             "🗓️ Estimated Harvest Time"
            harvestTime.text = "→ $htDisplay"
            harvestTime.setTextColor(ContextCompat.getColor(this, R.color.black))
            harvestTime.textSize = 16f
        }

        val narrative = summaryInterpretation.interpretationSummary
        if (!narrative.isNullOrBlank()) {
            interpretationText.text = narrative
            interpretationText.visibility = View.VISIBLE
        } else {
            interpretationText.text = "No summary interpretation available."
            interpretationText.visibility = View.VISIBLE
        }

        recommendationContainer.removeAllViews()

        if (images.size > 1) {
            val validCount = imageResults.count { it.second != null }
            fun imgW(n: Int) = if (n == 1) "image" else "images"
            addSectionHeader(recommendationContainer, "📊 Analysis Summary")
            addRecommendationBullet(recommendationContainer,
                "$validCount of ${images.size} ${imgW(images.size)} contributed to this result")
            if (notSweetPotatoCount > 0)
                addRecommendationBullet(recommendationContainer,
                    if (notSweetPotatoCount == 1) "1 image had no sweet potato detected (❌)"
                    else "$notSweetPotatoCount images had no sweet potato detected (❌)")
            if (lowConfidenceCount > 0)
                addRecommendationBullet(recommendationContainer,
                    if (lowConfidenceCount == 1) "1 image was too unclear to classify (🌫️)"
                    else "$lowConfidenceCount images were too unclear to classify (🌫️)")
            if (validCount == 1)
                addRecommendationBullet(recommendationContainer,
                    "⚠️ Result based on a single image — submit more photos for higher reliability")
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

        // Focus mode: anomaly flag cards BEFORE recommendations
        if (isFocusMode && allAnomalyFlags.isNotEmpty()) {
            addAnomalyFlagsSection(recommendationContainer, allAnomalyFlags)
        }

        addSectionHeader(recommendationContainer, "📋 Recommendations")
        summaryInterpretation.recommendations.forEach {
            addRecommendationBullet(recommendationContainer, it)
        }

        // Normal mode: anomaly flag cards AFTER recommendations
        if (!isFocusMode && allAnomalyFlags.isNotEmpty()) {
            addAnomalyFlagsSection(recommendationContainer, allAnomalyFlags)
        }

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

        val summaryIcon = toggleSummary.getChildAt(0) as? ImageView
        val summaryText = toggleSummary.getChildAt(1) as? TextView
        val detailIcon = toggleFullDetail.getChildAt(0) as? ImageView
        val detailText = toggleFullDetail.getChildAt(1) as? TextView

        fun applyViewMode(isSummary: Boolean) {
            if (isSummary) {
                toggleSummary.setBackgroundResource(R.drawable.toggle_active_bg)
                summaryText?.setTextColor(Color.parseColor("#212121"))
                summaryText?.setTypeface(null, android.graphics.Typeface.BOLD)
                summaryIcon?.setColorFilter(
                    Color.parseColor("#212121"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )

                toggleFullDetail.setBackgroundColor(Color.TRANSPARENT)
                detailText?.setTextColor(Color.parseColor("#9E9E9E"))
                detailText?.setTypeface(null, android.graphics.Typeface.NORMAL)
                detailIcon?.setColorFilter(
                    Color.parseColor("#9E9E9E"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )

                interpretationText.visibility = View.VISIBLE
                recommendationContainer.visibility = View.GONE

            } else {
                toggleFullDetail.setBackgroundResource(R.drawable.toggle_active_bg)
                detailText?.setTextColor(Color.parseColor("#212121"))
                detailText?.setTypeface(null, android.graphics.Typeface.BOLD)
                detailIcon?.setColorFilter(
                    Color.parseColor("#212121"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )

                toggleSummary.setBackgroundColor(Color.TRANSPARENT)
                summaryText?.setTextColor(Color.parseColor("#9E9E9E"))
                summaryText?.setTypeface(null, android.graphics.Typeface.NORMAL)
                summaryIcon?.setColorFilter(
                    Color.parseColor("#9E9E9E"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )

                interpretationText.visibility = View.GONE
                recommendationContainer.visibility = View.VISIBLE
            }
        }

        applyViewMode(isSummary = addCardBannerFlags.isEmpty())

        toggleSummary.setOnClickListener { applyViewMode(isSummary = true) }
        toggleFullDetail.setOnClickListener { applyViewMode(isSummary = false) }

        if (weather != null && weather.temperatureCelsius > -900f) {
            weatherTemp.text = "🌡️ ${"%.1f".format(weather.temperatureCelsius)}°C"
            weatherHumidity.text = "💧 ${weather.humidity}%"
            weatherPrecip.text = "🌧️ ${weather.precipitationMm}mm"
            weatherStrip.visibility = View.VISIBLE
        } else if (!summaryInterpretation.weatherSummary.isNullOrBlank()) {
            try {
                val afterPipe = summaryInterpretation.weatherSummary.substringAfter("|").trim()
                val tempPart = afterPipe.substringBefore(",").trim()
                val humidPart = afterPipe.substringAfter(",").trim().replace("humidity", "").trim()
                weatherTemp.text = "🌡️ $tempPart"
                weatherHumidity.text = "💧 $humidPart"
                weatherPrecip.visibility = View.GONE
                weatherStrip.visibility = View.VISIBLE
            } catch (e: Exception) {
                weatherStrip.visibility = View.GONE
            }
        } else {
            weatherStrip.visibility = View.GONE
        }

        val placeholder = uploadedImagesContainer.findViewWithTag<View>("shimmer_placeholder")
        if (placeholder != null) uploadedImagesContainer.removeView(placeholder)
        uploadedImagesContainer.addView(card)
        if (uploadedImagesContainer.childCount > 0) hideInstruction()
        scrollToBottom()
    }

    private fun addNoDetectionMiniCard(undetectedUris: List<Uri>) {
        if (undetectedUris.isEmpty()) return
        val card = layoutInflater.inflate(R.layout.item_no_detection_mini_card, uploadedImagesContainer, false)
        val imageRow = card.findViewById<LinearLayout>(R.id.miniCardImageRow)
        val titleView = card.findViewById<TextView>(R.id.miniCardTitle)
        titleView.text = "❌ ${undetectedUris.size} Undetected Image${if (undetectedUris.size > 1) "s" else ""}"

        undetectedUris.forEach { uri ->
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(200, 200).apply { setMargins(4, 4, 4, 4) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.65f
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

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

        val panelDate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
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
                cornerRadius = 6f * resources.displayMetrics.density
                setColor(android.graphics.Color.parseColor("#F9F9F9"))
            }
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

        val panelEstimate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        data class WeekGroup(val emoji: String, val label: String, val sublabel: String, val range: IntRange)
        val groups = listOf(
            WeekGroup("🌱", "Just started", "1 – 3 weeks", 1..3),
            WeekGroup("🌿", "Growing", "4 – 8 weeks", 4..8),
            WeekGroup("🍃", "Well established", "9 – 13 weeks", 9..13),
            WeekGroup("🌾", "Almost ready", "14 – 22 weeks", 14..22)
        )

        var selectedWeek = 1
        var expandedGroup: WeekGroup? = null
        val weekButtonRows = mutableMapOf<WeekGroup, LinearLayout>()
        val weekButtons = mutableListOf<TextView>()
        val groupCards = mutableListOf<LinearLayout>()

        val activeGreen = android.graphics.Color.parseColor("#4CAF50")
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

            groupCard.setOnClickListener {
                val isExpanding = expandedGroup != group
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

        var isDateMode = false

        fun selectCard(dateMode: Boolean) {
            isDateMode = dateMode
            val activeStroke = android.graphics.Color.parseColor("#4CAF50")
            val activeFill = android.graphics.Color.parseColor("#F1F8E9")
            val inactiveFill = android.graphics.Color.parseColor("#F5F5F5")
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
            panelDate.visibility = if (dateMode) View.VISIBLE else View.GONE
            scrollEstimate.visibility = if (!dateMode) View.VISIBLE else View.GONE
            panelEstimate.visibility = View.VISIBLE
        }

        cardDatePicker.setOnClickListener { selectCard(true) }
        cardEstimate.setOnClickListener { selectCard(false) }

        selectCard(false)

        val dialog = MaterialAlertDialogBuilder(this, R.style.MyCustomDialogLayout)
            .setTitle("🌱 When did you plant this?")
            .setView(root)
            .setPositiveButton("Confirm", null)
            .setCancelable(false)
            .show()

        val confirmButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)

        confirmButton.isEnabled = false

        fun refreshConfirmButton() {
            confirmButton.isEnabled = when {
                isDateMode -> true
                else -> expandedGroup != null
            }
        }

        cardDatePicker.setOnClickListener {
            selectCard(true)
            refreshConfirmButton()
        }
        cardEstimate.setOnClickListener {
            selectCard(false)
            refreshConfirmButton()
        }

        groups.forEachIndexed { index, group ->
            val groupCard = groupCards[index]
            val weekRow = weekButtonRows[group]!!
            val chevron = groupCard.getChildAt(groupCard.childCount - 1) as? TextView

            groupCard.setOnClickListener {
                val isExpanding = expandedGroup != group

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

                refreshConfirmButton()
            }
        }

        confirmButton.setOnClickListener {
            if (isDateMode) {
                val picked = Calendar.getInstance().apply {
                    set(datePicker.year, datePicker.month, datePicker.dayOfMonth, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val diffMs = Calendar.getInstance().timeInMillis - picked.timeInMillis
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
        val plantingDate = db.conversationDao().getPlantingDate(conversationId)
        if (plantingDate != null && plantingDate > 0) {
            val diffMs = System.currentTimeMillis() - plantingDate
            return (diffMs / (1000L * 60 * 60 * 24 * 7)).toInt().coerceIn(1, 22)
        }

        val baseAge = db.conversationDao().getCropAge(conversationId) ?: return null
        val history = db.promptDao().getPromptsForConversation(conversationId)
        if (history.isEmpty()) return baseAge
        return try {
            val sdf = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())
            val firstDate = sdf.parse(history.first().timestamp) ?: return baseAge
            val weeksElapsed = ((Date().time - firstDate.time) / (1000L * 60 * 60 * 24 * 7)).toInt()
            (baseAge + weeksElapsed).coerceAtMost(22)
        } catch (e: Exception) {
            baseAge
        }
    }

    private fun extractStageFromDiagnostic(diagnostic: String): String? {
        if (diagnostic == "no_detection") return "no_detection"
        val parts = diagnostic.split("|")
        return if (parts.isNotEmpty()) parts[0] else null
    }

    private fun buildProgressionText(prevStage: String, currStage: String): String {
        val stageOrder = mapOf("Not Ready" to 0, "Near Harvest" to 1, "Harvest Ready" to 2)
        val prevOrder = stageOrder[prevStage] ?: return ""
        val currOrder = stageOrder[currStage] ?: return ""
        return when {
            currOrder > prevOrder -> "📈 Progression: $prevStage → $currStage\n✅ Crop is progressing normally"
            currOrder == prevOrder -> "📊 Progression: $prevStage → $currStage\n⏳ Crop remains at the same stage"
            else -> "📉 Progression: $prevStage → $currStage\n⚠️ Stage regression detected — check crop health"
        }
    }

    // ─── Overload used when loading history from DB ───────────────────────────

    private fun addConversationCard(
        images: List<Uri>,
        interpretation: CropInterpretation?,
        timestamp: String,
        isNoDetection: Boolean = false,
        cropAgeWeeks: Int? = null,
        isFirstScan: Boolean = false,
        perImageStatuses: List<String> = emptyList()   // ← NEW
    ) {
        // Build per-image results: nullify interpretation for images flagged as bad
        val imageResults = images.mapIndexed { i, uri ->
            val st = perImageStatuses.getOrNull(i)
            Triple(
                uri,
                if (st == "no_detect" || st == "low_conf" || st == "invalid") null
                else interpretation,
                st == "no_detect" || isNoDetection
            )
        }
        addConversationCard(
            images = images,
            imageResults = imageResults,
            summaryInterpretation = if (isNoDetection) null else interpretation,
            notSweetPotatoCount = if (isNoDetection) images.size else 0,
            lowConfidenceCount = perImageStatuses.count { it == "low_conf" },
            timestamp = timestamp,
            weather = if (interpretation?.weatherSummary != null) WeatherData(-999f, 0, 0f, 0, "") else null,
            progressionInsight = null,
            cropAgeWeeks = cropAgeWeeks,
            hasConflict = false,
            perImageStages = emptyList(),
            perImageStatuses = perImageStatuses,
            isFirstScan = isFirstScan
        )
    }

    private suspend fun checkSameCrop(): Boolean {
        if (conversationId == -1L) return false
        val history = db.promptDao().getPromptsForConversation(conversationId)
        if (history.isEmpty()) return false
        val lastScan = history.last()
        val lastStage = extractStageFromDiagnostic(lastScan.diagnostic)
        val cropAge = db.conversationDao().getCropAge(conversationId)
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
                    .setPositiveButton("✅ Yes — Same Plant") { _, _ -> cont.resume(true) }
                    .setNegativeButton("🔄 No — Different Plant") { _, _ -> cont.resume(false) }
                    .setCancelable(false).show()
            }
        }
    }

    // ─── Text helpers — for recommendation container ──────────────────────────

    private fun buildWhatItMeansLine(
        scenarioLabel: String,
        perImageStatuses: List<String>,
        imageCount: Int
    ): String {
        val total     = perImageStatuses.size.takeIf { it > 0 } ?: imageCount
        val nNoDetect = perImageStatuses.count { it == "no_detect" }
        val nLowConf  = perImageStatuses.count { it == "low_conf" }
        val nOutlier  = perImageStatuses.count { it == "outlier" }
        val nValid    = perImageStatuses.count { it == "valid" }
        fun img(n: Int) = if (n == 1) "image" else "images"

        return when (scenarioLabel) {
            "Insufficient Batch" -> when {
                nLowConf > 0 && nNoDetect == 0 ->
                    "🌫️ YOLO detected sweet potato in all $total ${img(total)}, but CNN maturity confidence was below the threshold in $nLowConf ${img(nLowConf)} — the system needs clearer images to classify the stage reliably."
                nNoDetect > 0 && nLowConf == 0 ->
                    "🔍 YOLO found no sweet potato plant in $nNoDetect of $total ${img(total)} — only $nValid ${img(nValid)} passed object detection."
                nNoDetect > 0 && nLowConf > 0 ->
                    "📊 $nNoDetect ${img(nNoDetect)} had no plant detected by YOLO, and $nLowConf ${img(nLowConf)} had detection but CNN confidence was too low — only $nValid of $total were usable."
                else ->
                    if (nValid < total)
                        "📊 Only $nValid of $total ${img(total)} passed both plant detection and maturity classification."
                    else
                        "📊 All $total ${img(total)} passed detection — but the batch was still too small for a confident result."
            }
            "Stage Conflict" -> when {
                nOutlier > 0 ->
                    "⚠️ $nOutlier of $total ${img(total)} showed a different maturity stage from the majority — conflicting signals from the same batch prevent a reliable conclusion."
                else ->
                    "⚠️ The AI detected a maturity stage inconsistent with your crop's recorded age — the result has been flagged as inconclusive."
            }
            else -> "📊 The AI could not produce a reliable result from this batch."
        }
    }

    private fun addSectionHeader(container: LinearLayout, text: String) {
        container.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 24, 0, 6) }
            this.text = text
            textSize = 15f
            typeface = nunitoBold
            setTextColor(ContextCompat.getColor(this@ConversationsActivity, R.color.black))
        })
    }

    private fun addRecommendationBullet(container: LinearLayout, text: String) {
        container.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(16, 6, 0, 6) }
            this.text = "• $text"
            textSize = 15f
            typeface = nunitoRegular
            setTextColor(ContextCompat.getColor(this@ConversationsActivity, R.color.black))
            setLineSpacing(0f, 1.3f)
        })
    }

    private fun addSectionHeaderTo(container: LinearLayout, text: String) = addSectionHeader(container, text)
    private fun addBulletTo(container: LinearLayout, text: String) = addRecommendationBullet(container, text)

    /**
     * Renders a single AnomalyFlag as a polished card:
     *
     *  ┌────────────────────────────────────────────────────┐
     *  │▌ 🔴  Stage Regression                [CRITICAL]   │
     *  │   • Stage dropped 2 steps backward                 │
     *  │   💡 Inspect for root rot or waterlogging          │
     *  └────────────────────────────────────────────────────┘
     */
    private fun addAnomalyFlagCard(container: LinearLayout, flag: com.ai.growsight.ai.AnomalyFlag) {
        val density = resources.displayMetrics.density

        // Severity palette
        val (borderColor, chipBg, chipText, severityLabel, severityIcon) = when (flag.severity) {
            "critical" -> arrayOf("#B71C1C", "#FFEBEE", "#B71C1C", "CRITICAL", "🔴")
            "high"     -> arrayOf("#BF360C", "#FBE9E7", "#BF360C", "HIGH",     "🟠")
            "medium"   -> arrayOf("#E65100", "#FFF8E1", "#E65100", "MEDIUM",   "🟡")
            else       -> arrayOf("#1565C0", "#E3F2FD", "#1565C0", "LOW",      "🔵")
        }

        // Outer card wrapper: white background + rounded corners + shadow
        val card = androidx.cardview.widget.CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, (8 * density).toInt(), 0, (4 * density).toInt()) }
            radius = (10 * density)
            cardElevation = (1.5f * density)
            setCardBackgroundColor(android.graphics.Color.parseColor(chipBg as String))
        }

        // Inner layout with left colored accent bar
        val innerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Left accent bar (4dp wide, full height)
        val accentBar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (4 * density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor(borderColor as String))
        }

        // Content column
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(
                (12 * density).toInt(),
                (10 * density).toInt(),
                (12 * density).toInt(),
                (10 * density).toInt()
            )
        }

        // Header row: icon + badge label + severity chip
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleText = TextView(this).apply {
            text = "$severityIcon  ${flag.badgeLabel}"
            textSize = 13f
            typeface = nunitoBold
            setTextColor(android.graphics.Color.parseColor(borderColor as String))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val severityChip = TextView(this).apply {
            text = severityLabel as String
            textSize = 10f
            typeface = nunitoBold
            setTextColor(android.graphics.Color.parseColor(chipText as String))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = (6 * density)
                setStroke((1 * density).toInt(), android.graphics.Color.parseColor(chipText as String))
                setColor(android.graphics.Color.TRANSPARENT)
            }
            setPadding(
                (7 * density).toInt(), (2 * density).toInt(),
                (7 * density).toInt(), (2 * density).toInt()
            )
        }

        headerRow.addView(titleText)
        headerRow.addView(severityChip)
        content.addView(headerRow)

        // Thin divider
        content.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply { setMargins(0, (6 * density).toInt(), 0, (6 * density).toInt()) }
            setBackgroundColor(android.graphics.Color.parseColor("#22000000"))
        })

        // Detail line (bullet style)
        content.addView(TextView(this).apply {
            text = "▸  ${flag.detail}"
            textSize = 12.5f
            typeface = nunitoRegular
            setTextColor(android.graphics.Color.parseColor("#212121"))
            setLineSpacing(0f, 1.35f)
        })

        // Suggestion line (action prompt)
        if (flag.suggestion.isNotBlank()) {
            content.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
                ).apply { setMargins(0, (6 * density).toInt(), 0, (4 * density).toInt()) }
                setBackgroundColor(android.graphics.Color.parseColor("#15000000"))
            })
            content.addView(TextView(this).apply {
                text = "💡 ${flag.suggestion}"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#555555"))
                setTypeface(null, android.graphics.Typeface.ITALIC)
                setLineSpacing(0f, 1.3f)
            })
        }

        innerRow.addView(accentBar)
        innerRow.addView(content)
        card.addView(innerRow)
        container.addView(card)
    }

    /** Renders all anomaly flags from [flags] into [container] under a section header. */
    private fun addAnomalyFlagsSection(
        container: LinearLayout,
        flags: List<com.ai.growsight.ai.AnomalyFlag>
    ) {
        if (flags.isEmpty()) return
        addSectionHeader(container, "⚠️ Issues Detected")
        flags.sortedBy {
            when (it.severity) { "critical" -> 0; "high" -> 1; "medium" -> 2; else -> 3 }
        }.forEach { addAnomalyFlagCard(container, it) }
    }


    private val nunitoRegular by lazy { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.nunito_regular) }
    private val nunitoBold by lazy { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.nunito_bold) }

    // ─── Image helpers ────────────────────────────────────────────────────────

    private suspend fun getConversationWeather(): WeatherData? {
        if (conversationId == -1L) return null
        val conv = db.conversationDao().getConversationById(conversationId) ?: return null
        val lat = conv.latitude ?: return null
        val lon = conv.longitude ?: return null
        return try {
            withContext(Dispatchers.IO) {
                com.ai.growsight.ai.WeatherService.fetchWeather(lat, lon)
            }
        } catch (e: Exception) {
            null
        }
    }


    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
            } else {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
            bitmap?.let {
                if (it.config != Bitmap.Config.ARGB_8888) {
                    val copy = it.copy(Bitmap.Config.ARGB_8888, false)
                    it.recycle()
                    copy
                } else it
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun ensureLocalCopy(uri: Uri): Uri? {
        if (uri.scheme == "file") return uri
        return try {
            val imagesDir = File(filesDir, "images").apply { if (!exists()) mkdirs() }
            val outFile = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
            val inputStream = contentResolver.openInputStream(uri) ?: run {
                Log.e("ensureLocalCopy", "Could not open input stream for: $uri")
                return null
            }
            inputStream.use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output) } }
            if (outFile.length() == 0L) {
                Log.e("ensureLocalCopy", "File was written but is empty: $uri")
                return null
            }
            Uri.fromFile(outFile)
        } catch (e: Exception) {
            Log.e("ensureLocalCopy", "Failed to copy: ${e.message}", e)
            null
        }
    }

    private fun showImageModal(uri: Uri) {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val contentView = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            isClickable = true
            isFocusable = true
        }

        val imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            ).apply {
                val margin = (24 * density).toInt()
                setMargins(margin, margin, margin, margin)
            }
            setImageURI(uri)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            scaleX = 0.88f
            scaleY = 0.88f
            alpha = 0f

            val cornerRadius = 20 * density
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
            clipToOutline = true
        }

        contentView.addView(imageView)
        dialog.setContentView(contentView)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            // Let the dialog own the status bar area and paint it transparently
            // so no activity color bleeds through
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                statusBarColor = Color.TRANSPARENT
                navigationBarColor = Color.TRANSPARENT
            }
        }

        fun dismiss() {
            imageView.animate()
                .scaleX(0.88f).scaleY(0.88f).alpha(0f)
                .setDuration(180)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            contentView.animate()
                .alpha(0f)
                .setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { dialog.dismiss() }
                .start()
        }

        contentView.setOnClickListener { dismiss() }
        imageView.setOnClickListener { dismiss() }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else false
        }

        dialog.show()

        imageView.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun addPreviewImage(uri: Uri) {
        if (uploadedUris.size >= 10) {
            Toast.makeText(this, "Maximum 10 images allowed at once", Toast.LENGTH_SHORT).show()
            return
        }
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

        // ── Duplicate scan image guard ────────────────────────────────────────────
        // Checks the newly added image against every image already stored in this
        // plantation's scan history. Fires an alert if a match is found.
        if (conversationId != -1L) {
            lifecycleScope.launch {
                val newHash = computeImageFingerprint(uri) ?: return@launch
                val matchTimestamp = withContext(Dispatchers.IO) {
                    db.promptDao().getPromptsForConversation(conversationId)
                        .firstOrNull { prompt ->
                            prompt.imageUris.any { storedStr ->
                                computeImageFingerprint(Uri.parse(storedStr)) == newHash
                            }
                        }?.timestamp
                }
                if (matchTimestamp != null) {
                    withContext(Dispatchers.Main) {
                        for (i in 0 until previewContainer.childCount) {
                            val child = previewContainer.getChildAt(i)
                            if (child?.tag == uri) { removePreviewImage(child, uri); break }
                        }
                        Toast.makeText(
                            this@ConversationsActivity,
                            "⚠️ Duplicate removed — already submitted on $matchTimestamp",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
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
            val allConvs = db.conversationDao().getAllConversations()

            // ← Use QuickScanManager as source of truth, same as PlantationListActivity
            val quickScanIds = QuickScanManager.load(this@ConversationsActivity)
                .map { it.conversationId }
                .toSet()

            val plantations = allConvs.filter { it.id !in quickScanIds }
            val quickScans  = allConvs.filter { it.id in quickScanIds }

            val isPlantationTab = drawerTabLayout.selectedTabPosition == 0

            suspend fun lastPrompt(convId: Long): PromptEntity? =
                db.promptDao().getPromptsForConversation(convId).lastOrNull()

            if (isPlantationTab) {
                // Build plantation rows
                val rows = mutableListOf<DrawerRow>()
                plantations.forEach { conv ->
                    rows += DrawerRow.ConvItem(conv, lastPrompt(conv.id))
                }

                runOnUiThread {
                    drawerQuickScanListView.visibility = View.GONE

                    if (plantations.isEmpty()) {
                        conversationListView.visibility = View.GONE
                        drawerEmptyState.visibility     = View.VISIBLE
                        drawerEmptyIcon.text            = "🌿"
                        drawerEmptyText.text            = "No plantations yet"
                    } else {
                        drawerEmptyState.visibility     = View.GONE
                        conversationListView.visibility = View.VISIBLE
                        conversationListView.adapter    = DrawerAdapter(rows)

                        conversationListView.setOnItemClickListener { _, _, position, _ ->
                            val row = rows.getOrNull(position) as? DrawerRow.ConvItem
                                ?: return@setOnItemClickListener
                            if (row.conv.isVoided) {
                                MaterialAlertDialogBuilder(this@ConversationsActivity, R.style.MyCustomDialogLayout)
                                    .setTitle("🚫 Plantation Archived")
                                    .setMessage(
                                        "\"${row.conv.name}\" has been archived due to accuracy deficiency.\n\n" +
                                                "You can view its scan history but cannot submit new scans. " +
                                                "Please create a new plantation to continue monitoring."
                                    )
                                    .setPositiveButton("View History") { _, _ -> openDrawerConversation(row.conv) }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            } else {
                                openDrawerConversation(row.conv)
                            }
                        }

                        conversationListView.setOnItemLongClickListener { _, _, position, _ ->
                            val row = rows.getOrNull(position) as? DrawerRow.ConvItem
                                ?: return@setOnItemLongClickListener false
                            showManageDialog(row.conv)
                            true
                        }
                    }
                }

            } else {
                // Build quick scan rows
                val rows = mutableListOf<DrawerRow>()
                quickScans.forEach { conv ->
                    rows += DrawerRow.ConvItem(conv, lastPrompt(conv.id))
                }

                runOnUiThread {
                    conversationListView.visibility = View.GONE

                    if (quickScans.isEmpty()) {
                        drawerQuickScanListView.visibility = View.GONE
                        drawerEmptyState.visibility        = View.VISIBLE
                        drawerEmptyIcon.text               = "📷"
                        drawerEmptyText.text               = "No quick scans yet"
                    } else {
                        drawerEmptyState.visibility        = View.GONE
                        drawerQuickScanListView.visibility = View.VISIBLE
                        drawerQuickScanListView.adapter    = DrawerAdapter(rows)

                        drawerQuickScanListView.setOnItemClickListener { _, _, position, _ ->
                            val row = rows.getOrNull(position) as? DrawerRow.ConvItem
                                ?: return@setOnItemClickListener
                            openDrawerConversation(row.conv)
                        }

                        drawerQuickScanListView.setOnItemLongClickListener { _, _, position, _ ->
                            val row = rows.getOrNull(position) as? DrawerRow.ConvItem
                                ?: return@setOnItemLongClickListener false
                            showManageDialog(row.conv)
                            true
                        }
                    }
                }
            }
        }
    }

    private fun openDrawerConversation(conv: ConversationEntity) {
        isSwitchingConversation = true
        isProcessingImages      = false
        isHistoryLoaded         = false
        checkAndApplySendLock()
        loader.visibility = View.GONE
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
            conversationId = conv.id
            withContext(Dispatchers.Main) {
                conversationTitle.text = conv.name
                drawerLayout.closeDrawer(GravityCompat.END)
                loader.visibility = View.GONE
                shimmerLayout.stopShimmer()
                shimmerLayout.visibility = View.GONE
                isProcessingImages      = false
                isSwitchingConversation = false
            }
            reloadConversationHistory()
            withContext(Dispatchers.Main) { stopPulseInputRow() }
        }
    }

    private fun showManageDialog(conv: ConversationEntity) {
        AlertDialog.Builder(this@ConversationsActivity)
            .setTitle("Manage Conversation")
            .setItems(arrayOf("Edit Name", "Delete Conversation")) { _, which ->
                when (which) {
                    0 -> showEditDialog(conv)
                    1 -> deleteConversation(conv)
                }
            }.show()
    }

    private inner class DrawerAdapter(
        private val rows: List<DrawerRow>
    ) : BaseAdapter() {

        private val VIEW_TYPE_HEADER = 0
        private val VIEW_TYPE_ITEM   = 1

        override fun getCount()                     = rows.size
        override fun getItem(pos: Int)              = rows[pos]
        override fun getItemId(pos: Int)            = pos.toLong()
        override fun isEnabled(pos: Int)            = rows[pos] is DrawerRow.ConvItem
        override fun getViewTypeCount()             = 2
        override fun getItemViewType(pos: Int)      =
            if (rows[pos] is DrawerRow.SectionHeader) VIEW_TYPE_HEADER else VIEW_TYPE_ITEM

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = rows[position]) {

                // ── Section header ────────────────────────────────────────────
                is DrawerRow.SectionHeader -> {
                    val view = convertView
                        ?: layoutInflater.inflate(R.layout.item_drawer_section_header, parent, false)
                    view.findViewById<TextView>(R.id.drawerSectionLabel).text = row.label
                    view.findViewById<TextView>(R.id.drawerSectionCount).text = "${row.count}"
                    view
                }

                // ── Conversation item ─────────────────────────────────────────
                is DrawerRow.ConvItem -> {
                    val view = convertView
                        ?: layoutInflater.inflate(R.layout.item_drawer_conversation, parent, false)

                    val thumbImage   = view.findViewById<ImageView>(R.id.drawerThumbImage)
                    val thumbShimmer = view.findViewById<com.facebook.shimmer.ShimmerFrameLayout>(R.id.drawerThumbShimmer)
                    val stageDot     = view.findViewById<View>(R.id.drawerStageDot)
                    val titleText    = view.findViewById<TextView>(R.id.drawerItemTitle)
                    val tsText       = view.findViewById<TextView>(R.id.drawerItemTimestamp)
                    val badgeText    = view.findViewById<TextView>(R.id.drawerStageBadge)

                    // Reset for recycling
                    thumbImage.visibility   = View.INVISIBLE
                    thumbShimmer.visibility = View.VISIBLE
                    thumbShimmer.startShimmer()
                    thumbImage.setImageBitmap(null)

                    titleText.text = if (row.conv.isVoided) "🚫 ${row.conv.name}" else row.conv.name
                    titleText.setTextColor(
                        if (row.conv.isVoided)
                            android.graphics.Color.parseColor("#9E9E9E")
                        else
                            android.graphics.Color.parseColor("#212121")
                    )

                    val prompt = row.lastPrompt
                    tsText.text = prompt?.timestamp ?: "No scans yet"

                    // Stage badge + dot from latest prompt
                    val stage = prompt?.diagnostic
                        ?.takeIf { it != "no_detection" && it.isNotBlank() }
                        ?.split("|")?.firstOrNull()

                    if (stage != null) {
                        badgeText.text = stage
                        badgeText.visibility = View.VISIBLE
                        val (badgeBg, dotBg) = when (stage) {
                            "Harvest Ready" -> R.drawable.rounded_corner_green to R.drawable.circle_green_2
                            "Near Harvest"  -> R.drawable.rounded_corner_orange to R.drawable.circle_yellow_2
                            "Not Ready"     -> R.drawable.rounded_corner_red    to R.drawable.circle_red_2
                            else            -> R.drawable.rounded_corner_gray   to R.drawable.circle_gray
                        }
                        badgeText.setBackgroundResource(badgeBg)
                        stageDot.setBackgroundResource(dotBg)
                    } else {
                        badgeText.visibility = View.GONE
                        stageDot.setBackgroundResource(R.drawable.circle_gray)
                    }

                    // Load thumbnail async
                    val firstUriStr = prompt?.imageUris?.firstOrNull()
                    if (firstUriStr != null) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val bmp = loadThumbnail(Uri.parse(firstUriStr), 120)
                            withContext(Dispatchers.Main) {
                                // Guard against recycled view showing wrong image
                                if (titleText.text == row.conv.name) {
                                    thumbShimmer.stopShimmer()
                                    thumbShimmer.visibility = View.GONE
                                    if (bmp != null) thumbImage.setImageBitmap(bmp)
                                    else thumbImage.setImageURI(Uri.parse(firstUriStr))
                                    thumbImage.visibility = View.VISIBLE

                                    // Apply rounded corners programmatically
                                    thumbImage.outlineProvider = object : ViewOutlineProvider() {
                                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                                            val r = (8 * resources.displayMetrics.density)
                                            outline.setRoundRect(0, 0, view.width, view.height, r)
                                        }
                                    }
                                    thumbImage.clipToOutline = true
                                }
                            }
                        }
                    } else {
                        // No image — show placeholder leaf
                        thumbShimmer.stopShimmer()
                        thumbShimmer.visibility = View.GONE
                        thumbImage.setImageResource(R.drawable.frame)
                        thumbImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        thumbImage.visibility = View.VISIBLE
                    }

                    view
                }
            }
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
        isProcessingImages = false
        isSwitchingConversation = false
        isHistoryLoaded = true
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
        if (conversationId == -1L) {
            Toast.makeText(this, "Please select or create a plantation first", Toast.LENGTH_SHORT).show()
            return
        }
        val photoFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "photo_${System.currentTimeMillis()}.jpg")
        cameraImageUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", photoFile)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
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
                val name = "Plantation#${count + 1}"
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
                            finish()
                        }
                        conversationId = -1L
                    }
                    refreshConversationList()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // AFTER — only clear isRetaking after we know result is OK
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) {
            // If we were retaking and the user backed out, reset the flag
            if (requestCode == PICK_IMAGES_REQUEST || requestCode == CAMERA_REQUEST_CODE) {
                isRetaking = false
            }
            isHistoryLoaded = true
            return
        }

        isHistoryLoaded = true
        when (requestCode) {
            PICK_IMAGES_REQUEST -> {
                val clipData = data?.clipData
                if (clipData != null) {
                    val remaining = 10 - uploadedUris.size
                    var added = 0
                    for (i in 0 until clipData.itemCount) {
                        if (added >= remaining) {
                            Toast.makeText(this, "Maximum 10 images allowed — ${clipData.itemCount - i} skipped", Toast.LENGTH_SHORT).show()
                            break
                        }
                        val uri = clipData.getItemAt(i).uri
                        if (!uploadedUris.contains(uri)) {
                            addPreviewImage(uri)
                            added++
                        }
                    }
                } else {
                    data?.data?.let { if (!uploadedUris.contains(it)) addPreviewImage(it) }
                }
            }
            CAMERA_REQUEST_CODE -> {
                cameraImageUri?.let { if (!uploadedUris.contains(it)) addPreviewImage(it) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cooldownTimer?.cancel()
        cachedWeather = null
        weatherFetchState = WeatherFetchState.IDLE
        weatherFetchedAt = 0L
        stopPulseInputRow()
        pulseAnimators.values.forEach { it.cancel() }
        pulseAnimators.clear()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (dismissImageOverlay != null) {
            dismissImageOverlay?.invoke()
        } else {
            super.onBackPressed()
        }
    }

    // ─── Testing ──────────────────────────────────────────────────────────────

    private fun setupTesting() {
        conversationTitle.setOnClickListener {
            testClickCount++
            if (testClickCount >= 3) {
                testClickCount = 0
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

    private fun checkModelStatus() {
        showTestResult("🔍 MODEL STATUS\n\nCNN: ${if (cnn != null) "✅ LOADED" else "❌ NOT LOADED"}\nYOLO: ${if (yolo != null) "✅ LOADED" else "❌ NOT LOADED"}\nAll Ready: ${ModelManager.areModelsAvailable()}")
    }

    private fun runComprehensiveTest() {
        lifecycleScope.launch {
            showTestProgress("Running Comprehensive Test...")
            val sb = StringBuilder("🧪 COMPREHENSIVE MODEL TEST\n\n")
            try {
                sb.append("1. MODEL LOADING:\n")
                if (!waitForModels()) {
                    sb.append("   ❌ Models failed to load\n")
                    showTestResult(sb.toString())
                    return@launch
                }
                sb.append("   ✅ Models loaded\n   - YOLO: ${yolo != null}\n   - CNN: ${cnn != null}\n\n")

                sb.append("2. YOLO DETECTION TEST:\n")
                if (yolo != null) {
                    val testBitmap = createTestBitmap()
                    val detections = withContext(Dispatchers.Default) { yolo?.detect(testBitmap) ?: emptyList() }
                    if (detections.isNotEmpty()) {
                        val best = detections.maxByOrNull { it.score }!!
                        sb.append("   ✅ YOLO working — ${best.label} (${"%.1f".format(best.score * 100)}%)\n")
                    } else sb.append("   ⚠️ YOLO working but no detection\n")
                    testBitmap.recycle()
                } else sb.append("   ❌ YOLO not available\n")

                sb.append("\n3. CNN CLASSIFICATION TEST:\n")
                if (cnn != null) {
                    val testBitmap = createTestBitmap()
                    val result = withContext(Dispatchers.Default) { cnn?.classify(testBitmap) }
                    if (result != null) {
                        val interpretation = InterpretationEngine.interpret(result)
                        sb.append("   ✅ CNN working\n   - Label: ${result.label}\n   - Confidence: ${"%.1f".format(result.confidence * 100)}%\n   - Stage: ${interpretation.stage}\n   - Harvest Time: ${interpretation.harvestTime}\n")
                    } else sb.append("   ❌ CNN returned null\n")
                    testBitmap.recycle()
                } else sb.append("   ❌ CNN not available\n")

                sb.append("\n4. ASSET FILES CHECK:\n")
                listOf("ml/yolov11_3_5.tflite", "ml/sweetpotato_v3_5.tflite", "ml/yolo_labels.json", "ml/labels.txt").forEach { asset ->
                    try {
                        assets.open(asset).close()
                        sb.append("   ✅ $asset\n")
                    } catch (e: Exception) {
                        sb.append("   ❌ $asset — ${e.message}\n")
                    }
                }
            } catch (e: Exception) {
                sb.append("\n❌ Comprehensive test failed: ${e.message}")
            }
            showTestResult(sb.toString())
        }
    }

    private fun createTestBitmap() = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until width) for (y in 0 until height) setPixel(x, y, Color.argb(255, 50, (150 + (x * 100 / width)).coerceIn(0, 255), 50))
    }

    private fun showTestProgress(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    private fun showTestResult(msg: String) = runOnUiThread { AlertDialog.Builder(this).setTitle("Test Results").setMessage(msg).setPositiveButton("OK", null).show() }

    // ─── Scroll ───────────────────────────────────────────────────────────────

    private fun scrollToBottom() {
        val adapter = scanGrid.adapter
        if (adapter != null && adapter.count > 0) {
            scanGrid.postDelayed({
                scanGrid.smoothScrollToPosition(adapter.count - 1)
            }, 1000L)
        }
        refreshGrid()
    }
}