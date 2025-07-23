package com.example.datatrackerapp

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

/**
 * A CoroutineWorker responsible for uploading system event logs to Google Drive.
 *
 * Key functionalities:
 * - Retrieves account name from input data.
 * - Renames the log file before uploading to prevent conflicts.
 * - Uploads the log file to Google Drive using DriveUploader.
 * - Handles success and failure scenarios, including renaming the file back on failure for retry.
 * - Cleans up temporary files.
 */
class SystemEventUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    /**
     * Companion object holding constants.
     * - `LOG_FILE_NAME`: The name of the log file to be uploaded.
     */
    companion object {
        const val LOG_FILE_NAME = "system_event_log.txt"
    }
    /**
     * The main work method for the worker.
     * - Retrieves the account name, checks if the log file exists and is not empty.
     * - Renames the log file, attempts to upload it, and handles the result.
     * - Performs cleanup of temporary files.
     * @return Result indicating success or failure of the work.
     */
    override suspend fun doWork(): Result {
        val accountName = inputData.getString("ACCOUNT_NAME")
        if (accountName.isNullOrEmpty()) {
            Log.e("SystemEventUploadWorker", "ERROR: Account name not provided.")
            return Result.failure()
        }

        Log.d("SystemEventUploadWorker", "Worker starting for user: $accountName")
        val uploader = DriveUploader(applicationContext, accountName)
        val logFile = File(applicationContext.cacheDir, LOG_FILE_NAME)

        if (!logFile.exists() || logFile.length() == 0L) {
            Log.d("SystemEventUploadWorker", "Log file is empty or doesn't exist. Nothing to upload.")
            return Result.success()
        }

        // Rename the file before uploading to prevent conflicts if new events are logged.
        val uploadFile = File(applicationContext.cacheDir, "system_event_upload_${System.currentTimeMillis()}.txt")
        logFile.renameTo(uploadFile)
        Log.d("SystemEventUploadWorker", "Renamed log file to ${uploadFile.name} for upload.")

        try {
            val uploadId = uploader.uploadFile(uploadFile)
            return if (uploadId != null) {
                Log.d("SystemEventUploadWorker", "SUCCESS: Upload complete.")
                Result.success()
            } else {
                Log.e("SystemEventUploadWorker", "ERROR: Upload failed.")
                uploadFile.renameTo(logFile) // Rename back on failure to retry later.
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e("SystemEventUploadWorker", "ERROR: Worker failed with an exception.", e)
            uploadFile.renameTo(logFile) // Also rename back on exception.
            return Result.failure()
        } finally {
            // Clean up the renamed file after the attempt.
            if (uploadFile.exists()) {
                uploadFile.delete()
                Log.d("SystemEventUploadWorker", "Cleanup: Deleted temporary upload file.")
            }
        }
    }
}