package com.ai.growsight

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.ai.growsight.ConversationsActivity.Companion.EXTRA_CONVERSATION_ID
import com.ai.growsight.ConversationsActivity.Companion.EXTRA_IMAGE_URIS
import com.ai.growsight.ai.MaturityClassifier
import com.ai.growsight.ai.ModelManager
import com.ai.growsight.ai.ModelUpdateManager
import com.ai.growsight.ai.YoloDetector
import com.ai.growsight.data.AppDatabase
import com.ai.growsight.data.ConversationEntity
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class UploadActivity : AppCompatActivity(), ModelUpdateManager.UpdateListener {

    private lateinit var uploadButton: Button
    private lateinit var flipper: ViewFlipper
    private lateinit var db: AppDatabase
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var conversationListView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var conversationId: Long = -1L

    private var cameraImageUri: Uri? = null
    private var updateInProgress = false

    // Model instances - loaded once at startup
    var yoloDetector: YoloDetector? = null
    var maturityClassifier: MaturityClassifier? = null
    private var modelsLoaded = false
    private var modelLoadingInProgress = false
    private var modelErrorDialogShown = false

    private lateinit var updateProgressDialog: AlertDialog
    private lateinit var progressTextView: TextView

    private lateinit var modelStatusIndicator: ImageView
    private lateinit var modelStatusText: TextView

    // --- Gallery picker ---
    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris: List<Uri> ->
            if (uris.isEmpty()) {
                Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            val unsentUris = uris.filterNot { selectedUri ->
                ConversationsActivity.sentUris.contains(selectedUri.toString())
            }

            if (unsentUris.isEmpty()) {
                Toast.makeText(this, "All selected images were already sent", Toast.LENGTH_SHORT)
                    .show()
                return@registerForActivityResult
            }

            val intent = Intent(this, ConversationsActivity::class.java).apply {
                putParcelableArrayListExtra(EXTRA_IMAGE_URIS, ArrayList(unsentUris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("yolo_available", yoloDetector != null)
                putExtra("cnn_available", maturityClassifier != null)
            }
            startActivity(intent)
        }

    // --- Camera capture launcher ---
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
            if (success && cameraImageUri != null) {
                val intent = Intent(this, ConversationsActivity::class.java).apply {
                    putParcelableArrayListExtra(
                        EXTRA_IMAGE_URIS,
                        arrayListOf(cameraImageUri)
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra("yolo_available", yoloDetector != null)
                    putExtra("cnn_available", maturityClassifier != null)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Camera capture cancelled", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CRASH", "App crashed in thread: ${thread.name}", throwable)
            throwable.printStackTrace()
        }

        uploadButton = findViewById(R.id.uploadButton)
        flipper = findViewById(R.id.flipperContainer)
        drawerLayout = findViewById(R.id.drawerLayout)
        conversationListView = findViewById(R.id.conversationListView)
        val hamburgerButton = findViewById<ImageButton>(R.id.menuButton)

        modelStatusIndicator = findViewById(R.id.modelStatusIndicator)
        modelStatusText = findViewById(R.id.modelStatusText)

        runOnUiThread {
            modelStatusIndicator.setImageResource(R.drawable.ic_model_loading)
            modelStatusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.blue))
            modelStatusText.text = "Loading AI..."
            modelStatusText.setTextColor(ContextCompat.getColor(this, R.color.blue))
        }

        modelStatusIndicator.setOnClickListener {
            showModelStatusDialog()
        }

        initializeAppModelsAsync()

        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "prompts-db"
        ).fallbackToDestructiveMigration().build()

        flipper.inAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        flipper.outAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_out)
        flipper.flipInterval = 5000
        flipper.startFlipping()

        uploadButton.setOnClickListener {
            showImageSourceDialog()
        }

        hamburgerButton.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                drawerLayout.closeDrawer(GravityCompat.END)
            } else {
                refreshConversationList()
                drawerLayout.openDrawer(GravityCompat.END)
            }
        }

        setupAdapter()
        refreshConversationList()

        conversationListView.setOnItemClickListener { _, _, position, _ ->
            openConversation(position)
        }
        conversationListView.setOnItemLongClickListener { _, _, position, _ ->
            showConversationOptions(position)
            true
        }
    }

    private fun initializeAppModelsAsync() {
        if (modelLoadingInProgress) {
            Log.d("UploadActivity", "Model loading already in progress")
            return
        }

        modelLoadingInProgress = true
        modelErrorDialogShown = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("UploadActivity", "Starting model initialization...")

                updateInProgress = true
                val updated = ModelUpdateManager.checkForModelUpdates(
                    this@UploadActivity,
                    this@UploadActivity
                )
                updateInProgress = false

                Log.d("UploadActivity", "Initializing ModelManager...")
                ModelManager.initializeModels(this@UploadActivity)

                updateLocalModelReferences()

                modelsLoaded = ModelManager.areModelsAvailable()

                withContext(Dispatchers.Main) {
                    modelLoadingInProgress = false

                    val yoloAvailable = yoloDetector != null
                    val cnnAvailable = maturityClassifier != null

                    updateModelStatusIndicator()

                    if (yoloAvailable && cnnAvailable) {
                        Toast.makeText(
                            this@UploadActivity,
                            "✓ All AI models loaded",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    Log.d("UploadActivity", "Model initialization complete - YOLO: $yoloAvailable, CNN: $cnnAvailable")
                }
            } catch (e: Exception) {
                Log.e("UploadActivity", "Failed to initialize models: ${e.message}", e)
                updateInProgress = false
                modelLoadingInProgress = false

                withContext(Dispatchers.Main) {
                    if (ModelManager.areModelsAvailable()) {
                        updateLocalModelReferences()
                        updateModelStatusIndicator()
                        Toast.makeText(
                            this@UploadActivity,
                            "Using previously loaded models",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        updateModelStatusIndicator()

                        if (!modelErrorDialogShown && !isFinishing) {
                            modelErrorDialogShown = true
                            showModelErrorDialog("""
                                Failed to load AI models. Some features may be unavailable.
                                
                                Error: ${e.message ?: "Unknown error"}
                                
                                You can still upload images without AI analysis.
                            """.trimIndent())
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

                if (!modelLoadingInProgress) {
                    updateModelStatusIndicator()
                }
            }
        }
    }

    private fun updateModelStatusIndicator() {
        val yoloReady = yoloDetector != null
        val cnnReady = maturityClassifier != null

        runOnUiThread {
            val allReady = yoloReady && cnnReady
            val anyReady = yoloReady || cnnReady

            when {
                modelLoadingInProgress -> {
                    modelStatusIndicator.setImageResource(R.drawable.ic_model_loading)
                    modelStatusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.blue))
                    modelStatusText.text = "Loading AI..."
                    modelStatusText.setTextColor(ContextCompat.getColor(this, R.color.blue))
                }
                allReady -> {
                    modelStatusIndicator.setImageResource(R.drawable.ic_model_ready)
                    modelStatusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.green))
                    modelStatusText.text = "AI Ready"
                    modelStatusText.setTextColor(ContextCompat.getColor(this, R.color.green))
                }
                anyReady -> {
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
        val cnnReady = maturityClassifier != null
        val loading = modelLoadingInProgress

        val message = buildString {
            append("🤖 AI Model Status\n\n")

            if (loading) {
                append("🔄 Models are currently loading...\n\n")
            }

            append("📊 Current Status:\n")
            if (yoloReady) {
                append("✅ YOLO (Object Detection) loaded\n")
            } else if (loading) {
                append("⏳ YOLO (Object Detection) loading...\n")
            } else {
                append("❌ YOLO (Object Detection) not loaded\n")
            }

            if (cnnReady) {
                append("✅ CNN-LSTM (Plant Analysis) loaded\n")
            } else if (loading) {
                append("⏳ CNN-LSTM (Plant Analysis) loading...\n")
            } else {
                append("❌ CNN-LSTM (Plant Analysis) not loaded\n")
            }

            append("\nModels load automatically at app startup.")
        }

        AlertDialog.Builder(this)
            .setTitle("Model Status")
            .setMessage(message)
            .setPositiveButton("Check for Updates") { _, _ ->
                checkForUpdatesManually()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateLocalModelReferences() {
        yoloDetector = ModelManager.getYoloDetector()
        maturityClassifier = ModelManager.getMaturityClassifier()

        runOnUiThread {
            updateModelStatusIndicator()
            Log.d("UploadActivity", "Updated model references - YOLO: ${yoloDetector != null}, CNN: ${maturityClassifier != null}")
        }
    }

    private fun showModelErrorDialog(error: String) {
        AlertDialog.Builder(this)
            .setTitle("AI Models Not Available")
            .setMessage(error)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onUpdateStarted() {
        runOnUiThread {
            showUpdateProgressDialog("Checking for updates...")
        }
    }

    override fun onDownloadProgress(fileName: String, progress: Int, totalFiles: Int) {
        runOnUiThread {
            updateProgressDialogMessage("Downloading $fileName... ($progress/$totalFiles)")
        }
    }

    override fun onUpdateCompleted(success: Boolean, message: String) {
        runOnUiThread {
            dismissUpdateProgressDialog()

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
                        Log.e("UploadActivity", "Failed to reload models after update", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@UploadActivity, "Failed to reload models: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else if (message != "Models are already up to date") {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } else {
                updateLocalModelReferences()
            }
        }
    }

    private fun showUpdateProgressDialog(message: String) {
        try {
            val dialogView = layoutInflater.inflate(R.layout.dialog_update_progress, null)
            progressTextView = dialogView.findViewById(R.id.progressTextView)

            updateProgressDialog = AlertDialog.Builder(this)
                .setTitle("Updating AI Models")
                .setView(dialogView)
                .setCancelable(false)
                .create()

            progressTextView.text = message
            updateProgressDialog.show()
        } catch (e: Exception) {
            Log.e("UploadActivity", "Failed to show progress dialog", e)
            updateProgressDialog = AlertDialog.Builder(this)
                .setTitle("Updating AI Models")
                .setMessage(message)
                .setCancelable(false)
                .create()
            updateProgressDialog.show()
        }
    }

    private fun updateProgressDialogMessage(message: String) {
        if (::progressTextView.isInitialized) {
            progressTextView.text = message
        } else if (::updateProgressDialog.isInitialized && updateProgressDialog.isShowing) {
            updateProgressDialog.setMessage(message)
        }
    }

    private fun dismissUpdateProgressDialog() {
        if (::updateProgressDialog.isInitialized && updateProgressDialog.isShowing) {
            updateProgressDialog.dismiss()
        }
    }

    fun checkForUpdatesManually() {
        if (updateInProgress) {
            Toast.makeText(this, "Update already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            updateInProgress = true
            val updated = ModelUpdateManager.checkForModelUpdates(this@UploadActivity, this@UploadActivity)
            updateInProgress = false

            if (!updated) {
                withContext(Dispatchers.Main) {
                    updateLocalModelReferences()
                    Toast.makeText(this@UploadActivity, "Models are up to date", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showImageSourceDialog() {
        val options = arrayOf("Take Photo", "Choose from Gallery")
        AlertDialog.Builder(this)
            .setTitle("Select Image Source")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> openGallery()
                }
            }
            .show()
    }

    private fun openGallery() {
        pickImagesLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun openCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 100)
            return
        }

        val imageFile = createImageFile()
        cameraImageUri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.provider",
            imageFile
        )
        takePictureLauncher.launch(cameraImageUri)
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = cacheDir
        val file = File(storageDir, "IMG_$timeStamp.jpg")
        file.createNewFile()
        return file
    }

    private fun setupAdapter() {
        adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf()
        )
        conversationListView.adapter = adapter
    }

    private fun refreshConversationList() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val all = db.conversationDao().getAllConversations()
                val names = all.map { it.name }

                withContext(Dispatchers.Main) {
                    adapter.clear()
                    adapter.addAll(names)
                    adapter.notifyDataSetChanged()

                    if (names.isEmpty()) {
                        val emptyText = TextView(this@UploadActivity).apply {
                            text = "No Plantation yet"
                            gravity = Gravity.CENTER
                            setTextColor(ContextCompat.getColor(this@UploadActivity, android.R.color.darker_gray))
                        }
                        conversationListView.emptyView = emptyText
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@UploadActivity,
                        "Error loading Plantations",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun openConversation(position: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val all = db.conversationDao().getAllConversations()
            if (position in all.indices) {
                val conv = all[position]
                conversationId = conv.id
                withContext(Dispatchers.Main) {
                    drawerLayout.closeDrawer(GravityCompat.END)
                    val intent = Intent(this@UploadActivity, ConversationsActivity::class.java).apply {
                        putExtra(EXTRA_CONVERSATION_ID, conv.id)
                        putExtra("conversation_name", conv.name)
                        putExtra("yolo_available", ModelManager.getYoloDetector() != null)
                        putExtra("cnn_available", ModelManager.getMaturityClassifier() != null)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun showConversationOptions(position: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val all = db.conversationDao().getAllConversations()
            if (position in all.indices) {
                val conv = all[position]
                withContext(Dispatchers.Main) {
                    showOptionsDialog(conv)
                }
            }
        }
    }

    private fun showOptionsDialog(conv: ConversationEntity) {
        val options = arrayOf("Edit Name", "Delete Plantation")
        AlertDialog.Builder(this)
            .setTitle("Manage '${conv.name}'")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditDialog(conv)
                    1 -> deleteConversation(conv)
                }
            }
            .show()
    }

    private fun showEditDialog(conv: ConversationEntity) {
        val input = EditText(this).apply {
            setText(conv.name)
            setSelection(conv.name.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Plantation Name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.conversationDao().updateConversationName(conv.id, newName)
                        refreshConversationList()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@UploadActivity, "Plantation updated", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteConversation(conv: ConversationEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Plantation")
            .setMessage("Are you sure you want to delete '${conv.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.conversationDao().deleteConversation(conv)
                    if (conv.id == conversationId) conversationId = -1L
                    refreshConversationList()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@UploadActivity, "Plantation deleted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        flipper.startFlipping()
    }

    override fun onPause() {
        super.onPause()
        flipper.stopFlipping()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}