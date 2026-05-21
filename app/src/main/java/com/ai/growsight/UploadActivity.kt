package com.ai.growsight

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.GridView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.ai.growsight.ConversationsActivity.Companion.EXTRA_CONVERSATION_ID
import com.ai.growsight.ConversationsActivity.Companion.EXTRA_IMAGE_URIS
import com.ai.growsight.ai.ModelManager
import com.ai.growsight.ai.ModelUpdateManager
import com.ai.growsight.ai.MaturityClassifier
import com.ai.growsight.ai.YoloDetector
import com.ai.growsight.data.AppDatabase
import com.ai.growsight.data.ConversationEntity
import com.ai.growsight.data.PromptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen


class UploadActivity : AppCompatActivity(), ModelUpdateManager.UpdateListener {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var modelStatusIndicator: android.widget.ImageView
    private lateinit var modelStatusText: TextView
    private lateinit var plantationGrid: GridView
    private lateinit var plantationsEmptyText: TextView   // ← FIXED: was missing binding
    private lateinit var activityContainer: LinearLayout
    private lateinit var activityEmptyText: TextView

    // ── Bottom nav ────────────────────────────────────────────────────────────
    private lateinit var navHome: LinearLayout
    private lateinit var navPlantations: LinearLayout
    private lateinit var navCamera: LinearLayout

    // ── DB / Models ───────────────────────────────────────────────────────────
    private lateinit var db: AppDatabase
    var yoloDetector: YoloDetector? = null
    var maturityClassifier: MaturityClassifier? = null
    private var modelsLoaded = false
    private var modelLoadingInProgress = false
    private var modelErrorDialogShown = false
    private var updateInProgress = false

