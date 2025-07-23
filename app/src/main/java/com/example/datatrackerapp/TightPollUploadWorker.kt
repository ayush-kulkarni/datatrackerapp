package com.example.datatrackerapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

/**
 * Worker responsible for uploading the tight poll log file to Google Drive.
 * - Initializes SharedPreferences to retrieve the account name.
 * - Defines a companion object for constants like the log file name.
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
     * The main work method for this worker.
     * - Retrieves the account name, checks if the log file exists and has content.
     * - Renames the log file for upload, attempts to upload it using DriveUploader, and handles success or failure.
     */
    override suspend fun doWork(): Result {
        // Read the account name directly from SharedPreferences.
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

        val uploadFile = File(applicationContext.cacheDir, "tight_poll_upload_${System.currentTimeMillis()}.txt")
        logFile.renameTo(uploadFile)
        Log.d("TightPollUploadWorker", "Renamed log file to ${uploadFile.name} for upload.")

        try {
            val uploadId = uploader.uploadFile(uploadFile)
            return if (uploadId != null) {
                Log.d("TightPollUploadWorker", "SUCCESS: Upload complete.")
                Result.success()
            } else {
                Log.e("TightPollUploadWorker", "ERROR: Upload failed.")
                uploadFile.renameTo(logFile)
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e("TightPollUploadWorker", "ERROR: Worker failed with an exception.", e)
            uploadFile.renameTo(logFile)
            return Result.failure()
        } finally {
            if (uploadFile.exists()) {
                uploadFile.delete()
            }
        }
    }
}