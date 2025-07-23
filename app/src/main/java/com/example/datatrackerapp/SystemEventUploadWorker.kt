package com.example.datatrackerapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

/**
 * A CoroutineWorker responsible for uploading system event logs to Google Drive.
 * It retrieves the Google account name from SharedPreferences and uses DriveUploader to perform the upload.
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
     * - Retrieves the Google account name from SharedPreferences.
     * - Checks if the log file exists and is not empty.
     * - Uploads the log file to Google Drive using DriveUploader.
     */
    override suspend fun doWork(): Result {
        // --- THIS IS THE FIX ---
        // Read the account name directly from SharedPreferences.
        val accountName = prefs.getString("ACCOUNT_NAME", null)
        if (accountName.isNullOrEmpty()) {
            Log.e("SystemEventUploadWorker", "ERROR: Account name not found in SharedPreferences.")
            return Result.failure()
        }
        // --- END OF FIX ---

        Log.d("SystemEventUploadWorker", "Worker starting for user: $accountName")
        val uploader = DriveUploader(applicationContext, accountName)
        val logFile = File(applicationContext.cacheDir, LOG_FILE_NAME)

        if (!logFile.exists() || logFile.length() == 0L) {
            Log.d("SystemEventUploadWorker", "Log file is empty. Nothing to upload.")
            return Result.success()
        }

        val uploadFile = File(applicationContext.cacheDir, "system_event_upload_${System.currentTimeMillis()}.txt")
        logFile.renameTo(uploadFile)

        try {
            val uploadId = uploader.uploadFile(uploadFile)
            return if (uploadId != null) {
                Result.success()
            } else {
                uploadFile.renameTo(logFile)
                Result.failure()
            }
        } catch (e: Exception) {
            uploadFile.renameTo(logFile)
            return Result.failure()
        } finally {
            if (uploadFile.exists()) {
                uploadFile.delete()
            }
        }
    }
}