    // ── Quick scan launchers ──────────────────────────────────────────────────
    private lateinit var quickScanGalleryLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var quickScanCameraLauncher: ActivityResultLauncher<Uri>
    private var quickScanCameraUri: Uri? = null
    private val quickScanCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openQuickScanCamera()
        else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
    }
    private lateinit var quickScansContainer: LinearLayout
    private lateinit var quickScansEmptyText: TextView

    // ── Camera launcher ───────────────────────────────────────────────────────
    private val detectionCameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uriString   = result.data?.getStringExtra(CameraDetectionActivity.RESULT_IMAGE_URI)
                val confidence  = result.data?.getFloatExtra("detection_confidence", 0f) ?: 0f
                val wasDetected = result.data?.getBooleanExtra("was_detected", false) ?: false

                if (uriString != null) {
                    val uri = Uri.parse(uriString)
                    val intent = Intent(this, ConversationsActivity::class.java).apply {
                        putParcelableArrayListExtra(EXTRA_IMAGE_URIS, arrayListOf(uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra("yolo_available", yoloDetector != null)
                        putExtra("cnn_available", maturityClassifier != null)
                        putExtra("camera_detection_confidence", confidence)
                        putExtra("camera_was_detected", wasDetected)
                        putExtra(ConversationsActivity.EXTRA_IS_QUICK_SCAN, true)
                    }
                    startActivity(intent)
                }
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CRASH", "App crashed in thread: ${thread.name}", throwable)
        }

        // ── DB ────────────────────────────────────────────────────────────────
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "prompts-db")
            .fallbackToDestructiveMigration().build()

        // ── Bind views ────────────────────────────────────────────────────────
        modelStatusIndicator  = findViewById(R.id.modelStatusIndicator)
        modelStatusText       = findViewById(R.id.modelStatusText)
        plantationGrid        = findViewById(R.id.plantationGrid)
        plantationsEmptyText  = findViewById(R.id.plantationsEmptyText)  // ← bound here
        activityContainer     = findViewById(R.id.activityContainer)
        activityEmptyText     = findViewById(R.id.activityEmptyText)

        navHome        = findViewById(R.id.navHome)
        navPlantations = findViewById(R.id.navPlantations)
        navCamera      = findViewById(R.id.navCamera)

        val newPlantationButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.newPlantationButton)
        newPlantationButton.background = ContextCompat.getDrawable(this, R.drawable.gradient_background)
        newPlantationButton.stateListAnimator = null
        newPlantationButton.backgroundTintList = null
        val logoButton = findViewById<ImageButton>(R.id.logoButton)

        // ── AI status chip — loading state ────────────────────────────────────
        modelStatusIndicator.setImageResource(R.drawable.ic_model_loading)
        modelStatusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.blue))
        modelStatusText.text = "Loading AI..."
        modelStatusText.setTextColor(ContextCompat.getColor(this, R.color.blue))
        modelStatusIndicator.setOnClickListener { showModelStatusDialog() }

        // ── Kick off model loading ────────────────────────────────────────────
        initializeAppModelsAsync()

        // ── Quick Scan views ──────────────────────────────────────────────────
        quickScansContainer = findViewById(R.id.quickScansContainer)
        quickScansEmptyText = findViewById(R.id.quickScansEmptyText)

        // ── Quick Scan launchers ──────────────────────────────────────────────
        quickScanCameraLauncher = registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            if (success && quickScanCameraUri != null) {
                launchQuickScan(arrayListOf(quickScanCameraUri!!))
            } else {
                Toast.makeText(this, "Camera cancelled", Toast.LENGTH_SHORT).show()
            }
        }

        quickScanGalleryLauncher = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(5)
        ) { uris ->
            if (uris.isNotEmpty()) launchQuickScan(ArrayList(uris))
            else Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
        }

        // ── Buttons ───────────────────────────────────────────────────────────
        newPlantationButton.setOnClickListener {
            startActivity(Intent(this, PlantationProfileActivity::class.java))
        }
        logoButton.setOnClickListener { /* brand logo, intentionally empty */ }

        // ── Bottom nav ────────────────────────────────────────────────────────
        navHome.setOnClickListener {
            findViewById<androidx.core.widget.NestedScrollView>(R.id.homeScrollView)
                ?.smoothScrollTo(0, 0)
        }
        navPlantations.setOnClickListener {
            startActivity(Intent(this, PlantationListActivity::class.java))
        }
        navCamera.setOnClickListener {
            showQuickScanChooser()
        }

        // ── Collapsible sections ──────────────────────────────────────────────
        val sectionBodyPlantations   = findViewById<android.view.View>(R.id.sectionBodyPlantations)
        val chevronPlantations       = findViewById<android.widget.ImageView>(R.id.chevronPlantations)
        val sectionHeaderPlantations = findViewById<android.view.View>(R.id.sectionHeaderPlantations)

        val sectionBodyActivity    = findViewById<android.view.View>(R.id.sectionBodyActivity)
        val chevronActivity        = findViewById<android.widget.ImageView>(R.id.chevronActivity)
        val sectionHeaderActivity  = findViewById<android.view.View>(R.id.sectionHeaderActivity)

        val sectionBodyQuickScans   = findViewById<android.view.View>(R.id.sectionBodyQuickScans)
        val chevronQuickScans       = findViewById<android.widget.ImageView>(R.id.chevronQuickScans)
        val sectionHeaderQuickScans = findViewById<android.view.View>(R.id.sectionHeaderQuickScans)

        val homeScrollView      = findViewById<androidx.core.widget.NestedScrollView>(R.id.homeScrollView)
        val sectionBody         = findViewById<android.view.View>(R.id.sectionBodyPlantations)
        val sectionHeader       = findViewById<android.view.View>(R.id.sectionHeaderPlantations)

