package com.ai.growsight.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

object ModelUpdateManager {

    // GitHub repo details
    private const val REPO_OWNER = "krstngdm"
    private const val REPO_NAME = "THESIS-SWEETPOTATOES"
    private const val RELEASE_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    private const val LOCAL_VERSION_FILE = "ml/version.json"
    private const val TAG = "ModelUpdateManager"

    interface UpdateListener {
        fun onUpdateStarted()
        fun onDownloadProgress(fileName: String, progress: Int, totalFiles: Int)
        fun onUpdateCompleted(success: Boolean, message: String)
    }

    suspend fun checkForModelUpdates(context: Context, listener: UpdateListener? = null): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                listener?.onUpdateStarted()
                Log.d(TAG, "Checking GitHub for model updates...")

                val response = URL(RELEASE_API).readText()
                val json = JSONObject(response)

                val assets = json.getJSONArray("assets")
                val remoteVersionJsonUrl = findAssetUrl(assets, "version.json")
                    ?: return@withContext false.also {
                        listener?.onUpdateCompleted(false, "No version file found in release")
                        Log.e(TAG, "No version.json in release.")
                    }

                val remoteVersionJson = URL(remoteVersionJsonUrl).readText()
                val remote = JSONObject(remoteVersionJson)

                val localVersion = readLocalVersion(context)

                if (localVersion == remote.toString()) {
                    Log.d(TAG, "Models are up to date.")
                    listener?.onUpdateCompleted(false, "Models are already up to date")
                    return@withContext false
                }

                Log.d(TAG, "New model update found! Downloading...")

                // Download all model files with progress
                val filesToDownload = listOf(
                    "yolov8.tflite",
                    "cnn_lstm_mobile.pt",
                    "label_maps.json",
                    "numeric_cols.json",
                    "yolo_labels.json"
                )

                filesToDownload.forEachIndexed { index, fileName ->
                    listener?.onDownloadProgress(fileName, index + 1, filesToDownload.size)
                    downloadAsset(context, assets, fileName)
                }

                // Save the new version
                writeLocalVersion(context, remote.toString())

                // Clean up existing models to force reload
                ModelManager.cleanup()

                Log.d(TAG, "✓ Model update completed successfully.")
                listener?.onUpdateCompleted(true, "AI models updated successfully!")
                true

            } catch (e: Exception) {
                val errorMsg = "Update failed: ${e.message}"
                Log.e(TAG, errorMsg, e)
                listener?.onUpdateCompleted(false, errorMsg)
                false
            }
        }
    }

    private fun findAssetUrl(assets: org.json.JSONArray, name: String): String? {
        for (i in 0 until assets.length()) {
            val obj = assets.getJSONObject(i)
            if (obj.getString("name") == name)
                return obj.getString("browser_download_url")
        }
        return null
    }

    private fun downloadAsset(context: Context, assets: org.json.JSONArray, fileName: String) {
        try {
            val url = findAssetUrl(assets, fileName) ?: run {
                Log.w(TAG, "Asset $fileName not found in release")
                return
            }

            val dest = File(context.filesDir, "ml/$fileName")
            dest.parentFile?.mkdirs()

            URL(url).openStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Downloaded: $fileName (${dest.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download $fileName: ${e.message}", e)
            throw e // Re-throw to indicate update failure
        }
    }

    private fun readLocalVersion(context: Context): String? {
        val f = File(context.filesDir, LOCAL_VERSION_FILE)
        return if (f.exists()) f.readText() else null
    }

    private fun writeLocalVersion(context: Context, txt: String) {
        val f = File(context.filesDir, LOCAL_VERSION_FILE)
        f.parentFile?.mkdirs()
        f.writeText(txt)
    }

    // Helper to check if downloaded models exist
    fun hasDownloadedModels(context: Context): Boolean {
        val requiredFiles = listOf(
            "ml/yolov8.tflite",
            "ml/cnn_lstm_mobile.pt",
            "ml/label_maps.json",
            "ml/numeric_cols.json",
            "ml/yolo_labels.json"
        )

        return requiredFiles.all { filePath ->
            File(context.filesDir, filePath).exists()
        }
    }
}