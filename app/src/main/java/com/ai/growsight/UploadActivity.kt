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
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.ai.growsight.ConversationsActivity.Companion.EXTRA_CONVERSATION_ID
import com.ai.growsight.ConversationsActivity.Companion.EXTRA_IMAGE_URIS
import com.ai.growsight.ai.MaturityClassifier
import com.ai.growsight.ai.ModelManager
import com.ai.growsight.ai.YoloDetector
import com.ai.growsight.data.AppDatabase
import com.ai.growsight.data.ConversationEntity
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class UploadActivity : AppCompatActivity() {

    private lateinit var uploadButton: Button
    private lateinit var flipper: ViewFlipper
    private lateinit var db: AppDatabase
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var conversationListView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var conversationId: Long = -1L

    private var cameraImageUri: Uri? = null

    // Model instances - loaded once at startup
    var yoloDetector: YoloDetector? = null
    var maturityClassifier: MaturityClassifier? = null
    private var modelsLoaded = false
    private var modelLoadingInProgress = false

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
                // Pass model references
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
                    // Pass model references
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

        // Add this to catch any initialization errors
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CRASH", "App crashed in thread: ${thread.name}", throwable)
            throwable.printStackTrace()
            // This will help us see the actual error
        }

        uploadButton = findViewById(R.id.uploadButton)
        flipper = findViewById(R.id.flipperContainer)
        drawerLayout = findViewById(R.id.drawerLayout)
        conversationListView = findViewById(R.id.conversationListView)
        val hamburgerButton = findViewById<ImageButton>(R.id.menuButton)

        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "prompts-db"
        ).fallbackToDestructiveMigration().build()

        flipper.inAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        flipper.outAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_out)
        flipper.flipInterval = 5000
        flipper.startFlipping()

        // Load models at startup
        loadModels()

        // Upload button shows choice dialog
        uploadButton.setOnClickListener {
            showImageSourceDialog()
        }

        // Hamburger menu toggle
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

    private fun loadModels() {
        if (modelLoadingInProgress) {
            Log.d("UploadActivity", "Model loading already in progress")
            return
        }

        modelLoadingInProgress = true
        lifecycleScope.launch {
            try {
                Log.d("UploadActivity", "Starting model loading using ModelManager...")

                // Use ModelManager to load models
                yoloDetector = ModelManager.getYoloDetector(this@UploadActivity)
                maturityClassifier = ModelManager.getMaturityClassifier(this@UploadActivity)

                modelsLoaded = (yoloDetector != null || maturityClassifier != null)
                modelLoadingInProgress = false

                Log.d("UploadActivity", "ModelManager loading finished. YOLO=${yoloDetector != null}, CNN=${maturityClassifier != null}")

                runOnUiThread {
                    updateUIForModelStatus()
                }
            } catch (e: Exception) {
                Log.e("UploadActivity", "Failed to load models using ModelManager: ${e.message}", e)
                modelLoadingInProgress = false
                runOnUiThread {
                    showModelErrorDialog("Failed to load AI models: ${e.message}")
                }
            }
        }
    }

    private fun updateUIForModelStatus() {
        val yoloAvailable = yoloDetector != null
        val cnnAvailable = maturityClassifier != null

        Log.d("UploadActivity", "Model Status - YOLO: $yoloAvailable, CNN: $cnnAvailable")

        if (!yoloAvailable || !cnnAvailable) {
            val message = buildString {
                append("AI Models Status:\n")
                if (!yoloAvailable) append("• Object detection unavailable\n")
                if (!cnnAvailable) append("• Plant analysis unavailable\n")
                append("\nYou can still save images without AI analysis.")
            }
            // Only show dialog if no models loaded at all
            if (!yoloAvailable && !cnnAvailable) {
                showModelErrorDialog(message)
            }
        } else {
            Toast.makeText(this, "AI models loaded successfully", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showModelErrorDialog(error: String) {
        AlertDialog.Builder(this)
            .setTitle("AI Models Not Available")
            .setMessage(error)
            .setPositiveButton("OK", null)
            .show()
    }

    // --- Choice Dialog ---
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
        lifecycleScope.launch {
            try {
                val all = db.conversationDao().getAllConversations()
                val names = all.map { it.name }

                runOnUiThread {
                    adapter.clear()
                    adapter.addAll(names)
                    adapter.notifyDataSetChanged()

                    if (names.isEmpty()) {
                        val emptyText = TextView(this@UploadActivity).apply {
                            text = "No Plantation yet"
                            gravity = Gravity.CENTER
                            setTextColor(resources.getColor(android.R.color.darker_gray))
                        }
                        conversationListView.emptyView = emptyText
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
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
        lifecycleScope.launch {
            val all = db.conversationDao().getAllConversations()
            if (position in all.indices) {
                val conv = all[position]
                conversationId = conv.id
                runOnUiThread {
                    drawerLayout.closeDrawer(GravityCompat.END)
                    val intent = Intent(this@UploadActivity, ConversationsActivity::class.java).apply {
                        putExtra(EXTRA_CONVERSATION_ID, conv.id)
                        putExtra("conversation_name", conv.name)
                        // Pass model availability
                        putExtra("yolo_available", yoloDetector != null)
                        putExtra("cnn_available", maturityClassifier != null)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun showConversationOptions(position: Int) {
        lifecycleScope.launch {
            val all = db.conversationDao().getAllConversations()
            if (position in all.indices) {
                val conv = all[position]
                runOnUiThread {
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
                    lifecycleScope.launch {
                        db.conversationDao().updateConversationName(conv.id, newName)
                        refreshConversationList()
                        runOnUiThread {
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
                lifecycleScope.launch {
                    db.conversationDao().deleteConversation(conv)
                    if (conv.id == conversationId) conversationId = -1L
                    refreshConversationList()
                    runOnUiThread {
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
            openCamera() // Permission granted, retry opening camera
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up models
        try {
            yoloDetector?.close()
        } catch (e: Exception) {
            Log.e("UploadActivity", "Error closing YOLO", e)
        }
    }
}