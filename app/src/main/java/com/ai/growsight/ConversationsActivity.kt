package com.ai.growsight

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.semantics.text
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.ai.growsight.data.AppDatabase
import com.ai.growsight.data.ConversationEntity
import com.ai.growsight.data.PromptEntity
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ConversationsActivity : AppCompatActivity() {

    companion object {
        const val PICK_IMAGES_REQUEST = 1001
        private const val CAMERA_REQUEST_CODE = 2001
        private const val READ_STORAGE_PERMISSION_REQUEST_CODE = 102
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val CAMERA_PERMISSION_CODE = 3001
        const val EXTRA_IMAGE_URIS = "extra_image_uris" // Fixed the red issue here
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

    private val uploadedUris = mutableListOf<Uri>()
    private var cameraImageUri: Uri? = null
    private var conversationId: Long = -1L

    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversations)

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
        val menuButton = findViewById<ImageButton>(R.id.menuButton)

        // Toggle drawer
        menuButton.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                drawerLayout.closeDrawer(GravityCompat.END)
            } else {
                drawerLayout.openDrawer(GravityCompat.END)
            }
        }

        // Handle incoming images from MainActivity
        val incomingUris = intent.getParcelableArrayListExtra<Uri>(EXTRA_IMAGE_URIS)
        lifecycleScope.launch {
            if (conversationId == -1L && !incomingUris.isNullOrEmpty()) {
                // Create new conversation immediately
                val count = conversationDao.getConversationCount()
                val defaultName = "Conversation#${count + 1}"
                val newId = conversationDao.insertConversation(ConversationEntity(name = defaultName))
                conversationId = newId
                runOnUiThread { conversationTitle.text = defaultName }
                refreshConversationList()

                // Persist images once and insert ONE prompt that contains ALL the image URIs
                val persistedUris: List<Uri> = incomingUris.mapNotNull { ensureLocalCopy(it) }
                if (persistedUris.isNotEmpty()) {
                    val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(Date())
                    val status = "Status: Analysis Complete"
                    val diagnostic = "Diagnostic Report - $timestamp\nProcessed Images: ${persistedUris.size}\n$status"

                    promptDao.insertPrompt(
                        PromptEntity(
                            conversationId = conversationId,
                            imageUris = persistedUris.map { it.toString() },
                            diagnostic = diagnostic,
                            timestamp = timestamp
                        )
                    )

                    runOnUiThread {
                        addConversationCard(persistedUris, diagnostic, timestamp)
                    }
                }
            }
        }
        intent.removeExtra(EXTRA_IMAGE_URIS)


        val editTitleButton = findViewById<ImageButton>(R.id.editTitleButton)
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
                val conv = conversationDao.getConversationById(conversationId)
                conv?.let {
                    runOnUiThread { conversationTitle.text = it.name }
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
                openCamera() // Create this new function
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            }
        }

        // Upload button → create conversation if needed
        uploadButton.setOnClickListener {
            // For picking images, READ_MEDIA_IMAGES (API 33+) or READ_EXTERNAL_STORAGE is needed.
            // Let's assume you're targeting API 33+ for this example.
            // If targeting lower, you'd use Manifest.permission.READ_EXTERNAL_STORAGE.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
                    openGallery() // Create this new function
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), READ_STORAGE_PERMISSION_REQUEST_CODE)
                }
            } else { // For older versions (API < 33)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    openGallery() // Create this new function
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

            val timestamp = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault()).format(Date())
            val status = "Status: Analysis Complete"
            val diagnostic = "Diagnostic Report - $timestamp\nProcessed Images: ${persisted.size}\n$status"

            lifecycleScope.launch {
                // SINGLE prompt that groups all images
                promptDao.insertPrompt(
                    PromptEntity(
                        conversationId = conversationId,
                        imageUris = persisted.map { it.toString() },
                        diagnostic = diagnostic,
                        timestamp = timestamp
                    )
                )
            }

            addConversationCard(persisted, diagnostic, timestamp)

            uploadedUris.clear()
            previewContainer.removeAllViews()
        }
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


    private fun openCamera() {
        val photoFile = File(getExternalFilesDir(null), "photo_${System.currentTimeMillis()}.jpg")
        cameraImageUri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile)

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
        lifecycleScope.launch { // Keep your conversation creation logic if needed
            if (conversationId == -1L) {
                val count = db.conversationDao().getConversationCount() // Ensure db is initialized
                val defaultName = "Conversation#${count + 1}"
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
                            conversationTitle.text = "No conversation"
                            uploadedImagesContainer.removeAllViews()
                            previewContainer.removeAllViews()
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
                layoutParams = LinearLayout.LayoutParams(180, 180).apply { setMargins(8, 8, 8, 8) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(uri)
            }
            imageRow.addView(img)
        }
        card.findViewById<TextView>(R.id.cardDiagnostic).text = diagnostic
        card.findViewById<TextView>(R.id.cardTimestamp).text = timestamp
        uploadedImagesContainer.addView(card)
    }
}
