package com.ai.growsight

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
import com.ai.growsight.data.AppDatabase
import com.ai.growsight.data.PromptEntity
import com.ai.growsight.data.ConversationEntity
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
import com.ai.growsight.ai.MaturityClassifier
import com.ai.growsight.ai.YoloDetector

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

    private val uploadedUris = mutableListOf<Uri>()
    private var cameraImageUri: Uri? = null
    private var conversationId: Long = -1L

    private lateinit var db: AppDatabase

    // Model variables - NOT lazy initialized
    private var yolo: YoloDetector? = null
    private var cnn: MaturityClassifier? = null
    private var modelsLoaded = false

    private var modelLoadingInProgress = false
    private val modelLoadCallbacks = mutableListOf<() -> Unit>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversations)

        // Initialize message container and instruction text
        messagesContainer = findViewById(R.id.messagesContainer)
        instructionText = findViewById(R.id.instructionText)

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

        // Load models asynchronously at startup
        loadModels()

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
        val incomingUris = intent.getParcelableArrayListExtra<Uri>(EXTRA_IMAGE_URIS)
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
                        Toast.makeText(this@ConversationsActivity, "Images saved (AI unavailable)", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            uploadedUris.clear()
            previewContainer.removeAllViews()
        }
    }

    private fun loadModels() {
        if (modelLoadingInProgress) {
            Log.d("ModelLoad", "Model loading already in progress")
            return
        }

        modelLoadingInProgress = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("ModelLoad", "Starting model loading...")

                // Load YOLO with better error handling
                yolo = try {
                    YoloDetector(this@ConversationsActivity).also {
                        Log.d("ModelLoad", "✓ YOLO loaded successfully")
                    }
                } catch (e: Exception) {
                    Log.e("ModelLoad", "✗ YOLO failed: ${e.message}")
                    null
                }

                // Load CNN
                cnn = try {
                    MaturityClassifier(this@ConversationsActivity).also {
                        Log.d("ModelLoad", "✓ CNN loaded successfully")
                    }
                } catch (e: Exception) {
                    Log.e("ModelLoad", "✗ CNN failed: ${e.message}")
                    null
                }

                modelsLoaded = true
                modelLoadingInProgress = false

                Log.d("ModelLoad", "Model loading finished. YOLO=${yolo != null}, CNN=${cnn != null}")

                withContext(Dispatchers.Main) {
                    updateUIForModelStatus()
                    modelLoadCallbacks.forEach { it.invoke() }
                    modelLoadCallbacks.clear()
                }
            } catch (e: Exception) {
                Log.e("ModelLoad", "Failed to load models: ${e.message}")
                modelLoadingInProgress = false
                withContext(Dispatchers.Main) {
                    showError("Failed to load AI models: ${e.message}")
                }
            }
        }
    }

    private suspend fun waitForModels(): Boolean {
        if (modelsLoaded) return true

        return withContext(Dispatchers.IO) {
            var waited = 0
            val maxWait = 100 // 10 seconds
            while (!modelsLoaded && waited < maxWait && !modelLoadingInProgress) {
                delay(100)
                waited++
            }
            modelsLoaded
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

                // Create new conversation
                val count = db.conversationDao().getConversationCount()
                val defaultName = "Plantation#${count + 1}"
                val newId = db.conversationDao().insertConversation(ConversationEntity(name = defaultName))
                conversationId = newId

                runOnUiThread {
                    conversationTitle.text = defaultName
                    Toast.makeText(this@ConversationsActivity, "Processing images...", Toast.LENGTH_SHORT).show()
                }
                refreshConversationList()

                // Wait for models to load if not ready
                if (yolo == null || cnn == null) {
                    Log.d("ModelWait", "Waiting for actual model instances to be ready (timeout 5s)...")
                    var waited = 0
                    while ((yolo == null || cnn == null) && waited < 50) { // 5 second timeout, 100ms intervals
                        delay(100)
                        waited++
                    }
                    Log.d("ModelWait", "Wait complete. YOLO=${yolo != null}, CNN=${cnn != null}")
                    if (yolo == null || cnn == null) {
                        Log.w("ModelWait", "One or more models still unavailable after timeout")
                    }
                }

                Log.d("AI Models", "YOLO available: ${yolo != null}, CNN available: ${cnn != null}")

                val persistedUris: List<Uri> = incomingUris.mapNotNull {
                    val localUri = ensureLocalCopy(it)
                    if (localUri == null) {
                        Log.e("FileCopy", "Failed to copy URI: $it")
                    }
                    localUri
                }

                Log.d("ConversationsActivity", "Successfully persisted ${persistedUris.size} uris")

                if (persistedUris.isNotEmpty()) {
                    if (areModelsAvailable()) {
                        processImagesWithAI(persistedUris)
                    } else {
                        fallbackSaveImages(persistedUris, "AI models not available")
                    }
                } else {
                    throw IllegalStateException("No images could be persisted")
                }

            } catch (e: Exception) {
                Log.e("ConversationsActivity", "Failed to process incoming images", e)
                fallbackSaveImages(incomingUris, e.message ?: "Unknown error")
            }
        }
    }

    // Fixed function to update arrow position based on AI-predicted stage
    fun setPlantStage(stage: Int, totalStages: Int = 3) {
        runOnUiThread {
            val gradientView = findViewById<View>(R.id.cardStageGradient)
            val arrow = findViewById<ImageView>(R.id.stageArrow)

            // Check if views are available
            if (gradientView == null || arrow == null) {
                Log.w("setPlantStage", "Stage indicator views not available yet")
                return@runOnUiThread
            }

            // Ensure stage is within bounds (1-3)
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

                // Calculate arrow X position for 3 stages
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
        try {
            yolo?.close()
        } catch (e: Exception) {
            Log.e("YOLO", "Error closing YOLO", e)
        }
        try {
            cnn?.close()
        } catch (e: Exception) {
            Log.e("CNN", "Error closing CNN", e)
        }
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

    private fun addConversationCard(images: List<Uri>, diagnostic: String, timestamp: String) {
        val card = layoutInflater.inflate(R.layout.item_conversation_card, uploadedImagesContainer, false)
        val imageRow = card.findViewById<LinearLayout>(R.id.cardImageRow)
        images.forEach { uri ->
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(400, 400).apply { setMargins(8, 8, 8, 8) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(uri)

                // Make image clickable
                setOnClickListener {
                    showImageModal(uri)
                }

                // Visual feedback
                isClickable = true
            }
            imageRow.addView(img)
        }
        card.findViewById<TextView>(R.id.cardDiagnostic).text = diagnostic
        card.findViewById<TextView>(R.id.cardTimestamp).text = timestamp
        uploadedImagesContainer.addView(card)

        // Hide instruction when there are cards
        if (uploadedImagesContainer.childCount > 0) {
            hideInstruction()
        }
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

    private suspend fun processImagesWithAI(persistedUris: List<Uri>) {
        // Show loading indicator
        withContext(Dispatchers.Main) {
            Toast.makeText(this@ConversationsActivity, "Starting AI analysis...", Toast.LENGTH_SHORT).show()
        }

        // Wait for models to be ready
        if (!waitForModels()) {
            Log.w("AI Processing", "Models not available after waiting, using fallback")
            fallbackSaveImages(persistedUris, "AI models not ready")
            return
        }

        val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(Date())
        val fullReport = StringBuilder("🌱 Plant Diagnostic Report - $timestamp\n\n")

        var totalDetections = 0
        var averageStage = 0f
        var processedCount = 0

        for ((index, uri) in persistedUris.withIndex()) {
            // Declare bitmap variable outside the try block so it's accessible in finally
            var bitmap: Bitmap? = null
            var usedCrop: Bitmap? = null

            try {
                Log.d("AI Processing", "Processing image ${index + 1}/${persistedUris.size}")

                // Update progress
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ConversationsActivity,
                        "Analyzing image ${index + 1}/${persistedUris.size}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                bitmap = loadBitmapFromUri(uri)
                if (bitmap == null) {
                    fullReport.append("❌ Image ${index + 1}: Could not load image\n\n")
                    continue
                }

                fullReport.append("📸 Image ${index + 1}\n")

                var detection: YoloDetector.Detection? = null

                // YOLO Detection
                if (yolo != null) {
                    try {
                        detection = withContext(Dispatchers.Default) {
                            yolo?.detect(bitmap)
                        }

                        if (detection != null) {
                            fullReport.append("   • 🔍 Object Detection: ${detection.label} (${"%.1f".format(detection.score * 100)}% confidence)\n")

                            // Log detection details
                            Log.d("YOLO Detection", "Found: ${detection.label} with ${detection.score} at ${detection.box}")

                            // Crop detected area with safety checks
                            val left = detection.box.left.toInt().coerceIn(0, bitmap.width - 1)
                            val top = detection.box.top.toInt().coerceIn(0, bitmap.height - 1)
                            val right = detection.box.right.toInt().coerceIn(left + 1, bitmap.width)
                            val bottom = detection.box.bottom.toInt().coerceIn(top + 1, bitmap.height)
                            val width = right - left
                            val height = bottom - top

                            if (width > 0 && height > 0) {
                                usedCrop = try {
                                    Bitmap.createBitmap(bitmap, left, top, width, height)
                                } catch (e: Exception) {
                                    Log.e("Crop", "Crop failed: ${e.message}")
                                    null
                                }
                            }
                        } else {
                            fullReport.append("   • 🔍 Object Detection: No plant detected\n")
                            Log.d("YOLO Detection", "No plant detected in image")
                        }
                    } catch (e: Exception) {
                        Log.e("YOLO", "YOLO detection failed", e)
                        fullReport.append("   • 🔍 Object Detection: Failed - ${e.message}\n")
                    }
                } else {
                    fullReport.append("   • 🔍 Object Detection: Unavailable\n")
                }

                // CNN Analysis
                if (cnn != null) {
                    val analysisBitmap = usedCrop ?: bitmap // Use crop if available, else original

                    try {
                        val result = withContext(Dispatchers.Default) {
                            cnn?.predict(analysisBitmap)
                        }

                        if (result != null && result.maturity != "error" && result.health != "error" && result.variant != "error") {
                            fullReport.append("   • 🌿 Plant Analysis:\n")
                            fullReport.append("     - Maturity: ${formatLabel(result.maturity)}\n")
                            fullReport.append("     - Health: ${formatLabel(result.health)}\n")
                            fullReport.append("     - Variant: ${formatLabel(result.variant)}\n")

                            // Map maturity to stage
                            val stageInt = when (result.maturity.lowercase(Locale.getDefault())) {
                                "premature", "early" -> 1
                                "near-harvest", "medium" -> 2
                                "harvest-ready", "mature", "late" -> 3
                                else -> 1
                            }
                            averageStage += stageInt
                            totalDetections++
                            processedCount++
                        } else {
                            fullReport.append("   • 🌿 Plant Analysis: Failed or inconclusive\n")
                        }
                    } catch (e: Exception) {
                        Log.e("CNN", "CNN analysis failed", e)
                        fullReport.append("   • 🌿 Plant Analysis: Failed - ${e.message}\n")
                    }
                } else {
                    fullReport.append("   • 🌿 Plant Analysis: Unavailable\n")
                }

                fullReport.append("\n")

            } catch (e: Exception) {
                Log.e("AI Processing", "Error processing image $uri", e)
                fullReport.append("❌ Image ${index + 1}: Processing error - ${e.message}\n\n")
            } finally {
                // Clean up bitmaps to avoid memory leaks
                bitmap?.recycle()
                usedCrop?.recycle()
            }
        }

        // Update plant stage if we have detections
        if (totalDetections > 0) {
            val avgStage = (averageStage / totalDetections).toInt().coerceIn(1, 3)
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    setPlantStage(avgStage, totalStages = 3)
                }
            }
        }

        // Final report summary
        if (processedCount > 0) {
            fullReport.insert(0, "✅ Successfully analyzed $processedCount/${persistedUris.size} images\n\n")
        } else {
            fullReport.insert(0, "⚠️ Could not analyze any images\n\n")
        }

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

        // Update UI
        withContext(Dispatchers.Main) {
            addConversationCard(persistedUris, diagnostic, timestamp)
            val message = if (processedCount > 0) {
                "Analysis complete! Processed $processedCount images"
            } else {
                "Analysis completed with limited results"
            }
            Toast.makeText(this@ConversationsActivity, message, Toast.LENGTH_LONG).show()
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
                Toast.makeText(
                    this@ConversationsActivity,
                    "Failed to save images: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Change requirement: allow processing if at least one model is available
    private fun areModelsAvailable(): Boolean {
        val available = (yolo != null || cnn != null)
        Log.d("ModelCheck", "areModelsAvailable -> $available (YOLO=${yolo != null}, CNN=${cnn != null}, modelsLoaded=$modelsLoaded)")
        return available
    }

    // Helper
    private fun formatLabel(label: String): String {
        return label.replace("-", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun showModelErrorDialog(error: String) {
        AlertDialog.Builder(this)
            .setTitle("AI Models Not Available")
            .setMessage("The AI analysis features are currently unavailable: $error\n\nYou can still save images without AI analysis.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun debugModelStatus() {
        val status = """
        Model Status:
        - YOLO Loaded: ${yolo != null}
        - CNN Loaded: ${cnn != null}
        - Models Loaded Flag: $modelsLoaded
        - Loading in Progress: $modelLoadingInProgress
    """.trimIndent()

        Log.d("ModelDebug", status)

        // Show in UI for debugging
        Toast.makeText(this, "YOLO: ${yolo != null}, CNN: ${cnn != null}", Toast.LENGTH_LONG).show()
    }

    private fun checkModelStatus() {
        lifecycleScope.launch {
            debugModelStatus()
        }
    }
}