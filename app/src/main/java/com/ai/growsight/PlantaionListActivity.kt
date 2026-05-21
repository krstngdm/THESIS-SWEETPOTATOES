package com.ai.growsight

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.ai.growsight.ConversationsActivity.Companion.EXTRA_CONVERSATION_ID
import com.ai.growsight.data.AppDatabase
import com.ai.growsight.data.ConversationEntity
import com.ai.growsight.data.PromptEntity
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.contract.ActivityResultContracts

class PlantationListActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var plantationListView: ListView
    private lateinit var quickScanListView: ListView
    private lateinit var emptyState: LinearLayout
    private lateinit var quickScanEmptyState: LinearLayout
    private lateinit var subtitleText: TextView
    private lateinit var tabLayout: TabLayout

    private var pendingWeather: com.ai.growsight.ai.WeatherData? = null

    private val quickScanCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openQuickScanCamera()
        else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
    }

    private val detectionCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uriString   = result.data?.getStringExtra(CameraDetectionActivity.RESULT_IMAGE_URI)
            val confidence  = result.data?.getFloatExtra("detection_confidence", 0f) ?: 0f
            val wasDetected = result.data?.getBooleanExtra("was_detected", false) ?: false
            if (uriString != null) {
                val uri = Uri.parse(uriString)
                startActivity(Intent(this, ConversationsActivity::class.java).apply {
                    putParcelableArrayListExtra(ConversationsActivity.EXTRA_IMAGE_URIS, arrayListOf(uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra("camera_detection_confidence", confidence)
                    putExtra("camera_was_detected", wasDetected)
                    putExtra("preloaded_weather_temp", pendingWeather?.temperatureCelsius ?: -999f)
                    putExtra("preloaded_weather_humidity", pendingWeather?.humidity ?: -1)
                    putExtra("preloaded_weather_precip", pendingWeather?.precipitationMm ?: -1f)
                    putExtra("preloaded_weather_code", pendingWeather?.weatherCode ?: -1)
                    putExtra(ConversationsActivity.EXTRA_IS_QUICK_SCAN, true)
                })
                pendingWeather = null
            }
        }
    }

    private val quickScanGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris ->
        if (uris.isNotEmpty()) {
            startActivity(Intent(this, ConversationsActivity::class.java).apply {
                putParcelableArrayListExtra(ConversationsActivity.EXTRA_IMAGE_URIS, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(ConversationsActivity.EXTRA_IS_QUICK_SCAN, true)
            })
        } else {
            Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plantation_list)

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "prompts-db")
            .fallbackToDestructiveMigration().build()

        plantationListView  = findViewById(R.id.plantationListView)
        quickScanListView   = findViewById(R.id.quickScanListView)
        emptyState          = findViewById(R.id.listEmptyState)
        quickScanEmptyState = findViewById(R.id.quickScanEmptyState)
        subtitleText        = findViewById(R.id.listSubtitle)
        tabLayout           = findViewById(R.id.plantationTabs)

        tabLayout.addTab(tabLayout.newTab().setText("My Plantations"))
        tabLayout.addTab(tabLayout.newTab().setText("Quick Scans"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> showPlantationsTab()
                    1 -> showQuickScansTab()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        findViewById<com.google.android.material.button.MaterialButton>(R.id.emptyCreateButton)
            .setOnClickListener {
                startActivity(Intent(this, PlantationProfileActivity::class.java))
            }

        // Bottom nav
        findViewById<LinearLayout>(R.id.listNavHome).setOnClickListener {
            startActivity(Intent(this, UploadActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }
        findViewById<LinearLayout>(R.id.listNavPlantations).setOnClickListener {
            tabLayout.getTabAt(0)?.select()
            plantationListView.smoothScrollToPosition(0)
        }
        findViewById<LinearLayout>(R.id.listNavCamera).setOnClickListener {
            showQuickScanChooser()
        }

        val backButton = findViewById<ImageButton>(R.id.logoButton)

        backButton.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    // ── Tab visibility helpers ────────────────────────────────────────────────

    private fun showPlantationsTab() {
        // hide quick scan views
        quickScanListView.visibility   = View.GONE
        quickScanEmptyState.visibility = View.GONE
        // plantation views already managed by loadPlantations()
        loadPlantations()
    }

    private fun showQuickScansTab() {
        // hide plantation views
        plantationListView.visibility = View.GONE
        emptyState.visibility         = View.GONE
        loadQuickScans()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadData() {
        when (tabLayout.selectedTabPosition) {
            0 -> loadPlantations()
            1 -> loadQuickScans()
        }
    }

    private fun loadPlantations() {
        lifecycleScope.launch(Dispatchers.IO) {
            val allConversations = db.conversationDao().getAllConversations()

            // ← Same filter as UploadActivity
            val quickScanIds = QuickScanManager.load(this@PlantationListActivity)
                .map { it.conversationId }
                .toSet()

            val plantations = allConversations.filter { it.id !in quickScanIds }
            val promptMap   = plantations.associate { conv ->
                conv.id to db.promptDao().getPromptsForConversation(conv.id)
            }
            withContext(Dispatchers.Main) {
                subtitleText.text = "${plantations.size} active"
                quickScanListView.visibility   = View.GONE
                quickScanEmptyState.visibility = View.GONE

                if (plantations.isEmpty()) {
                    plantationListView.visibility = View.GONE
                    emptyState.visibility         = View.VISIBLE
                } else {
                    emptyState.visibility         = View.GONE
                    plantationListView.visibility = View.VISIBLE
                    plantationListView.adapter    = PlantationListAdapter(plantations, promptMap)
                    plantationListView.setOnItemClickListener { _, _, position, _ ->
                        val conv = plantations[position]
                        startActivity(Intent(this@PlantationListActivity, ConversationsActivity::class.java).apply {
                            putExtra(EXTRA_CONVERSATION_ID, conv.id)
                            putExtra("conversation_name", conv.name)
                        })
                    }
                }
            }
        }
    }

    private fun loadQuickScans() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Use QuickScanManager — same as UploadActivity does
            val scans = QuickScanManager.load(this@PlantationListActivity)

            // Clean up orphaned scans whose conversation was deleted
            val validConversationIds = db.conversationDao()
                .getAllConversations()
                .map { it.id }
                .toSet()

            val validScans = scans.filter { it.conversationId in validConversationIds }
            if (validScans.size != scans.size) {
                QuickScanManager.saveAll(this@PlantationListActivity, validScans)
            }

            withContext(Dispatchers.Main) {
                // Hide plantation views
                plantationListView.visibility = View.GONE
                emptyState.visibility         = View.GONE

                if (validScans.isEmpty()) {
                    quickScanListView.visibility   = View.GONE
                    quickScanEmptyState.visibility = View.VISIBLE
                    subtitleText.text = "0 quick scans"
                } else {
                    quickScanEmptyState.visibility = View.GONE
                    quickScanListView.visibility   = View.VISIBLE
                    subtitleText.text = "${validScans.size} quick scan${if (validScans.size != 1) "s" else ""}"
                    quickScanListView.adapter = QuickScanListAdapter(validScans)
                }
            }
        }
    }

    // ── Quick scan chooser / camera ───────────────────────────────────────────

    private fun showQuickScanChooser() {
        val sheet     = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_quick_scan, null)
        sheet.setContentView(sheetView)
        sheetView.findViewById<View>(R.id.quickScanOptionCamera).setOnClickListener {
            sheet.dismiss(); openQuickScanCamera()
        }
        sheetView.findViewById<View>(R.id.quickScanOptionGallery).setOnClickListener {
            sheet.dismiss()
            quickScanGalleryLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
        sheet.show()
    }

    private fun openQuickScanCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            quickScanCameraPermission.launch(android.Manifest.permission.CAMERA)
            return
        }
        com.ai.growsight.ai.LocationWeatherManager.startWeatherFlow(
            activity = this,
            callback = object : com.ai.growsight.ai.LocationWeatherManager.WeatherFlowCallback {
                override fun onWeatherReady(weather: com.ai.growsight.ai.WeatherData?) {
                    pendingWeather = weather
                    detectionCameraLauncher.launch(
                        Intent(this@PlantationListActivity, CameraDetectionActivity::class.java)
                            .putExtra(ConversationsActivity.EXTRA_IS_QUICK_SCAN, true)
                    )
                }
                override fun onSkipped() {
                    pendingWeather = null
                    detectionCameraLauncher.launch(
                        Intent(this@PlantationListActivity, CameraDetectionActivity::class.java)
                            .putExtra(ConversationsActivity.EXTRA_IS_QUICK_SCAN, true)
                    )
                }
            }
        )
    }

    // ── Plantation list adapter ───────────────────────────────────────────────

    private inner class PlantationListAdapter(
        private val items: List<ConversationEntity>,
        private val promptMap: Map<Long, List<PromptEntity>>
    ) : BaseAdapter() {

        override fun getCount()                = items.size
        override fun getItem(pos: Int)         = items[pos]
        override fun getItemId(pos: Int): Long = items[pos].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@PlantationListActivity)
                .inflate(R.layout.item_plantation_list_row, parent, false)

            val plantation = items[position]
            val prompts    = promptMap[plantation.id] ?: emptyList()

            view.findViewById<TextView>(R.id.rowPlantationName).text = plantation.name

            val week     = estimateCurrentWeek(plantation)
            val location = plantation.locationLabel?.takeIf { it.isNotBlank() }
            val meta     = buildString {
                if (week != null) append("Week $week")
                if (location != null) append("  ·  $location")
                append("  ·  ${prompts.size} scan${if (prompts.size != 1) "s" else ""}")
            }
            view.findViewById<TextView>(R.id.rowMeta).text = meta

            val stage = extractLatestStage(prompts)
            val stageView = view.findViewById<TextView>(R.id.rowStageBadge)
            val progressBar = view.findViewById<ProgressBar>(R.id.rowProgressBar)

            // Use the bindStageColor helper
            bindStageColor(
                stageBadge = stageView,
                progressBar = progressBar,
                stage = stage,
                cropWeek = week,
                context = this@PlantationListActivity
            )
            stageView.visibility = View.VISIBLE

            // Thumbnail
            val avatarImage    = view.findViewById<ImageView>(R.id.rowAvatarImage)
            val latestImageUri = prompts.lastOrNull { it.imageUris.isNotEmpty() }
                ?.imageUris?.firstOrNull()
            if (latestImageUri != null) {
                avatarImage.setPadding(0, 0, 0, 0)
                avatarImage.setBackgroundResource(R.drawable.rounded_corner_gray_light)
                avatarImage.clipToOutline = true
                avatarImage.setImageURI(Uri.parse(latestImageUri))
                avatarImage.scaleType  = ImageView.ScaleType.CENTER_CROP
            } else {
                val p = (15 * resources.displayMetrics.density).toInt()
                avatarImage.setPadding(p, p, p, p)
                avatarImage.clipToOutline = false
                avatarImage.setBackgroundResource(R.drawable.rounded_corner_white)
                avatarImage.setImageResource(R.drawable.frame)
                avatarImage.scaleType = ImageView.ScaleType.CENTER
            }

            // Week chips
// Week chips — mirrors PlantationGridAdapter exactly
            val chipRow = view.findViewById<LinearLayout>(R.id.rowWeekChips)
            chipRow.removeAllViews()
            buildWeekChips(chipRow, prompts, week, plantation.plantingDate)

            return view
        }

        private fun extractLatestStage(prompts: List<PromptEntity>): String? =
            prompts.lastOrNull { it.diagnostic != "no_detection" && it.diagnostic.isNotBlank() }
                ?.diagnostic?.split("|")?.firstOrNull()?.takeIf { it.isNotBlank() }

        private fun estimateCurrentWeek(plantation: ConversationEntity): Int? {
            val planted = plantation.plantingDate.takeIf { it > 0L }
            if (planted != null) {
                val diffMs = System.currentTimeMillis() - planted
                return (diffMs / (1000L * 60 * 60 * 24 * 7)).toInt().coerceIn(1, 22)
            }
            return plantation.cropAgeWeeks
        }
    }

    // ── Quick scan adapter ────────────────────────────────────────────────────

    private inner class QuickScanListAdapter(
        private val items: List<QuickScanManager.QuickScan>  // ← use the same type as UploadActivity
    ) : BaseAdapter() {

        private val fmt = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())

        override fun getCount()                = items.size
        override fun getItem(pos: Int)         = items[pos]
        override fun getItemId(pos: Int): Long = items[pos].conversationId

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@PlantationListActivity)
                .inflate(R.layout.item_plantation_list_row, parent, false)

            val scan = items[position]

            // Title: formatted timestamp
            view.findViewById<TextView>(R.id.rowPlantationName).text =
                "Quick Scan  ·  ${fmt.format(java.util.Date(scan.timestamp))}"

            // Meta: diagnosis
            val fmt = java.text.SimpleDateFormat("MMM d, yyyy  ·  h:mm a", java.util.Locale.getDefault())
            view.findViewById<TextView>(R.id.rowMeta).text = fmt.format(java.util.Date(scan.timestamp))

            // Stage badge — uses bindStageColor for consistency with My Plantations tab
            val stageView = view.findViewById<TextView>(R.id.rowStageBadge)
            bindStageColor(
                stageBadge  = stageView,
                progressBar = view.findViewById(R.id.rowProgressBar),
                stage       = quickScanDiagnosisToStage(scan.diagnosis),
                cropWeek    = null,
                context     = this@PlantationListActivity
            )
            stageView.visibility = View.VISIBLE

            // Thumbnail — same loadThumbnailSafe pattern as UploadActivity
            val avatarImage = view.findViewById<ImageView>(R.id.rowAvatarImage)
            val iconPadding = (15 * resources.displayMetrics.density).toInt()
            if (scan.imageUri.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val bmp = loadThumbnailSafe(scan.imageUri)
                    withContext(Dispatchers.Main) {
                        if (bmp != null) {
                            avatarImage.setPadding(0, 0, 0, 0)
                            avatarImage.setBackgroundResource(R.drawable.rounded_corner_gray_light)
                            avatarImage.clipToOutline = true
                            avatarImage.setImageBitmap(bmp)
                            avatarImage.scaleType  = ImageView.ScaleType.CENTER_CROP
                        } else {
                            avatarImage.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                            avatarImage.clipToOutline = false
                            avatarImage.setBackgroundResource(R.drawable.gradient_background)
                            avatarImage.setImageResource(R.drawable.grow_sight_leaf)
                            avatarImage.scaleType = ImageView.ScaleType.CENTER
                        }
                    }
                }
            } else {
                avatarImage.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                avatarImage.clipToOutline = false
                avatarImage.setBackgroundResource(R.drawable.gradient_background)
                avatarImage.setImageResource(R.drawable.grow_sight_leaf)
                avatarImage.scaleType = ImageView.ScaleType.CENTER
            }

            // Hide week chips and progress bar — not relevant for quick scans
            view.findViewById<LinearLayout>(R.id.rowWeekChips).visibility = View.GONE
            view.findViewById<ProgressBar>(R.id.rowProgressBar).visibility = View.GONE

            // Tap → open that conversation
            view.setOnClickListener {
                startActivity(
                    Intent(this@PlantationListActivity, ConversationsActivity::class.java)
                        .putExtra(EXTRA_CONVERSATION_ID, scan.conversationId)
                        .putExtra(ConversationsActivity.EXTRA_IS_QUICK_SCAN, true)
                )
            }

            return view
        }
    }

    //helper

    // ── Shared week-chip builder (mirrors PlantationGridAdapter) ─────────────

    private fun buildWeekChips(
        row: LinearLayout,
        prompts: List<PromptEntity>,
        currentWeek: Int?,
        plantingDateMs: Long
    ) {
        row.orientation = LinearLayout.VERTICAL

        val total = currentWeek?.coerceAtLeast(1) ?: prompts.size.coerceAtLeast(1)

        fun promptToWeek(index: Int, prompt: PromptEntity): Int {
            return if (plantingDateMs > 0L) {
                val ts = if (prompt.timestampMs > 0L) prompt.timestampMs
                else System.currentTimeMillis()
                ((( ts - plantingDateMs) / (1000L * 60 * 60 * 24 * 7)).toInt() + 1).coerceAtLeast(1)
            } else {
                index + 1
            }
        }

        val allScannedWeeks: Set<Int> = prompts.mapIndexed { i, p -> promptToWeek(i, p) }.toSet()
        val anomalousWeeks: Set<Int>  = prompts
            .mapIndexed { i, p -> i to p }
            .filter  { (_, p) -> isAnomalousListResult(p.diagnostic) }
            .map     { (i, p) -> promptToWeek(i, p) }
            .toSet()
        val healthyWeeks: Set<Int> = allScannedWeeks - anomalousWeeks

        val chipsPerRow = 10
        val chipSizeDp  = 14
        val textSizeSp  = 7f
        val density     = resources.displayMetrics.density

        (1..total).chunked(chipsPerRow).forEach { weekChunk ->
            val subRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (2 * density).toInt()) }
            }

            weekChunk.forEach { week ->
                val isCurrent   = week == total
                val isHealthy   = week in healthyWeeks
                val isAnomalous = week in anomalousWeeks

                val chip = TextView(this).apply {
                    val sizePx = (chipSizeDp * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                        setMargins((2 * density).toInt(), 0, (2 * density).toInt(), 0)
                    }
                    text     = "$week"
                    gravity  = android.view.Gravity.CENTER
                    textSize = textSizeSp
                    setTextColor(ContextCompat.getColor(this@PlantationListActivity,
                        if (isCurrent || isAnomalous) android.R.color.black else R.color.white))
                    typeface = ResourcesCompat.getFont(
                        this@PlantationListActivity, R.font.nunito_extrabold)
                    setBackgroundResource(when {
                        isCurrent   -> R.drawable.circle_light_gray
                        isHealthy   -> R.drawable.circle_dark_green
                        isAnomalous -> R.drawable.circle_inconclusive
                        else        -> R.drawable.circle_gray
                    })
                }
                subRow.addView(chip)
            }

            row.addView(subRow)
        }
    }

    private fun quickScanDiagnosisToStage(diagnosis: String): String? {
        if (diagnosis.isBlank()) return null
        val normalized = diagnosis.lowercase().replace(" ", "_")
        if (normalized.split("|").first() == "no_detection") return null
        if (diagnosis.startsWith("Stage Conflict"))     return "Stage Conflict"
        if (diagnosis.startsWith("Insufficient Batch")) return "Insufficient"
        return diagnosis.split("|").firstOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun isAnomalousListResult(diagnostic: String): Boolean {
        if (diagnostic.isBlank()) return true
        val normalized = diagnostic.lowercase().replace(" ", "_")
        return when {
            normalized.split("|").first() == "no_detection" -> true
            diagnostic.startsWith("Stage Conflict|")        -> true
            diagnostic.startsWith("Insufficient Batch|")    -> true
            else                                             -> false
        }
    }

    private fun loadThumbnailSafe(uriString: String): android.graphics.Bitmap? {
        if (uriString.isBlank()) return null
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
        return try {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "file"    -> android.graphics.BitmapFactory.decodeFile(uri.path, opts)
                "content" -> contentResolver.openInputStream(uri)
                    ?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                else      -> null
            }
        } catch (_: Exception) { null }
    }
}