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

/**
 * This class is a CoroutineWorker responsible for collecting various types of device data,
 * saving it to a temporary file, and uploading it to Google Drive.
 * It manages the lifecycle of the data collection and upload process, including deleting previous uploads and cleaning up local files.
 */
class DataCollectionWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("DriveUploadPrefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_LAST_UPLOADED_FILE_ID = "last_uploaded_file_id"
    }

    /**
     * This is the main entry point for the worker.
     * It orchestrates the following steps:
     * 1. Retrieves and deletes the previously uploaded file from Google Drive.
     * 2. Collects new device data using `DeviceDataCollector`.
     * 3. Creates a temporary file with the collected data.
     * 4. Uploads the new file to Google Drive.
     * 5. Saves the ID of the newly uploaded file for the next run and cleans up the local temporary file.
     */
    override suspend fun doWork(): Result {
        val accountName = inputData.getString("ACCOUNT_NAME")
        if (accountName.isNullOrEmpty()) {
            Log.e("DataCollectionWorker", "ERROR: Account name not provided.")
            return Result.failure()
        }

        Log.d("DataCollectionWorker", "Hourly poll starting for user: $accountName")
        val uploader = DriveUploader(appContext, accountName)
        var tempLogFile: File? = null

        try {
            // Step 1: Get the ID of the last uploaded file and delete it from Drive.
            val lastFileId = prefs.getString(KEY_LAST_UPLOADED_FILE_ID, null)
            uploader.deletePreviousFile(lastFileId)

            // Step 2: Collect data using the new DeviceDataCollector class.
            Log.d("DataCollectionWorker", "Collecting data using DeviceDataCollector...")
            val dataCollector = DeviceDataCollector(appContext)
            val allData = buildDataString(dataCollector)

            // Step 3: Create the new temporary file.
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val fileName = "hourly_poll_$timestamp.txt"
            tempLogFile = File(appContext.cacheDir, fileName)
            tempLogFile.writeText(allData)

            // Step 4: Upload the new file.
            val newFileId = uploader.uploadFile(tempLogFile)

            if (newFileId != null) {
                // Step 5: If upload is successful, save the new file ID for the next run.
                prefs.edit().putString(KEY_LAST_UPLOADED_FILE_ID, newFileId).apply()
                Log.d("DataCollectionWorker", "SUCCESS: New file ID $newFileId saved.")
                return Result.success()
            } else {
                Log.e("DataCollectionWorker", "ERROR: Upload failed.")
                return Result.failure()
            }
        } catch (e: Exception) {
            Log.e("DataCollectionWorker", "ERROR: Worker failed with an exception.", e)
            return Result.failure()
        } finally {
            // Step 6: Clean up the local temporary file.
            tempLogFile?.delete()
        }
    }

    /**
     * Constructs a single string containing all collected device data.
     * It calls various methods on the `DeviceDataCollector` instance to get different types of data.
     * @param dataCollector An instance of `DeviceDataCollector` used to fetch device information.
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