package com.example.datatrackerapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

/**
 * Worker responsible for uploading system event logs to Google Drive.
 * - Retrieves the Android ID and prepends it to the log file content before upload.
 * - Clears the original log file after successful upload.
 */
class SystemEventUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("DriveUploadPrefs", Context.MODE_PRIVATE)

    companion object {
        const val LOG_FILE_NAME = "system_event_log.txt"
    }

    /**
     * The main work method for the worker.
     * - Reads log data, prepares it for upload by adding Android ID, and uploads it.
     */
    override suspend fun doWork(): Result {
        val accountName = prefs.getString("ACCOUNT_NAME", null)
        if (accountName.isNullOrEmpty()) {
            Log.e("SystemEventUploadWorker", "ERROR: Account name not found in SharedPreferences.")
            return Result.failure()
        }

        Log.d("SystemEventUploadWorker", "Worker starting for user: $accountName")
        val uploader = DriveUploader(applicationContext, accountName)
        val logFile = File(applicationContext.cacheDir, LOG_FILE_NAME)

        if (!logFile.exists() || logFile.length() == 0L) {
            Log.d("SystemEventUploadWorker", "Log file is empty. Nothing to upload.")
            return Result.success()
        }

        // --- MODIFICATION START ---
        val dataCollector = DeviceDataCollector(applicationContext)
        val androidId = dataCollector.getAndroidId()
        Log.d("SystemEventUploadWorker", "Device ANDROID_ID: $androidId")

        val timestamp = System.currentTimeMillis()
        val uploadFileName = "${androidId}_system_event_upload_$timestamp.txt"
        val uploadFile = File(applicationContext.cacheDir, uploadFileName)

        val originalContent = logFile.readText()
        val finalFileContent = "ANDROID_ID: $androidId\n\n$originalContent"
        uploadFile.writeText(finalFileContent)

        logFile.writeText("") // Clear original log file
        // --- MODIFICATION END ---

        try {
            val uploadId = uploader.uploadFile(uploadFile)
            return if (uploadId != null) {
                Result.success()
            } else {
                logFile.appendText(originalContent) // Restore on failure
                Result.failure()
            }
        } catch (e: Exception) {
            logFile.appendText(originalContent) // Restore on exception
            return Result.failure()
        } finally {
            if (uploadFile.exists()) {
                uploadFile.delete()
            }
        }
    }
}