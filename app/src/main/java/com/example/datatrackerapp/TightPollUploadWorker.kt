package com.example.datatrackerapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

/**
 * A CoroutineWorker responsible for uploading the tight poll log file to Google Drive.
 * It reads the log file, prepends the Android ID, uploads it, and then clears the original log file.
 */
class TightPollUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    // SharedPreferences instance for accessing saved preferences.
    // Specifically used here to retrieve the Google account name.
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("DriveUploadPrefs", Context.MODE_PRIVATE)

    companion object {
        const val LOG_FILE_NAME = "tight_poll_log.txt"
    }

    /**
     * The main work method for the worker.
     * - Retrieves the Google account name from SharedPreferences.
     * - Reads the log file, prepends the Android ID, and uploads it to Google Drive.
     * - Clears the original log file upon successful upload.
     */
    override suspend fun doWork(): Result {
        val accountName = prefs.getString("ACCOUNT_NAME", null)
        if (accountName.isNullOrEmpty()) {
            Log.e("TightPollUploadWorker", "ERROR: Account name not found in SharedPreferences.")
            return Result.failure()
        }

        Log.d("TightPollUploadWorker", "Worker starting for user: $accountName")
        val uploader = DriveUploader(applicationContext, accountName)
        val logFile = File(applicationContext.cacheDir, LOG_FILE_NAME)

        if (!logFile.exists() || logFile.length() == 0L) {
            Log.d("TightPollUploadWorker", "Log file is empty or doesn't exist. Nothing to upload.")
            return Result.success()
        }

        // Get the Android ID to use in the filename and content.
        val dataCollector = DeviceDataCollector(applicationContext)
        val androidId = dataCollector.getAndroidId()
        Log.d("TightPollUploadWorker", "Device ANDROID_ID: $androidId")

        // Create a new filename with the Android ID.
        val timestamp = System.currentTimeMillis()
        val uploadFileName = "${androidId}_tight_poll_upload_$timestamp.txt"
        val uploadFile = File(applicationContext.cacheDir, uploadFileName)

        // Read the original content, prepend the ID, and write to the new upload file.
        val originalContent = logFile.readText()
        val finalFileContent = "ANDROID_ID: $androidId\n\n$originalContent"
        uploadFile.writeText(finalFileContent)

        // Clear the original log file now that its content has been moved.
        logFile.writeText("")
        Log.d("TightPollUploadWorker", "Moved content from ${logFile.name} to ${uploadFile.name} for upload.")

        try {
            val uploadId = uploader.uploadFile(uploadFile)
            return if (uploadId != null) {
                Log.d("TightPollUploadWorker", "SUCCESS: Upload complete.")
                Result.success()
            } else {
                Log.e("TightPollUploadWorker", "ERROR: Upload failed. Appending content back to main log.")
                // If upload fails, append the content back to the original log file so it's not lost.
                logFile.appendText(originalContent)
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e("TightPollUploadWorker", "ERROR: Worker failed with an exception.", e)
            logFile.appendText(originalContent) // Also restore on exception.
            return Result.failure()
        } finally {
            // Clean up the temporary upload file.
            if (uploadFile.exists()) {
                uploadFile.delete()
            }
        }
    }
}