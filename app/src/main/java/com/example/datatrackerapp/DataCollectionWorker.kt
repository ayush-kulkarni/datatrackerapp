package com.example.datatrackerapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.edit

/**
 * Worker class responsible for collecting device data and uploading it to Google Drive.
 */
class DataCollectionWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("DriveUploadPrefs", Context.MODE_PRIVATE)

//    companion object {
//        const val KEY_LAST_UPLOADED_FILE_ID = "last_uploaded_file_id"
//    }

    /**
     * This function is the entry point for the worker.
     * It collects device data, creates a log file, and uploads it to Google Drive.
     */
    override suspend fun doWork(): Result {
        val accountName = prefs.getString("ACCOUNT_NAME", null)
        if (accountName.isNullOrEmpty()) {
            Log.e("DataCollectionWorker", "ERROR: Account name not found in SharedPreferences.")
            return Result.failure()
        }

        Log.d("DataCollectionWorker", "Hourly poll starting for user: $accountName")
        val uploader = DriveUploader(appContext, accountName)
        var tempLogFile: File? = null

        try {
//            val lastFileId = prefs.getString(KEY_LAST_UPLOADED_FILE_ID, null)
//            uploader.deletePreviousFile(lastFileId)

            val dataCollector = DeviceDataCollector(appContext)

            // Get the Android ID first.
            val androidId = dataCollector.getAndroidId()
            Log.d("DataCollectionWorker", "Device ANDROID_ID: $androidId")

            // Collect the rest of the data.
            val pollData = buildDataString(dataCollector)

            // Create the filename using the Android ID.
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val fileName = "${androidId}_hourly_poll_$timestamp.txt"
            tempLogFile = File(appContext.cacheDir, fileName)

            // Prepend the Android ID to the file content.
            val finalFileContent = StringBuilder()
                .append("ANDROID_ID: $androidId\n\n")
                .append(pollData)
                .toString()

            tempLogFile.writeText(finalFileContent)

            val newFileId = uploader.uploadFile(tempLogFile)

            if (newFileId != null) {
//                prefs.edit { putString(KEY_LAST_UPLOADED_FILE_ID, newFileId) }
//                Log.d("DataCollectionWorker", "SUCCESS: New file ID $newFileId saved.")
                Log.d("DataCollectionWorker", "SUCCESS: Upload complete.")
                return Result.success()
            } else {
                Log.e("DataCollectionWorker", "ERROR: Upload failed.")
                return Result.failure()
            }
        } catch (e: Exception) {
            Log.e("DataCollectionWorker", "ERROR: Worker failed with an exception.", e)
            return Result.failure()
        } finally {
            // Clean up temporary file
            tempLogFile?.delete()
        }
    }

    /**
     * This function collects various device data points and concatenates them into a single string.
     * It utilizes the `DeviceDataCollector` to gather information.
     */
    private suspend fun buildDataString(dataCollector: DeviceDataCollector): String {
        return StringBuilder()
            .append(dataCollector.getUsageStats()).append("\n\n")
            .append(dataCollector.getInstalledApps()).append("\n\n")
            .append(dataCollector.getCurrentLocation()).append("\n\n")
            .append(dataCollector.getSensorData()).append("\n\n")
            .append(dataCollector.getCellInfo()).append("\n\n")
            .append(dataCollector.getSystemInfo())
            .toString()
    }
}