package com.example.datatrackerapp

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

/**
 * A [CoroutineWorker] responsible for uploading the tight poll log file (located at an absolute path like `/data/user/0/com.example.datatrackerapp/cache/tight_poll_log.txt`)
 * to Google Drive.
 *
 * This worker is designed to be triggered periodically. Its main purpose is to ensure that
 * locally collected data, stored in a file named `tight_poll_log.txt` within the application's
 * cache directory, is backed up to a remote location (Google Drive).
 * It handles critical aspects such as renaming the file before upload to avoid race conditions
 * if the file is being written to concurrently, and ensures proper cleanup of temporary files.
 */
class TightPollUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val LOG_FILE_NAME = "tight_poll_log.txt"
    }

    override suspend fun doWork(): Result {
        val accountName = inputData.getString("ACCOUNT_NAME")
        if (accountName.isNullOrEmpty()) {
            Log.e("TightPollUploadWorker", "ERROR: Account name not provided.")
            return Result.failure()
        }

        Log.d("TightPollUploadWorker", "Worker starting for user: $accountName")
        val uploader = DriveUploader(applicationContext, accountName)
        val logFile = File(applicationContext.cacheDir, LOG_FILE_NAME)

        if (!logFile.exists() || logFile.length() == 0L) {
            Log.d("TightPollUploadWorker", "Log file is empty or doesn't exist. Nothing to upload.")
            return Result.success()
        }

        // To prevent race conditions, we rename the file before uploading.
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
                // Rename back on failure so we don't lose the data.
                uploadFile.renameTo(logFile)
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e("TightPollUploadWorker", "ERROR: Worker failed with an exception.", e)
            uploadFile.renameTo(logFile) // Also rename back on exception.
            return Result.failure()
        } finally {
            // Clean up the renamed file after the attempt.
            if (uploadFile.exists()) {
                uploadFile.delete()
                Log.d("TightPollUploadWorker", "Cleanup: Deleted temporary upload file.")
            }
        }
    }
}