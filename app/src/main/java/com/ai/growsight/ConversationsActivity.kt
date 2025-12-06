package com.ai.growsight

import com.github.ybq.android.spinkit.SpinKitView
import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
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
import com.ai.growsight.ai.MaturityClassifier
import com.ai.growsight.ai.YoloDetector
import com.ai.growsight.data.AppDatabase
import com.ai.growsight.data.ConversationEntity
import com.ai.growsight.data.PromptEntity
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
import com.ai.growsight.ai.ModelManager

class ConversationsActivity : AppCompatActivity() {

    companion object {
        const val PICK_IMAGES_REQUEST = 1001
        private const val CAMERA_REQUEST_CODE = 2001
        private const val READ_STORAGE_PERMISSION_REQUEST_CODE = 102
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val CAMERA_PERMISSION_CODE = 3001
        const val EXTRA_IMAGE_URIS = "extra_image_uris"
        val sentUris = mutableSetOf<String>() // keeps track of already-sent images
    }

    private lateinit var uploadedImagesContainer: LinearLayout
    private lateinit var sendButton: ImageButton
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

    private val uploadedUris = mutableListOf<Uri>()
    private var cameraImageUri: Uri? = null
    private var conversationId: Long = -1L

    private lateinit var db: AppDatabase

    // Model variables
    private var yolo: YoloDetector? = null
    private var cnn: MaturityClassifier? = null
    private var modelsLoaded = false

    // Define week ranges based on training
    private val weekRanges = mapOf(
        "Early (1-3)" to Pair(1, 3),
        "Middle (4-8)" to Pair(4, 8),
        "Ready (9-12)" to Pair(9, 12),
        "Mature (13-16)" to Pair(13, 16)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversations)

        // Initialize message container and instruction text
        messagesContainer = findViewById(R.id.messagesContainer)
        instructionText = findViewById(R.id.instructionText)

        loader = findViewById(R.id.wave_loader)