// Update on scroll
        homeScrollView.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
                syncButtonVisibility()
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        refreshGridAndActivity()
        refreshQuickScans()
    }



    private fun syncButtonVisibility() {
        val spacer = findViewById<android.view.View>(R.id.plantationButtonSpacer) ?: return
        val button = findViewById<com.google.android.material.button.MaterialButton>(R.id.newPlantationButton) ?: return
        if (spacer.visibility == android.view.View.GONE) {
            button.visibility = android.view.View.GONE
            return
        }
        val navBar = findViewById<LinearLayout>(R.id.bottomNavBar) ?: return

        if (button.height == 0) { button.post { syncButtonVisibility() }; return }

        val spacerLoc = IntArray(2).also { spacer.getLocationOnScreen(it) }
        val navLoc    = IntArray(2).also { navBar.getLocationOnScreen(it) }
        val rootLoc   = IntArray(2).also { window.decorView.getLocationOnScreen(it) }

        val spacerScreenY = spacerLoc[1]
        val navScreenY    = navLoc[1]
        val rootScreenY   = rootLoc[1]

        val pinnedScreenY = navScreenY - button.height - dpToPx(8)
        val targetScreenY = minOf(spacerScreenY, pinnedScreenY)   // ← minOf is correct

        val statusBarHeight = window.decorView.rootWindowInsets?.   systemWindowInsetTop ?: 0
        button.y = (targetScreenY - rootScreenY - statusBarHeight).toFloat()

        button.visibility = if (spacerScreenY + spacer.height < 0) android.view.View.GONE
        else android.view.View.VISIBLE
    }
    // ── Grid + Recent Activity ────────────────────────────────────────────────
    private fun refreshGridAndActivity() {
        lifecycleScope.launch(Dispatchers.IO) {
            val allConversations = db.conversationDao().getAllConversations()

            val quickScanIds = QuickScanManager.load(this@UploadActivity)
                .map { it.conversationId }
                .toSet()

            val plantations = allConversations.filter { it.id !in quickScanIds }

            plantations.forEach { conv ->
                db.promptDao().deleteAllHiddenForConversation(conv.id)
            }

            val promptMap = plantations.associate { conv ->
                conv.id to db.promptDao().getPromptsForConversation(conv.id)
            }

            val recentScans = promptMap.values
                .flatten()
                .sortedByDescending { it.timestamp }

            withContext(Dispatchers.Main) {

                val spacer = findViewById<android.view.View>(R.id.plantationButtonSpacer)

                if (plantations.isEmpty()) {
                    spacer?.visibility              = android.view.View.GONE
                    plantationsEmptyText.visibility = android.view.View.VISIBLE
                    plantationGrid.visibility       = android.view.View.GONE
                } else {
                    spacer?.visibility              = android.view.View.VISIBLE
                    plantationsEmptyText.visibility = android.view.View.GONE
                    plantationGrid.visibility       = android.view.View.VISIBLE

                    val adapter = PlantationGridAdapter(
                        context     = this@UploadActivity,
                        plantations = plantations,
                        promptMap   = promptMap,
                        scope       = lifecycleScope
                    )
                    plantationGrid.adapter = adapter
                    fixGridViewHeightInScrollView(plantationGrid, adapter)

                    plantationGrid.setOnItemClickListener { _, _, position, _ ->
                        val conv = plantations[position]
                        val intent = Intent(this@UploadActivity, ConversationsActivity::class.java).apply {
                            putExtra(EXTRA_CONVERSATION_ID, conv.id)
                            putExtra("conversation_name", conv.name)
                            putExtra("yolo_available", yoloDetector != null)
                            putExtra("cnn_available", maturityClassifier != null)
                        }
                        startActivity(intent)
                    }
                    plantationGrid.setOnItemLongClickListener { _, _, position, _ ->
                        val conv = plantations[position]
                        showPlantationManageDialog(conv)
                        true
                    }
                }

                // ── Recent activity strip ─────────────────────────────────────
                val childCount = activityContainer.childCount
                for (i in childCount - 1 downTo 0) {
                    val child = activityContainer.getChildAt(i)
                    if (child.id != R.id.activityEmptyText) {
                        activityContainer.removeViewAt(i)
                    }
                }

                if (recentScans.isEmpty()) {
                    activityEmptyText.visibility = android.view.View.VISIBLE
                } else {
                    activityEmptyText.visibility = android.view.View.GONE
                    val initialCount = 5
                    recentScans.take(initialCount).forEach { prompt ->
                        addActivityRow(prompt, plantations)
                    }

                    if (recentScans.size > initialCount) {
                        val showMoreBtn = com.google.android.material.button.MaterialButton(
                            this@UploadActivity,
                            null,
                            com.google.android.material.R.attr.borderlessButtonStyle
                        ).apply {
                            id = R.id.activityShowMoreBtn
                            text = "Show more (${recentScans.size - initialCount} more)"
                            textSize = 12f
                            setTextColor(ContextCompat.getColor(this@UploadActivity, R.color.green))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
                        }
                        activityContainer.addView(showMoreBtn)

                        var shown = initialCount
                        showMoreBtn.setOnClickListener {
                            val nextBatch = recentScans.drop(shown).take(5)
                            val btnIndex  = activityContainer.indexOfChild(showMoreBtn)
                            nextBatch.forEachIndexed { i, prompt ->
                                addActivityRow(prompt, plantations, insertAt = btnIndex + i)
                            }
                            shown += nextBatch.size
                            if (shown >= recentScans.size) {
                                activityContainer.removeView(showMoreBtn)
                            } else {
                                showMoreBtn.text = "Show more (${recentScans.size - shown} more)"
                            }
                        }
                    }
                }
                window.decorView.post { syncButtonVisibility() }
            }
        }
    }

    private fun showPlantationManageDialog(conv: ConversationEntity) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(conv.name)
            .setItems(arrayOf("Edit Name", "Delete Plantation")) { _, which ->
                when (which) {
                    0 -> showEditPlantationNameDialog(conv)
                    1 -> deletePlantation(conv)
                }
            }.show()
    }

    private fun showEditPlantationNameDialog(conv: ConversationEntity) {
        val input = android.widget.EditText(this).apply { setText(conv.name) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Edit Plantation Name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        db.conversationDao().updateConversationName(conv.id, newName)
                        refreshGridAndActivity()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deletePlantation(conv: ConversationEntity) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete Plantation")
            .setMessage("Are you sure you want to delete \"${conv.name}\"? This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    db.conversationDao().deleteConversation(conv)
                    refreshGridAndActivity()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Quick Scan chooser ────────────────────────────────────────────────────
    private fun showQuickScanChooser() {
        val sheet     = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_quick_scan, null)
        sheet.setContentView(sheetView)

        sheetView.findViewById<android.view.View>(R.id.quickScanOptionCamera).setOnClickListener {
            sheet.dismiss()
            openQuickScanCamera()
        }
        sheetView.findViewById<android.view.View>(R.id.quickScanOptionGallery).setOnClickListener {
            sheet.dismiss()
            quickScanGalleryLauncher.launch(PickVisualMediaRequest())
        }
        sheet.show()
    }

    private fun openQuickScanCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            quickScanCameraPermission.launch(android.Manifest.permission.CAMERA)
            return
        }
        detectionCameraLauncher.launch(
            Intent(this, CameraDetectionActivity::class.java)
                .putExtra(ConversationsActivity.EXTRA_IS_QUICK_SCAN, true)
        )
    }

    private fun launchQuickScan(uris: ArrayList<Uri>) {
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
        }
        val intent = Intent(this, ConversationsActivity::class.java).apply {
            putParcelableArrayListExtra(ConversationsActivity.EXTRA_IMAGE_URIS, uris)
            putExtra(ConversationsActivity.EXTRA_IS_QUICK_SCAN, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    // ── Quick Scans section ───────────────────────────────────────────────────
    private fun refreshQuickScans() {
        lifecycleScope.launch(Dispatchers.IO) {
            val scans = QuickScanManager.load(this@UploadActivity)

            val validConversationIds = db.conversationDao()
                .getAllConversations()
                .map { it.id }
                .toSet()

            val validScans = scans.filter { it.conversationId in validConversationIds }
            if (validScans.size != scans.size) {
                QuickScanManager.saveAll(this@UploadActivity, validScans)
            }

            withContext(Dispatchers.Main) {
                val quickScansSection = findViewById<LinearLayout>(R.id.sectionHeaderQuickScans)
                val quickScansCard    = findViewById<LinearLayout>(R.id.quickScansCard)

                if (validScans.isEmpty()) {
                    quickScansSection.visibility   = android.view.View.VISIBLE
                    quickScansCard.visibility      = android.view.View.VISIBLE
                    quickScansEmptyText.visibility = android.view.View.VISIBLE
                    return@withContext
                }

                quickScansSection.visibility   = android.view.View.VISIBLE
                quickScansCard.visibility      = android.view.View.VISIBLE
                quickScansEmptyText.visibility = android.view.View.GONE
                quickScansContainer.removeAllViews()

                val inflater = android.view.LayoutInflater.from(this@UploadActivity)
                val fmt = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                val initialScanCount = 5

                validScans.take(initialScanCount).forEach { scan ->
                    val row = inflater.inflate(R.layout.item_quick_scan_row, quickScansContainer, false)

                    row.findViewById<TextView>(R.id.quickScanDate).text =
                        fmt.format(java.util.Date(scan.timestamp))

                    val badge = row.findViewById<TextView>(R.id.quickScanBadge)
                    bindStageColor(
                        stageBadge  = badge,
                        progressBar = row.findViewById(R.id.quickScanProgressBar),
                        stage       = quickScanDiagnosisToStage(scan.diagnosis),
                        cropWeek    = null,
                        context     = this@UploadActivity
                    )

                    val thumb = row.findViewById<android.widget.ImageView>(R.id.quickScanThumb)
                    thumb.setImageBitmap(null)
                    thumb.tag = scan.imageUri

                    val bmp = loadThumbnailSafe(scan.imageUri)
                    withContext(Dispatchers.Main) {
                        if (bmp != null && thumb.tag == scan.imageUri) {
                            thumb.setImageBitmap(bmp)
                            thumb.clipToOutline   = true
                            thumb.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                            thumb.setColorFilter(
                                android.graphics.Color.argb(60, 0, 0, 0),
                                android.graphics.PorterDuff.Mode.SRC_ATOP
                            )
                        }
                    }

                    val openBtn = row.findViewById<com.google.android.material.button.MaterialButton>(R.id.quickScanOpenBtn)
                    openBtn.background = ContextCompat.getDrawable(this@UploadActivity, R.drawable.rounded_card_thumb_placeholder_bg)
                    openBtn.setOnClickListener {
                        startActivity(
                            Intent(this@UploadActivity, ConversationsActivity::class.java)
                                .putExtra(ConversationsActivity.EXTRA_CONVERSATION_ID, scan.conversationId)
                                .putExtra(ConversationsActivity.EXTRA_IS_QUICK_SCAN, true)
                        )
                    }
                    quickScansContainer.addView(row)

                    val divider = android.view.View(this@UploadActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).also { it.setMargins(0, 4, 0, 4) }
                        setBackgroundColor(0x1A000000)
                    }
                    quickScansContainer.addView(divider)
                }

                if (validScans.size > initialScanCount) {
                    var shownCount = initialScanCount
                    val showMoreBtn = com.google.android.material.button.MaterialButton(
                        this@UploadActivity, null,
                        com.google.android.material.R.attr.borderlessButtonStyle
                    ).apply {
                        id = R.id.quickScansShowMoreBtn
                        text = "Show more (${validScans.size - shownCount} more)"
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(this@UploadActivity, R.color.green))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
                    }
                    quickScansContainer.addView(showMoreBtn)

                    showMoreBtn.setOnClickListener {
                        val nextBatch = validScans.drop(shownCount).take(5)
                        val btnIndex  = quickScansContainer.indexOfChild(showMoreBtn)
                        val inf2 = android.view.LayoutInflater.from(this@UploadActivity)
                        val fmt2 = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())

                        nextBatch.forEachIndexed { i, scan ->
                            val row = inf2.inflate(R.layout.item_quick_scan_row, quickScansContainer, false)
                            row.findViewById<TextView>(R.id.quickScanDate).text =
                                fmt2.format(java.util.Date(scan.timestamp))

                            val badge = row.findViewById<TextView>(R.id.quickScanBadge)
                            bindStageColor(
                                stageBadge  = badge,
                                progressBar = row.findViewById(R.id.quickScanProgressBar),
                                stage       = quickScanDiagnosisToStage(scan.diagnosis),
                                cropWeek    = null,
                                context     = this@UploadActivity
                            )

                            val thumb = row.findViewById<android.widget.ImageView>(R.id.quickScanThumb)
                            thumb.setImageBitmap(null)
                            thumb.tag = scan.imageUri

                            lifecycleScope.launch(Dispatchers.IO) {
                                val bmp = loadThumbnailSafe(scan.imageUri)
                                withContext(Dispatchers.Main) {
                                    if (bmp != null && thumb.tag == scan.imageUri) {
                                        thumb.setImageBitmap(bmp)
                                        thumb.clipToOutline   = true
                                        thumb.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                                        thumb.setColorFilter(
                                            android.graphics.Color.argb(60, 0, 0, 0),
                                            android.graphics.PorterDuff.Mode.SRC_ATOP
                                        )
                                    }
                                }
                            }

                            val openBtn = row.findViewById<com.google.android.material.button.MaterialButton>(R.id.quickScanOpenBtn)
                            openBtn.background = ContextCompat.getDrawable(this@UploadActivity, R.drawable.rounded_card_thumb_placeholder_bg)
                            openBtn.setOnClickListener {
                                startActivity(
                                    Intent(this@UploadActivity, ConversationsActivity::class.java)
                                        .putExtra(ConversationsActivity.EXTRA_CONVERSATION_ID, scan.conversationId)
                                        .putExtra(ConversationsActivity.EXTRA_IS_QUICK_SCAN, true)
                                )
                            }

                            quickScansContainer.addView(row, btnIndex + (i * 2))
                            val divider = android.view.View(this@UploadActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                                ).also { it.setMargins(0, 4, 0, 4) }
                                setBackgroundColor(0x1A000000)
                            }
                            quickScansContainer.addView(divider, btnIndex + (i * 2) + 1)
                        }

                        shownCount += nextBatch.size
                        if (shownCount >= validScans.size) {
                            quickScansContainer.removeView(showMoreBtn)
                        } else {
                            showMoreBtn.text = "Show more (${validScans.size - shownCount} more)"
                        }
                    }
                }
            }
        }
    }

    // ── Collapsible section toggle ────────────────────────────────────────────
    private fun toggleSection(
        body: android.view.View,
        chevron: android.widget.ImageView
    ) {
        val expanding       = body.visibility != android.view.View.VISIBLE
        val targetRotation  = if (expanding) 0f else 180f
        if (expanding) body.visibility = android.view.View.VISIBLE
        body.animate().cancel()
        chevron.animate().rotation(targetRotation).setDuration(220).start()
        if (!expanding) {
            body.animate().setDuration(220)
                .withEndAction { body.visibility = android.view.View.GONE }
                .start()
        }
    }

    // ── Activity row ──────────────────────────────────────────────────────────
    private fun addActivityRow(
        prompt: PromptEntity,
        plantations: List<ConversationEntity>,
        insertAt: Int = -1
    ) {
        val plantationName = plantations
            .firstOrNull { it.id == prompt.conversationId }?.name ?: "Unknown"

        val rawStage = when {
            prompt.diagnostic.isBlank() -> null
            prompt.diagnostic.lowercase().replace(" ", "_").split("|").first() == "no_detection" -> null
            prompt.diagnostic.startsWith("Stage Conflict")     -> "Stage Conflict"
            prompt.diagnostic.startsWith("Insufficient Batch") -> "Insufficient"
            else -> prompt.diagnostic.split("|").firstOrNull()?.takeIf { it.isNotBlank() }
        }
        val stage = when (rawStage) {
            null             -> "No detection"
            "Stage Conflict" -> "⚠️ Inconclusive"
            "Insufficient"   -> "📊 Insufficient"
            else             -> rawStage
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dpToPx(10)) }
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val dot = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply {
                setMargins(0, 0, dpToPx(10), 0)
            }
            setBackgroundResource(when (rawStage) {
                "Harvest Ready"  -> R.drawable.circle_green
                "Near Harvest"   -> R.drawable.circle_yellow
                "Not Ready"      -> R.drawable.circle_red
                "Stage Conflict",
                "Insufficient"   -> R.drawable.circle_inconclusive
                else             -> R.drawable.circle_gray
            })
        }

        val text = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            this.text = "$plantationName — $stage"
            textSize  = 12f
            setTextColor(ContextCompat.getColor(this@UploadActivity, R.color.black))
            typeface  = androidx.core.content.res.ResourcesCompat
                .getFont(this@UploadActivity, R.font.nunito_semibold)
        }

        val ts = TextView(this).apply {
            this.text = prompt.timestamp
            textSize  = 10f
            setTextColor(ContextCompat.getColor(this@UploadActivity, R.color.gray))
            typeface  = androidx.core.content.res.ResourcesCompat
                .getFont(this@UploadActivity, R.font.nunito_regular)
        }

        row.addView(dot)
        row.addView(text)
        row.addView(ts)

        row.setOnClickListener {
            val conv = plantations.firstOrNull { it.id == prompt.conversationId } ?: return@setOnClickListener
            startActivity(Intent(this, ConversationsActivity::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conv.id)
                putExtra("conversation_name", conv.name)
                putExtra("yolo_available", yoloDetector != null)
                putExtra("cnn_available", maturityClassifier != null)
            })
        }

        if (insertAt >= 0) activityContainer.addView(row, insertAt)
        else activityContainer.addView(row)
    }

    // ── GridView height fix inside ScrollView ─────────────────────────────────
    private fun fixGridViewHeightInScrollView(grid: GridView, adapter: PlantationGridAdapter) {
        grid.post {
            val columns = 2
            val rows    = Math.ceil(adapter.count.toDouble() / columns).toInt()
            if (rows == 0) return@post

            val item = adapter.getView(0, null, grid)
            item.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(grid.width / columns, android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.UNSPECIFIED
            )
            val totalHeight = rows * item.measuredHeight + (rows - 1) * grid.verticalSpacing + dpToPx(28)
            val params      = grid.layoutParams
            params.height   = totalHeight
            grid.layoutParams = params
            grid.requestLayout()

            // ← ADD THIS
            grid.post { syncButtonVisibility() }
        }
    }

    // ── Camera (plantation-gated) ─────────────────────────────────────────────
    private fun openCameraWithPlantationCheck() {
        lifecycleScope.launch(Dispatchers.IO) {
            val allConversations = db.conversationDao().getAllConversations()
            val quickScanIds = QuickScanManager.load(this@UploadActivity)
                .map { it.conversationId }.toSet()
            val plantations = allConversations.filter { it.id !in quickScanIds }

            withContext(Dispatchers.Main) {
                if (plantations.isEmpty()) {
                    Toast.makeText(this@UploadActivity,
                        "Please create a plantation first before scanning",
                        Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@UploadActivity, PlantationProfileActivity::class.java))
                    return@withContext
                }
                if (checkSelfPermission(android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 100)
                    return@withContext
                }
                detectionCameraLauncher.launch(
                    Intent(this@UploadActivity, CameraDetectionActivity::class.java)
                )
            }
        }
    }

    // ── Model initialisation ──────────────────────────────────────────────────
    private fun shouldAutoCheckForUpdates(): Boolean {
        val prefs     = getSharedPreferences("model_auto_update_prefs", MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_auto_check_timestamp", 0L)
        if (lastCheck == 0L) return true
        val days = java.util.concurrent.TimeUnit.MILLISECONDS
            .toDays(System.currentTimeMillis() - lastCheck)
        return days >= 7
    }

    private fun recordAutoCheckTimestamp() {
        getSharedPreferences("model_auto_update_prefs", MODE_PRIVATE)
            .edit().putLong("last_auto_check_timestamp", System.currentTimeMillis()).commit()
    }

    private fun initializeAppModelsAsync() {
        if (modelLoadingInProgress) return
        modelLoadingInProgress = true
        modelErrorDialogShown  = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (shouldAutoCheckForUpdates()) {
                    recordAutoCheckTimestamp()
                    updateInProgress = true
                    ModelUpdateManager.checkForModelUpdates(this@UploadActivity, this@UploadActivity)
                    updateInProgress = false
                }
                ModelManager.initializeModels(this@UploadActivity)
                updateLocalModelReferences()
                modelsLoaded = ModelManager.areModelsAvailable()

                withContext(Dispatchers.Main) {
                    modelLoadingInProgress = false
                    updateModelStatusIndicator()
                    if (yoloDetector != null && maturityClassifier != null) {
                        Toast.makeText(this@UploadActivity, "✓ All AI models loaded", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("UploadActivity", "Failed to initialize models: ${e.message}", e)
                updateInProgress       = false
                modelLoadingInProgress = false
                withContext(Dispatchers.Main) {
                    if (ModelManager.areModelsAvailable()) {
                        updateLocalModelReferences()
                        updateModelStatusIndicator()
                        Toast.makeText(this@UploadActivity, "Using previously loaded models", Toast.LENGTH_SHORT).show()
                    } else {
                        updateModelStatusIndicator()
                        if (!modelErrorDialogShown && !isFinishing) {
                            modelErrorDialogShown = true
                            android.app.AlertDialog.Builder(this@UploadActivity)
                                .setTitle("AI Models Not Available")
                                .setMessage("Failed to load AI models.\n\nError: ${e.message ?: "Unknown"}\n\nYou can still use the app without AI analysis.")
                                .setPositiveButton("OK", null).show()
                        }
                    }
                }
            }
            startModelStatusChecker()
        }
    }

    private fun startModelStatusChecker() {
        lifecycleScope.launch {
            delay(5000)
            while (true) {
                delay(30000)
                if (!modelLoadingInProgress) updateModelStatusIndicator()
            }
        }
    }

    private fun updateLocalModelReferences() {
        yoloDetector       = ModelManager.getYoloDetector()
        maturityClassifier = ModelManager.getMaturityClassifier()
        runOnUiThread { updateModelStatusIndicator() }
    }

    private fun updateModelStatusIndicator() {
        val yoloReady = yoloDetector != null
        val cnnReady  = maturityClassifier != null
        runOnUiThread {
            when {
                modelLoadingInProgress -> {
                    modelStatusIndicator.setImageResource(R.drawable.ic_model_loading)
                    modelStatusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.blue))
                    modelStatusText.text = "Loading AI..."
                    modelStatusText.setTextColor(ContextCompat.getColor(this, R.color.blue))
                }
                yoloReady && cnnReady -> {
                    modelStatusIndicator.setImageResource(R.drawable.ic_model_ready)
                    modelStatusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.green))
                    modelStatusText.text = "AI Ready"
                    modelStatusText.setTextColor(ContextCompat.getColor(this, R.color.green))
                }
                yoloReady || cnnReady -> {
                    modelStatusIndicator.setImageResource(R.drawable.ic_model_partial)
                    modelStatusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.orange))
                    modelStatusText.text = "AI Limited"
                    modelStatusText.setTextColor(ContextCompat.getColor(this, R.color.orange))
                }
                else -> {
                    modelStatusIndicator.setImageResource(R.drawable.ic_model_offline)
                    modelStatusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.red))
                    modelStatusText.text = "AI Offline"
                    modelStatusText.setTextColor(ContextCompat.getColor(this, R.color.red))
                }
            }
        }
    }

    private fun showModelStatusDialog() {
        val yoloReady = yoloDetector != null
        val cnnReady  = maturityClassifier != null
        val loading   = modelLoadingInProgress
        val message   = buildString {
            append("🤖 AI Model Status\n\n")
            if (loading) append("🔄 Models are currently loading...\n\n")
            append("📊 Current Status:\n")
            append(if (yoloReady) "✅ YOLO (Object Detection) loaded\n" else if (loading) "⏳ YOLO loading...\n" else "❌ YOLO not loaded\n")
            append(if (cnnReady)  "✅ CNN-LSTM (Plant Analysis) loaded\n" else if (loading) "⏳ CNN-LSTM loading...\n" else "❌ CNN-LSTM not loaded\n")
            append("\nModels load automatically at app startup.")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Model Status")
            .setMessage(message)
            .setPositiveButton("Check for Updates") { _, _ -> checkForUpdatesManually() }
            .setNegativeButton("Close", null).show()
    }

    fun checkForUpdatesManually() {
        if (updateInProgress) {
            Toast.makeText(this, "Update already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        recordAutoCheckTimestamp()
        lifecycleScope.launch(Dispatchers.IO) {
            updateInProgress = true
            ModelUpdateManager.checkForModelUpdates(this@UploadActivity, this@UploadActivity)
            updateInProgress = false
            withContext(Dispatchers.Main) {
                updateLocalModelReferences()
                Toast.makeText(this@UploadActivity, "Models are up to date", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── ModelUpdateManager.UpdateListener ────────────────────────────────────
    override fun onUpdateStarted() { /* silent on home screen */ }

    override fun onDownloadProgress(fileName: String, progress: Int, totalFiles: Int) {
        runOnUiThread { modelStatusText.text = "Updating ($progress/$totalFiles)…" }
    }

    override fun onUpdateCompleted(success: Boolean, message: String) {
        runOnUiThread {
            if (success) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        ModelManager.cleanup()
                        ModelManager.initializeModels(this@UploadActivity)
                        updateLocalModelReferences()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@UploadActivity, "Models updated and reloaded", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("UploadActivity", "Failed to reload after update", e)
                    }
                }
            } else if (message != "Models are already up to date") {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } else {
                updateLocalModelReferences()
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
        if (requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCameraWithPlantationCheck()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun loadThumbnailSafe(uriString: String): android.graphics.Bitmap? {
        if (uriString.isBlank()) return null
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
        return try {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "file"    -> android.graphics.BitmapFactory.decodeFile(uri.path, opts)
                "content" -> contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream, null, opts)
                }
                else -> null
            }
        } catch (se: SecurityException) {
            try {
                val path = Uri.parse(uriString).path ?: return null
                android.graphics.BitmapFactory.decodeFile(path, opts)
            } catch (_: Exception) { null }
        } catch (_: Exception) { null }
    }

    private fun quickScanDiagnosisToStage(diagnosis: String): String? {
        if (diagnosis.isBlank()) return null
        val normalized = diagnosis.lowercase().replace(" ", "_")
        if (normalized.split("|").first() == "no_detection") return null
        if (diagnosis.startsWith("Stage Conflict"))     return "Stage Conflict"
        if (diagnosis.startsWith("Insufficient Batch")) return "Insufficient"
        return diagnosis.split("|").firstOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}