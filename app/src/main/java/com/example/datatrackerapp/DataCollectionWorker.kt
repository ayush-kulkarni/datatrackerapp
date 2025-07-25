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
 * A CoroutineWorker that periodically collects data from the device and uploads it to Google Drive.
 *
 * @param appContext The application context.
 * @param workerParams Parameters for the worker.
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
     * The main work method for the worker.
     * This method is called by the WorkManager to execute the data collection and upload process.
     * It reads the Google account name from SharedPreferences, deletes the previously uploaded file
     * from Google Drive, collects various data points from the device, creates a new temporary file
     * with this data, uploads the new file to Google Drive, and saves the new file's ID for the
     * next run.
     */
    override suspend fun doWork(): Result {
        // --- THIS IS THE FIX ---
        // Read the account name directly from SharedPreferences.
        val accountName = prefs.getString("ACCOUNT_NAME", null)
        if (accountName.isNullOrEmpty()) {
            Log.e("DataCollectionWorker", "ERROR: Account name not found in SharedPreferences. Cannot upload.")
            return Result.failure() // Fail the job if no account is saved.
        }
        // --- END OF FIX ---

        Log.d("DataCollectionWorker", "Hourly poll starting for user: $accountName")
        val uploader = DriveUploader(appContext, accountName)
        var tempLogFile: File? = null

        try {
            // Step 1: Get the ID of the last uploaded file and delete it from Drive.
            val lastFileId = prefs.getString(KEY_LAST_UPLOADED_FILE_ID, null)
            uploader.deletePreviousFile(lastFileId)

            // Step 2: Collect data using the DeviceDataCollector class.
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
                prefs.edit { putString(KEY_LAST_UPLOADED_FILE_ID, newFileId) }
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
     * Helper function to build the data string by calling all methods
     * from the DeviceDataCollector.
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