        // Room DB setup
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "prompts-db"
        )
            .fallbackToDestructiveMigration()
            .build()
        val promptDao = db.promptDao()
        val conversationDao = db.conversationDao()

        // Views
        uploadedImagesContainer = findViewById(R.id.uploadedImagesContainer)
        sendButton = findViewById(R.id.sendButton)
        uploadButton = findViewById(R.id.uploadButton)
        deleteButton = findViewById(R.id.deleteButton)
        cameraButton = findViewById(R.id.cameraButton)
        previewContainer = findViewById(R.id.previewContainer)
        scrollContent = findViewById(R.id.scrollContent)
        conversationTitle = findViewById(R.id.conversationTitle)
        conversationListView = findViewById(R.id.conversationListView)
        drawerLayout = findViewById(R.id.drawerLayout)
        val hamburgerButton = findViewById<ImageButton>(R.id.menuButton)
        val backButton = findViewById<ImageButton>(R.id.logoButton)
        val addConversationButton = findViewById<ImageButton>(R.id.addConversationButton)
        val editTitleButton = findViewById<ImageButton>(R.id.editTitleButton)

        val yoloAvailable = intent.getBooleanExtra("yolo_available", false)
        val cnnAvailable = intent.getBooleanExtra("cnn_available", false)

        yolo = ModelManager.getYoloDetector()
        cnn = ModelManager.getMaturityClassifier()

        if (!yoloAvailable || !cnnAvailable) {
            Log.w("ConversationsActivity", "Intent says models not available, but checking ModelManager...")
        }

        updateUIForModelStatus()

        if (yolo == null && cnn == null) {
            Log.w("ConversationsActivity", "No models available - using fallback mode")
            showModelErrorDialog("AI models not available. You can still save images without AI analysis.")
        } else {
            Log.d("ConversationsActivity", "Using globally loaded models: YOLO=${yolo != null}, CNN=${cnn != null}")
        }

        // Set click listener for adding new conversation
        addConversationButton.setOnClickListener {
            showCreateConversationDialog()
        }

        backButton.setOnClickListener {
            finish() // Return to the previous screen
        }

        hamburgerButton.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                drawerLayout.closeDrawer(GravityCompat.END)
            } else {
                drawerLayout.openDrawer(GravityCompat.END)
            }
        }

        // Handle initial message state
        if (uploadedImagesContainer.childCount > 0) {
            hideInstruction()
        }

        // Handle incoming images from MainActivity
        val incomingUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_IMAGE_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(EXTRA_IMAGE_URIS)
        }

        lifecycleScope.launch {
            handleIncomingImages(incomingUris)
        }

        intent.removeExtra(EXTRA_IMAGE_URIS)

        editTitleButton.setOnClickListener {
            if (conversationId == -1L) {
                Toast.makeText(this, "No conversation to edit yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val input = EditText(this).apply {
                setText(conversationTitle.text)
            }
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
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Load existing conversation if passed
        conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, conversationId)
        if (conversationId != -1L) {
            lifecycleScope.launch {
                try {
                    val conv = conversationDao.getConversationById(conversationId)
                    conv?.let {
                        runOnUiThread {
                            // Set conversation title
                            conversationTitle.text = it.name
                            uploadedImagesContainer.removeAllViews()
                            previewContainer.removeAllViews()
                            clearConversationCards() // Clear any existing cards when loading conversation
                        }

                        // Load and display all prompts linked to this conversation
                        val history = db.promptDao().getPromptsForConversation(conversationId)
                        runOnUiThread {
                            history.forEach { prompt ->
                                val uris = prompt.imageUris.map { uriStr -> Uri.parse(uriStr) }
                                addConversationCard(uris, prompt.diagnostic, prompt.timestamp)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(
                            this@ConversationsActivity,
                            "Error loading conversation details",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        // Refresh drawer list
        refreshConversationList()

        // Click → open conversation
        conversationListView.setOnItemClickListener { _, _, position, _ ->
            lifecycleScope.launch {
                val all = conversationDao.getAllConversations()
                val conv = all[position]
                conversationId = conv.id
                runOnUiThread {
                    conversationTitle.text = conv.name
                    drawerLayout.closeDrawer(GravityCompat.END)
                    uploadedImagesContainer.removeAllViews()
                    previewContainer.removeAllViews()
                    clearConversationCards() // Clear cards when switching conversations
                }
                val history = db.promptDao().getPromptsForConversation(conversationId)
                history.forEach { prompt ->
                    val uris = prompt.imageUris.map { Uri.parse(it) }
                    addConversationCard(uris, prompt.diagnostic, prompt.timestamp)
                }
            }
        }

        // Long press → show options dialog
        conversationListView.setOnItemLongClickListener { _, _, position, _ ->
            lifecycleScope.launch {
                val all = db.conversationDao().getAllConversations()
                val conv = all[position]

                runOnUiThread {
                    val options = arrayOf("Edit Name", "Delete Conversation")

                    AlertDialog.Builder(this@ConversationsActivity)
                        .setTitle("Manage Conversation")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> showEditDialog(conv)   // Edit
                                1 -> deleteConversation(conv) // Delete
                            }
                        }
                        .show()
                }
            }
            true
        }

        // Camera button → open system camera
        cameraButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            }
        }

        // Upload button → create conversation if needed
        uploadButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
                    openGallery()
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), READ_STORAGE_PERMISSION_REQUEST_CODE)
                }
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    openGallery()
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), READ_STORAGE_PERMISSION_REQUEST_CODE)
                }
            }
        }

        sendButton.setOnClickListener {
            if (uploadedUris.isEmpty()) {
                Toast.makeText(this, "Add at least one image", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val persisted = uploadedUris.mapNotNull { ensureLocalCopy(it) }
            if (persisted.isEmpty()) {
                Toast.makeText(this, "Could not persist images", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (areModelsAvailable()) {
                // Show loader and disable buttons
                showLoader()

                // Use AI processing (if at least one model available)
                lifecycleScope.launch {
                    processImagesWithAI(persisted)
                }
            } else {
                // Fallback to basic saving
                val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(Date())
                val diagnostic = "Images Saved - $timestamp\nProcessed Images: ${persisted.size}\nAI Analysis: Unavailable"

                lifecycleScope.launch {
                    db.promptDao().insertPrompt(
                        PromptEntity(
                            conversationId = conversationId,
                            imageUris = persisted.map { it.toString() },
                            diagnostic = diagnostic,
                            timestamp = timestamp
                        )
                    )

                    withContext(Dispatchers.Main) {
                        addConversationCard(persisted, diagnostic, timestamp)

                        // ADD THIS: Scroll to show the new card
                        scrollToBottom()

                        Toast.makeText(this@ConversationsActivity, "Images saved (AI unavailable)", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            uploadedUris.clear()
            previewContainer.removeAllViews()
        }

        // Initialize testing
        setupTesting()

        // Optional: Auto-test after delay for quick verification
        lifecycleScope.launch {
            delay(5000) // Wait 5 seconds for models to load
            Log.d("TEST_AUTO", "Models loaded - ready for testing")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    openCamera()
                } else {
                    Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            READ_STORAGE_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    openGallery()
                } else {
                    Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLoader() {
        runOnUiThread {
            loader.visibility = View.VISIBLE
            // Disable buttons during processing
            sendButton.isEnabled = false
            uploadButton.isEnabled = false
            cameraButton.isEnabled = false
        }
    }

    private fun hideLoader() {
        runOnUiThread {
            loader.visibility = View.GONE
            // Re-enable buttons after processing
            sendButton.isEnabled = true
            uploadButton.isEnabled = true
            cameraButton.isEnabled = true
        }
    }

    private suspend fun waitForModels(): Boolean {
        // Models are already loaded globally, just check if they're available
        return withContext(Dispatchers.IO) {
            // Give a brief moment for any initialization
            delay(100)
            ModelManager.areModelsAvailable()
        }
    }

    private fun updateUIForModelStatus() {
        val yoloAvailable = yolo != null
        val cnnAvailable = cnn != null

        Log.d("ModelStatus", "YOLO: $yoloAvailable, CNN: $cnnAvailable")
        if (!yoloAvailable || !cnnAvailable) {
            val message = buildString {
                append("AI Models Status:\n")
                if (!yoloAvailable) append("• Object detection unavailable\n")
                if (!cnnAvailable) append("• Plant analysis unavailable\n")
                append("\nYou can still save images without AI analysis.")
            }
            showModelErrorDialog(message)
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            Toast.makeText(this@ConversationsActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun handleIncomingImages(incomingUris: ArrayList<Uri>?) {
        if (conversationId == -1L && !incomingUris.isNullOrEmpty()) {
            try {
                Log.d("ConversationsActivity", "Processing ${incomingUris.size} incoming images")
                showLoader()

                // Create new conversation first
                val count = db.conversationDao().getConversationCount()
                val defaultName = "Plantation#${count + 1}"
                val newId = db.conversationDao().insertConversation(ConversationEntity(name = defaultName))
                conversationId = newId

                runOnUiThread {
                    conversationTitle.text = defaultName
                    Toast.makeText(this@ConversationsActivity, "Processing images...", Toast.LENGTH_SHORT).show()
                }
                refreshConversationList()

                // Use the globally loaded models - no waiting needed
                val persistedUris: List<Uri> = incomingUris.mapNotNull {
                    ensureLocalCopy(it)
                }

                Log.d("ConversationsActivity", "Successfully persisted ${persistedUris.size} uris")

                if (persistedUris.isNotEmpty()) {
                    if (areModelsAvailable()) {
                        processImagesWithAI(persistedUris)
                    } else {
                        hideLoader()
                        fallbackSaveImages(persistedUris, "AI models not available")
                    }
                } else {
                    throw IllegalStateException("No images could be persisted")
                }

            } catch (e: Exception) {
                Log.e("ConversationsActivity", "Failed to process incoming images", e)
                hideLoader()
                fallbackSaveImages(incomingUris, e.message ?: "Unknown error")
            }
        }
    }

    // Fixed function to update arrow position based on AI-predicted stage
    fun setPlantStage(stage: Int, totalStages: Int = 4) { // Changed to 4 stages
        runOnUiThread {
            val gradientView = findViewById<View>(R.id.cardStageGradient)
            val arrow = findViewById<ImageView>(R.id.stageArrow)

            // Check if views are available
            if (gradientView == null || arrow == null) {
                Log.w("setPlantStage", "Stage indicator views not available yet")
                return@runOnUiThread
            }

            // Ensure stage is within bounds (1-4)
            val clampedStage = stage.coerceIn(1, totalStages)

            // Wait until the layout is drawn to get width
            gradientView.post {
                // Check again in case views became null
                if (gradientView.width == 0 || arrow.width == 0) {
                    Log.w("setPlantStage", "Views not measured yet, skipping stage update")
                    return@post
                }

                val gradientWidth = gradientView.width.toFloat()
                val arrowWidth = arrow.width.toFloat()

                // Calculate arrow X position for 4 stages
                val positionX = (clampedStage.toFloat() / totalStages) * gradientWidth - (arrowWidth / 2)

                // Move arrow
                arrow.translationX = positionX
                Log.d("setPlantStage", "Stage set to $clampedStage, position: $positionX")
            }
        }
    }

    // Clear conversation cards
    fun clearConversationCards() {
        uploadedImagesContainer.removeAllViews()
        // Show instruction when there are no cards
        if (uploadedImagesContainer.childCount == 0) {
            showInstruction()
        }
    }

    private fun hideInstruction() {
        instructionText.visibility = View.GONE
    }

    private fun showInstruction() {
        instructionText.visibility = View.VISIBLE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) return

        when (requestCode) {
            PICK_IMAGES_REQUEST -> {
                val clipData = data?.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        val uri = clipData.getItemAt(i).uri
                        if (!uploadedUris.contains(uri)) {
                            addPreviewImage(uri)
                        }
                    }
                } else {
                    data?.data?.let { uri ->
                        if (!uploadedUris.contains(uri)) {
                            addPreviewImage(uri)
                        }
                    }
                }
            }

            CAMERA_REQUEST_CODE -> {
                cameraImageUri?.let { uri ->
                    if (!uploadedUris.contains(uri)) {
                        addPreviewImage(uri)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun showCreateConversationDialog() {
        val input = EditText(this).apply {
            hint = "Enter conversation name"
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Create New Conversation")
            .setMessage("Enter a name for your new conversation")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val conversationName = input.text.toString().trim()
                if (conversationName.isNotBlank()) {
                    createNewConversation(conversationName)
                } else {
                    Toast.makeText(this, "Please enter a conversation name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createNewConversation(name: String) {
        lifecycleScope.launch {
            val newId = db.conversationDao().insertConversation(ConversationEntity(name = name))
            conversationId = newId

            runOnUiThread {
                // Update UI
                conversationTitle.text = name
                drawerLayout.closeDrawer(GravityCompat.END)
                refreshConversationList()

                // Clear any existing content
                uploadedImagesContainer.removeAllViews()
                previewContainer.removeAllViews()
                clearConversationCards()

                Toast.makeText(this@ConversationsActivity, "New conversation created", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openCamera() {
        val photoFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "photo_${System.currentTimeMillis()}.jpg")
        cameraImageUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", photoFile)

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Check if there's an app to handle this intent
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, CAMERA_REQUEST_CODE)
        } else {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        lifecycleScope.launch {
            if (conversationId == -1L) {
                val count = db.conversationDao().getConversationCount()
                val defaultName = "Plantation#${count + 1}"
                val newId = db.conversationDao().insertConversation(
                    ConversationEntity(name = defaultName)
                )
                conversationId = newId
                runOnUiThread { conversationTitle.text = defaultName }
                refreshConversationList()
            }
        }

        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        // Check if there's an app to handle this intent
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(
                Intent.createChooser(intent, "Select Pictures"),
                PICK_IMAGES_REQUEST
            )
        } else {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditDialog(conv: ConversationEntity) {
        val input = EditText(this).apply {
            setText(conv.name)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Conversation Name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        db.conversationDao().updateConversationName(conv.id, newName)
                        if (conv.id == conversationId) {
                            runOnUiThread { conversationTitle.text = newName }
                        }
                        refreshConversationList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addPreviewImage(uri: Uri) {
        uploadedUris.add(uri)  // This ensures the captured image is saved
        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(180, 180).apply { setMargins(8, 8, 8, 8) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageURI(uri)
        }
        previewContainer.addView(img)
    }

    private fun refreshConversationList() {
        lifecycleScope.launch {
            val all = db.conversationDao().getAllConversations()
            val names = all.map { it.name }
            runOnUiThread {
                val adapter = ArrayAdapter(
                    this@ConversationsActivity,
                    android.R.layout.simple_list_item_1,
                    names
                )
                conversationListView.adapter = adapter
            }
        }
    }

    private fun ensureLocalCopy(uri: Uri): Uri? {
        if (uri.scheme == "file") return uri
        return try {
            val imagesDir = File(filesDir, "images").apply { if (!exists()) mkdirs() }
            val outFile = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            Uri.fromFile(outFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun addConversationCard(images: List<Uri>, diagnostic: String, timestamp: String, weekNumber: Int? = null) {
        val card = layoutInflater.inflate(R.layout.item_conversation_card, uploadedImagesContainer, false)
        val imageRow = card.findViewById<LinearLayout>(R.id.cardImageRow)
        val cardDiagnostic = card.findViewById<TextView>(R.id.cardDiagnostic)
        val cardTimestamp = card.findViewById<TextView>(R.id.cardTimestamp)
        val stageArrow = card.findViewById<ImageView>(R.id.stageArrow)
        val stageGradient = card.findViewById<View>(R.id.cardStageGradient)

        // Add images
        images.forEach { uri ->
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(400, 400).apply { setMargins(8, 8, 8, 8) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(uri)
                setOnClickListener { showImageModal(uri) }
                isClickable = true
            }
            imageRow.addView(img)
        }

        cardDiagnostic.text = diagnostic
        cardTimestamp.text = timestamp

        // Position arrow based on week number if available
        if (weekNumber != null) {
            Log.d("WeekArrow", "Setting arrow position for week: $weekNumber")

            // Wait for layout to be measured
            stageGradient.post {
                if (stageGradient.width > 0 && stageArrow.width > 0) {
                    // Calculate position based on week (1-16) mapped to gradient width
                    val gradientWidth = stageGradient.width.toFloat()
                    val arrowWidth = stageArrow.width.toFloat()

                    // Map week 1-16 to position 0% to 100%
                    // Week 1 = leftmost, Week 16 = rightmost
                    val positionPercentage = (weekNumber - 1) / 15f // 0.0 to 1.0

                    // Calculate X position
                    val positionX = positionPercentage * gradientWidth - (arrowWidth / 2)

                    // Clamp position to stay within bounds
                    val clampedPosition = positionX.coerceIn(0f, gradientWidth - arrowWidth)

                    stageArrow.translationX = clampedPosition

                    Log.d("WeekArrow", "Week $weekNumber -> Gradient width: $gradientWidth, Position: $clampedPosition")
                } else {
                    Log.w("WeekArrow", "Views not measured yet, skipping arrow positioning")
                }
            }
        } else {
            Log.d("WeekArrow", "No week number available for arrow positioning")
        }

        uploadedImagesContainer.addView(card)

        // Hide instruction when there are cards
        if (uploadedImagesContainer.childCount > 0) {
            hideInstruction()
        }

        // Scroll to bottom after adding card
        scrollToBottom()
    }

    private fun showImageModal(uri: Uri) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.image_modal_layout)

        // Set transparent background for the dialog window
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val modalImage = dialog.findViewById<ImageView>(R.id.modalImageView)

        // Remove any white backgrounds
        modalImage?.setBackgroundColor(Color.TRANSPARENT)

        // Load the image
        modalImage?.setImageURI(uri)
        modalImage?.scaleType = ImageView.ScaleType.FIT_CENTER
        modalImage?.adjustViewBounds = true

        // Close on image click
        modalImage?.setOnClickListener {
            dialog.dismiss()
        }

        // Close on back button
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dialog.dismiss()
                true
            } else {
                false
            }
        }

        dialog.show()
    }

    // Convert URI → Bitmap with ARGB_8888 format
    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            }

            // Ensure bitmap is in ARGB_8888 format for YOLO
            bitmap?.let { originalBitmap ->
                if (originalBitmap.config != Bitmap.Config.ARGB_8888) {
                    val argbBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    originalBitmap.recycle() // Free memory from original bitmap
                    argbBitmap
                } else {
                    originalBitmap
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun makeBold(text: String): String {
        return "<b>$text</b>"
    }

    private suspend fun processImagesWithAI(persistedUris: List<Uri>) {
        showLoader()

        withContext(Dispatchers.Main) {
            Toast.makeText(this@ConversationsActivity, "Starting AI analysis...", Toast.LENGTH_SHORT).show()
        }

        if (!waitForModels()) {
            Log.w("AI Processing", "Models not available after waiting, using fallback")
            hideLoader()
            fallbackSaveImages(persistedUris, "AI models not ready")
            return
        }

        val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(Date())
        val fullReport = StringBuilder(
            "                    ────────────────────────\n\n"
        )

        var totalDetections = 0
        var averageStage = 0f
        var processedCount = 0
        var plantDetectedInAnyImage = false
        var weekRangeCounts = mutableMapOf<String, Int>()
        var maturityCounts = mutableMapOf<String, Int>()
        var healthCounts = mutableMapOf<String, Int>()
        var variantCounts = mutableMapOf<String, Int>()

        // NEW: Store actual week numbers for arrow positioning
        val weekNumbers = mutableListOf<Int>()
        val weekRangesFromAI = mutableListOf<String>()

        for ((index, uri) in persistedUris.withIndex()) {
            var bitmap: Bitmap? = null
            var usedCrop: Bitmap? = null

            try {
                Log.d("AI Processing", "Processing image ${index + 1}/${persistedUris.size}: $uri")

                bitmap = loadBitmapFromUri(uri)
                if (bitmap == null) {
                    fullReport.append("❌ Image ${index + 1}: Could not load image\n\n")
                    continue
                }

                fullReport.append("   📸 Image ${index + 1}\n")
                Log.d("AI Processing", "Bitmap loaded: ${bitmap.width}x${bitmap.height}")

                var detection: YoloDetector.Detection? = null
                var plantDetected = false

                // YOLO Detection with new model
                if (yolo != null) {
                    try {
                        val detections = withContext(Dispatchers.Default) {
                            yolo?.detect(bitmap) ?: emptyList()
                        }

                        if (detections.isNotEmpty()) {

                            // Pick highest-confidence detection
                            val detection = detections.maxByOrNull { it.score }

                            plantDetected = true
                            plantDetectedInAnyImage = true
                            totalDetections++

                            fullReport.append(
                                "        • 🔍 Object Detection: ${detection!!.label} " +
                                        "(${ "%.1f".format(detection.score * 100) }% confidence)\n"
                            )

                            Log.d(
                                "YOLO Detection",
                                "Found: ${detection.label} with ${detection.score}"
                            )

                            // --- Crop with bounds checking ---
                            val left = detection.box.left.toInt().coerceIn(0, bitmap.width - 1)
                            val top = detection.box.top.toInt().coerceIn(0, bitmap.height - 1)
                            val right = detection.box.right.toInt().coerceIn(left + 1, bitmap.width)
                            val bottom = detection.box.bottom.toInt().coerceIn(top + 1, bitmap.height)

                            val width = right - left
                            val height = bottom - top

                            if (width > 10 && height > 10) {
                                usedCrop = try {
                                    Bitmap.createBitmap(bitmap, left, top, width, height)
                                } catch (e: Exception) {
                                    Log.e("Crop", "Crop failed: ${e.message}")
                                    null
                                }
                            }

                        } else {
                            fullReport.append("        • 🔍 Object Detection: ❌ No sweet potato plant detected\n")
                            Log.d("YOLO Detection", "No detections found")
                            fullReport.append("                • 🌿 Plant Analysis: ⏭️ Skipped - no plant detected\n\n")
                            continue
                        }

                    } catch (e: Exception) {
                        Log.e("YOLO", "YOLO detection failed", e)
                        fullReport.append("        • 🔍 Object Detection: Failed - ${e.message}\n")
                        fullReport.append("                • 🌿 Plant Analysis: ⏭️ Skipped - detection failed\n\n")
                        continue
                    }

                } else {
                    fullReport.append("        • 🔍 Object Detection: Unavailable\n")
                }


                // CNN Analysis with new model
                if (cnn != null && (plantDetected || yolo == null)) {
                    try {
                        val analysisBitmap = usedCrop ?: bitmap
                        val result = withContext(Dispatchers.Default) {
                            cnn?.classify(analysisBitmap)
                        }

                        if (result != null) {
                            fullReport.append("                • 🌿 Plant Analysis:\n")

                            // Get predictions including week
                            val maturity = formatLabel(result.maturity)
                            val health = formatLabel(result.health)
                            val variant = formatLabel(result.variant)
                            val weekNumber = result.week  // Week number (1-16)
                            val weekRange = result.weekRange  // Week range (Early 1-3, etc.)

                            fullReport.append("                          - Maturity: $maturity\n")
                            fullReport.append("                          - Health: $health\n")
                            fullReport.append("                          - Variant: $variant\n")
                            fullReport.append("                          - Week: ($weekRange)\n")  // Added week info
                            fullReport.append("                          - Confidence: ${"%.1f".format(result.confidence * 100)}%\n")

                            // Count predictions for summary
                            weekRangeCounts[weekRange] = weekRangeCounts.getOrDefault(weekRange, 0) + 1
                            maturityCounts[maturity] = maturityCounts.getOrDefault(maturity, 0) + 1
                            healthCounts[health] = healthCounts.getOrDefault(health, 0) + 1
                            variantCounts[variant] = variantCounts.getOrDefault(variant, 0) + 1

                            // NEW: Store week numbers and ranges for arrow positioning
                            weekNumbers.add(weekNumber)
                            weekRangesFromAI.add(weekRange)

                            // Map to stage for UI (1-4 stages) using week number
                            val stageInt = getStageFromWeekNumber(weekNumber)
                            averageStage += stageInt

                            processedCount++

                            // Debug log
                            Log.d("WeekPrediction", "Predicted week: $weekNumber, range: $weekRange, stage: $stageInt")
                        } else {
                            fullReport.append("   • 🌿 Plant Analysis: Failed or inconclusive\n")
                            // Don't count this as processed if CNN failed
                        }
                    } catch (e: Exception) {
                        Log.e("CNN", "CNN analysis failed", e)
                        fullReport.append("   • 🌿 Plant Analysis: Failed - ${e.message}\n")
                        // Don't count this as processed if CNN failed
                    }
                } else if (!plantDetected && yolo != null) {
                    fullReport.append("   • 🌿 Plant Analysis: ⏭️ Skipped - no plant detected\n")
                } else {
                    fullReport.append("   • 🌿 Plant Analysis: Unavailable\n")
                }

                fullReport.append("\n")

            } catch (e: Exception) {
                Log.e("AI Processing", "Error processing image $uri", e)
                fullReport.append("❌ Image ${index + 1}: Processing error - ${e.message}\n\n")
            } finally {
                bitmap?.recycle()
                usedCrop?.recycle()
            }
        }

        // CRITICAL: Check if any plants were detected at all
        if (!plantDetectedInAnyImage && yolo != null) {
            // No plants detected in any image - show user-friendly message and stop
            val noPlantMessage = """
            🌱 Plant Diagnostic Report - $timestamp
            
            ❌ No sweet potato plants detected
            
            We couldn't identify any sweet potato plants in your images.
            
            📝 Please try again with images that clearly show:
            • Whole sweet potato plants or leaves
            • Good lighting and clear focus
            • Avoid blurry or dark images
            • Make sure the plant is the main subject
            
            🔍 Processed ${persistedUris.size} image(s) - no plants found
        """.trimIndent()

            // Save the no-plant result to database
            db.promptDao().insertPrompt(
                PromptEntity(
                    conversationId = conversationId,
                    imageUris = persistedUris.map { it.toString() },
                    diagnostic = noPlantMessage,
                    timestamp = timestamp
                )
            )

            withContext(Dispatchers.Main) {
                addConversationCard(persistedUris, noPlantMessage, timestamp)
                Toast.makeText(
                    this@ConversationsActivity,
                    "❌ No plants detected - please upload images with sweet potato plants",
                    Toast.LENGTH_LONG
                ).show()
                hideLoader()
            }
            return // Stop further processing
        }

        // Generate summary based on counts
        val header = StringBuilder()
        header.append("🌱 SUMMARY - $timestamp\n\n")

        if (plantDetectedInAnyImage) {
            val imageWord = if (persistedUris.size > 1) "images" else "image"
            val detectionWord = if (totalDetections > 1) "detections" else "detection"


            header.append("✅ Plant Detected in $totalDetections $detectionWord out of ${persistedUris.size} $imageWord\n")
            if (processedCount > 0) {
                val avgStage = (averageStage / processedCount).toInt().coerceIn(1, 4)

                // Find most common predictions
                val mostCommonWeek = weekRangeCounts.maxByOrNull { it.value }?.key ?: "Unknown"
                val mostCommonMaturity = maturityCounts.maxByOrNull { it.value }?.key ?: "Unknown"
                val mostCommonHealth = healthCounts.maxByOrNull { it.value }?.key ?: "Unknown"
                val mostCommonVariant = variantCounts.maxByOrNull { it.value }?.key ?: "Unknown"

                // NEW: Calculate average week number for arrow positioning
                val averageWeekNumber = if (weekNumbers.isNotEmpty()) {
                    weekNumbers.average().toInt().coerceIn(1, 16)
                } else null

                // NEW: Only show "Most Common Predictions" section when there are multiple images
                // OR when there are multiple valid predictions from a single image
                if (persistedUris.size > 1 || (weekRangeCounts.size > 1 || maturityCounts.size > 1 ||
                            healthCounts.size > 1 || variantCounts.size > 1)) {
                    header.append("\n   📊 Most Common Predictions:\n")
                    header.append("        • Growth Stage: $mostCommonWeek\n")
                    header.append("        • Maturity: $mostCommonMaturity\n")
                    header.append("        • Health: $mostCommonHealth\n")
                    header.append("        • Variant: $mostCommonVariant\n")

                    // NEW: Add week number information to summary
                    if (averageWeekNumber != null) {
                        header.append("        • Average Week Number: $averageWeekNumber\n")
                    }
                } else {
                    // For single image with consistent predictions, just show the primary prediction
                    if (mostCommonWeek != "Unknown") {
                        header.append("\n📊 Primary Prediction:\n")
                        header.append("        • Growth Stage: $mostCommonWeek\n")
                        if (averageWeekNumber != null) {
                            header.append("        • Week Number: $averageWeekNumber\n")
                        }
                    }
                }

                // Update UI with average stage
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        setPlantStage(avgStage, totalStages = 4)
                    }
                }
            }
        } else if (yolo == null) {
            header.append("⚠️ Plant detection unavailable - using all images for analysis\n")
        }

        header.append("\n")

        // Insert summary at beginning
        fullReport.insert(0, header.toString())
        val diagnostic = fullReport.toString()

        // Save to database
        db.promptDao().insertPrompt(
            PromptEntity(
                conversationId = conversationId,
                imageUris = persistedUris.map { it.toString() },
                diagnostic = diagnostic,
                timestamp = timestamp
            )
        )

        withContext(Dispatchers.Main) {
            // NEW: Calculate average week number for arrow positioning
            val averageWeekForArrow = if (weekNumbers.isNotEmpty()) {
                weekNumbers.average().toInt().coerceIn(1, 16)
            } else {
                // If no week numbers, estimate from most common week range
                val mostCommonRange = weekRangeCounts.maxByOrNull { it.value }?.key
                when {
                    mostCommonRange?.contains("Early") == true -> 2  // Mid Early
                    mostCommonRange?.contains("Middle") == true -> 6  // Mid Middle
                    mostCommonRange?.contains("Ready") == true -> 10  // Mid Ready
                    mostCommonRange?.contains("Mature") == true -> 14  // Mid Mature
                    else -> null
                }
            }

            // Pass week number to the card for arrow positioning
            addConversationCard(persistedUris, diagnostic, timestamp, averageWeekForArrow)

            // Scroll to show the new card
            scrollToBottom()

            val message = when {
                processedCount > 0 -> {
                    val weekInfo = if (weekRangeCounts.isNotEmpty()) {
                        val mostCommon = weekRangeCounts.maxByOrNull { it.value }?.key ?: ""
                        val weekNumInfo = if (averageWeekForArrow != null) " (Week $averageWeekForArrow)" else ""
                        " - $mostCommon$weekNumInfo"
                    } else ""
                    "✅ Analysis complete! Processed $processedCount images$weekInfo"
                }
                plantDetectedInAnyImage && yolo != null -> "⚠️ Plants detected but analysis had issues"
                else -> "❌ No plants detected in any images"
            }

            Toast.makeText(this@ConversationsActivity, message, Toast.LENGTH_LONG).show()
            hideLoader()

            // Debug: Show what was actually processed
            Log.d("AI_DEBUG", "Final report:\n$diagnostic")
            Log.d("WeekData", "Week numbers collected: $weekNumbers, Average: $averageWeekForArrow")
        }
    }

    private fun getStageFromWeekNumber(week: Int): Int {
        return when (week) {
            in 1..3 -> 1   // Early
            in 4..8 -> 2   // Middle
            in 9..12 -> 3  // Ready
            in 13..16 -> 4 // Mature
            else -> 1      // Default to Early if unexpected
        }
    }

    private suspend fun fallbackSaveImages(uris: List<Uri>, errorMessage: String) {
        try {
            val persistedUris = uris.mapNotNull { ensureLocalCopy(it) }
            if (persistedUris.isNotEmpty()) {
                val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(Date())
                val diagnostic = "Images Received - $timestamp\n" +
                        "Processed ${persistedUris.size} image(s)\n" +
                        "AI Analysis Skipped: $errorMessage"

                db.promptDao().insertPrompt(
                    PromptEntity(
                        conversationId = conversationId,
                        imageUris = persistedUris.map { it.toString() },
                        diagnostic = diagnostic,
                        timestamp = timestamp
                    )
                )

                withContext(Dispatchers.Main) {
                    addConversationCard(persistedUris, diagnostic, timestamp)
                    Toast.makeText(
                        this@ConversationsActivity,
                        "Images saved (AI analysis skipped)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (e: Exception) {
            Log.e("Fallback", "Fallback save also failed", e)
            withContext(Dispatchers.Main) {
                // ADD THIS: Scroll to show the new card
                scrollToBottom()

                Toast.makeText(
                    this@ConversationsActivity,
                    "Failed to save images: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun areModelsAvailable(): Boolean {
        // Just check the global ModelManager
        return ModelManager.areModelsAvailable()
    }

    // Helper
    private fun formatLabel(label: String): String {
        return label.replace("-", " ")
            .replace("_", " ")
            .replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault())
                else it.toString()
            }
    }

    private fun getWeekRangeForMaturity(maturity: String): String {
        val lowerMaturity = maturity.lowercase(Locale.getDefault())
        return when {
            lowerMaturity.contains("premature") || lowerMaturity.contains("early") ->
                "Early (Weeks 1-3)"
            lowerMaturity.contains("medium") || lowerMaturity.contains("near") ->
                "Middle (Weeks 4-8)"
            lowerMaturity.contains("mature") || lowerMaturity.contains("ready") ->
                "Ready (Weeks 9-12)"
            lowerMaturity.contains("late") ->
                "Mature (Weeks 13-16)"
            else -> "Early (Weeks 1-3)"
        }
    }

    private fun getStageFromWeekRange(weekRange: String): Int {
        return when {
            weekRange.contains("Early") -> 1
            weekRange.contains("Middle") -> 2
            weekRange.contains("Ready") -> 3
            weekRange.contains("Mature") -> 4
            else -> 1
        }
    }

    private fun showModelErrorDialog(error: String) {
        AlertDialog.Builder(this)
            .setTitle("AI Models Not Available")
            .setMessage("The AI analysis features are currently unavailable: $error\n\nYou can still save images without AI analysis.")
            .setPositiveButton("OK", null)
            .show()
    }

    // Testing section
    private fun setupTesting() {
        // Add a hidden test trigger - triple tap on the title
        conversationTitle.setOnClickListener {
            testClickCount++
            if (testClickCount >= 3) {
                testClickCount = 0
                showTestMenu()
            }
        }
    }

    private var testClickCount = 0

    private fun showTestMenu() {
        val testOptions = arrayOf(
            "Quick Model Test",
            "Full Integration Test",
            "Model Status Check",
            "Test with Real Image",
            "Comprehensive Test"  // Add this
        )

        AlertDialog.Builder(this)
            .setTitle("🧪 Model Testing")
            .setItems(testOptions) { _, which ->
                when (which) {
                    0 -> runQuickTest()
                    1 -> runFullIntegrationTest()
                    2 -> checkModelStatus()
                    3 -> lifecycleScope.launch { testWithRealImage() }
                    4 -> runComprehensiveTest()  // Add this
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runQuickTest() {
        lifecycleScope.launch {
            showTestProgress("Running Quick Test...")

            try {
                // Wait for models to load
                if (!waitForModels()) {
                    showTestResult("❌ Models not loaded")
                    return@launch
                }

                // Create test bitmap
                val testBitmap = createTestBitmap()

                // Test basic prediction
                val result = cnn?.classify(testBitmap)

                if (result != null) {
                    val weekRange = getWeekRangeForMaturity(result.maturity)
                    val testResult = """
                    ✅ QUICK TEST PASSED
                    
                    Maturity: ${result.maturity}
                    Health: ${result.health}  
                    Variant: ${result.variant}
                    Week Range: $weekRange
                    Confidence: ${"%.1f".format(result.confidence * 100)}%
                    
                    All predictions received successfully!
                """.trimIndent()
                    showTestResult(testResult)

                    // Log for debugging
                    Log.d("TEST_QUICK", "Maturity: ${result.maturity}")
                    Log.d("TEST_QUICK", "Health: ${result.health}")
                    Log.d("TEST_QUICK", "Variant: ${result.variant}")
                    Log.d("TEST_QUICK", "Confidence: ${result.confidence}")

                } else {
                    showTestResult("❌ Prediction returned null")
                }

                testBitmap.recycle()

            } catch (e: Exception) {
                showTestResult("❌ Test Failed: ${e.message}")
                Log.e("TEST_QUICK", "Error: ${e.message}", e)
            }
        }
    }

    private fun runFullIntegrationTest() {
        lifecycleScope.launch {
            showTestProgress("Running Full Integration Test...")

            try {
                if (!waitForModels()) {
                    showTestResult("❌ Models not loaded")
                    return@launch
                }

                val testBitmap = createTestBitmap()
                val testResults = StringBuilder()
                testResults.append("🧪 FULL INTEGRATION TEST\n\n")

                // Test 1: Basic prediction
                testResults.append("1. Basic Prediction:\n")
                val basicResult = cnn?.classify(testBitmap)
                if (basicResult != null) {
                    val weekRange = getWeekRangeForMaturity(basicResult.maturity)
                    testResults.append("   ✅ SUCCESS\n")
                    testResults.append("   - Maturity: ${basicResult.maturity}\n")
                    testResults.append("   - Health: ${basicResult.health}\n")
                    testResults.append("   - Variant: ${basicResult.variant}\n")
                    testResults.append("   - Week Range: $weekRange\n")
                    testResults.append("   - Confidence: ${"%.1f".format(basicResult.confidence * 100)}%\n")
                } else {
                    testResults.append("   ❌ FAILED\n")
                }

                testBitmap.recycle()
                showTestResult(testResults.toString())

            } catch (e: Exception) {
                showTestResult("❌ Integration Test Failed: ${e.message}")
                Log.e("TEST_FULL", "Error: ${e.message}", e)
            }
        }
    }

    private fun checkModelStatus() {
        val status = """
        🔍 MODEL STATUS CHECK
        
        CNN Model: ${if (cnn != null) "✅ LOADED" else "❌ NOT LOADED"}
        YOLO Model: ${if (yolo != null) "✅ LOADED" else "❌ NOT LOADED"}
        Models Flag: ${ModelManager.areModelsAvailable()}
        
        ${if (cnn != null && yolo != null) "🎉 All models ready!" else "⚠️ Some models missing"}
    """.trimIndent()

        showTestResult(status)
    }

    private suspend fun testWithRealImage() {
        showTestProgress("Testing with real image...")

        try {
            // Try to use the first uploaded image if available
            if (uploadedUris.isNotEmpty()) {
                val testUri = uploadedUris.first()
                val testBitmap = loadBitmapFromUri(testUri)

                if (testBitmap != null) {
                    val result = cnn?.classify(testBitmap)
                    val weekRange = result?.let { getWeekRangeForMaturity(it.maturity) } ?: "N/A"

                    val testResult = """
                    📸 REAL IMAGE TEST
                    
                    Image: ${testUri.lastPathSegment}
                    Maturity: ${result?.maturity ?: "N/A"}
                    Health: ${result?.health ?: "N/A"}
                    Variant: ${result?.variant ?: "N/A"}
                    Week Range: $weekRange
                    Confidence: ${result?.confidence?.let { "%.1f".format(it * 100) } ?: "N/A"}%
                    
                    ${if (result != null) "✅ Analysis completed" else "❌ Analysis failed"}
                """.trimIndent()

                    showTestResult(testResult)
                    testBitmap.recycle()
                } else {
                    showTestResult("❌ Could not load image from URI")
                }
            } else {
                showTestResult("ℹ️ No images available for testing\n\nUpload an image first, then test again.")
            }
        } catch (e: Exception) {
            showTestResult("❌ Real image test failed: ${e.message}")
        }
    }

    // Helper functions
    private fun createTestBitmap(): Bitmap {
        return Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply {
            // Create a simple green gradient for testing
            for (x in 0 until width) {
                for (y in 0 until height) {
                    val green = (150 + (x * 100 / width)).coerceIn(0, 255)
                    val pixel = Color.argb(255, 50, green, 50)
                    setPixel(x, y, pixel)
                }
            }
        }
    }

    private fun showTestProgress(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTestResult(message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Test Results")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun runComprehensiveTest() {
        lifecycleScope.launch {
            showTestProgress("Running Comprehensive Test...")

            val testResults = StringBuilder()
            testResults.append("🧪 COMPREHENSIVE MODEL TEST\n\n")

            try {
                // Test 1: Model Loading
                testResults.append("1. MODEL LOADING:\n")
                if (!waitForModels()) {
                    testResults.append("   ❌ Models failed to load\n")
                    showTestResult(testResults.toString())
                    return@launch
                }
                testResults.append("   ✅ Models loaded successfully\n")
                testResults.append("   - YOLO: ${yolo != null}\n")
                testResults.append("   - CNN: ${cnn != null}\n\n")

                // Test 2: YOLO Detection
                testResults.append("2. YOLO DETECTION TEST:\n")
                if (yolo != null) {
                    try {
                        val testBitmap = createTestBitmap()

                        val detections = withContext(Dispatchers.Default) {
                            yolo?.detect(testBitmap) ?: emptyList()
                        }

                        if (detections.isNotEmpty()) {
                            // Get highest-confidence detection
                            val best = detections.maxByOrNull { it.score }!!

                            testResults.append(
                                "   ✅ YOLO working - detected: ${best.label} " +
                                        "(${ "%.1f".format(best.score * 100) }%)\n"
                            )

                        } else {
                            testResults.append("   ⚠️ YOLO working but no detection\n")
                        }

                        testBitmap.recycle()

                    } catch (e: Exception) {
                        testResults.append("   ❌ YOLO test failed: ${e.message}\n")
                    }

                } else {
                    testResults.append("   ❌ YOLO not available\n")
                }


                // Test 3: CNN Classification
                testResults.append("\n3. CNN CLASSIFICATION TEST:\n")
                if (cnn != null) {
                    try {
                        val testBitmap = createTestBitmap()
                        val result = withContext(Dispatchers.Default) {
                            cnn?.classify(testBitmap)
                        }
                        if (result != null) {
                            val weekRange = getWeekRangeForMaturity(result.maturity)
                            testResults.append("   ✅ CNN working\n")
                            testResults.append("   - Maturity: ${result.maturity}\n")
                            testResults.append("   - Health: ${result.health}\n")
                            testResults.append("   - Variant: ${result.variant}\n")
                            testResults.append("   - Week Range: $weekRange\n")
                            testResults.append("   - Confidence: ${"%.1f".format(result.confidence * 100)}%\n")
                        } else {
                            testResults.append("   ❌ CNN returned null\n")
                        }
                        testBitmap.recycle()
                    } catch (e: Exception) {
                        testResults.append("   ❌ CNN test failed: ${e.message}\n")
                    }
                } else {
                    testResults.append("   ❌ CNN not available\n")
                }

                // Test 4: Asset Files
                testResults.append("\n4. ASSET FILES CHECK:\n")
                val assetsToCheck = listOf(
                    "ml/yolov8.tflite",
                    "ml/cnn_lstm_mobile.pt",
                    "ml/yolo_labels.json",
                    "ml/label_maps.json",
                    "ml/numeric_cols.json"
                )

                assetsToCheck.forEach { asset ->
                    try {
                        assets.open(asset).close()
                        testResults.append("   ✅ $asset\n")
                    } catch (e: Exception) {
                        testResults.append("   ❌ $asset - ${e.message}\n")
                    }
                }

            } catch (e: Exception) {
                testResults.append("\n❌ Comprehensive test failed: ${e.message}")
            }

            showTestResult(testResults.toString())
        }
    }

    private fun scrollToBottom() {
        runOnUiThread {
            // Give the layout a moment to update, then scroll
            uploadedImagesContainer.postDelayed({
                scrollContent.fullScroll(View.FOCUS_DOWN)
            }, 100)
        }
    }
}