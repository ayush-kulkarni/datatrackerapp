package com.example.datatrackerapp


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Receives system broadcast intents (e.g., boot completed, app updated).
 * - Logs the received system event to a local file.
 * - Triggers a background worker (SystemEventUploadWorker) to upload the log file.
 */
class SystemEventReceiver : BroadcastReceiver() {

    /**
     * Called when a broadcast intent is received.
     * - Identifies the action of the intent.
     * - Logs the event and triggers the upload worker.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "System Event: $action at $time"

        Log.d("SystemEventReceiver", "Received event: $action")

        // Step 1: Log the event to a local file.

        val logFile = File(context.cacheDir, SystemEventUploadWorker.LOG_FILE_NAME)
        try {
            logFile.appendText("$logMessage\n")
            Log.d("SystemEventReceiver", "Logged event to ${logFile.name}")
        } catch (e: Exception) {
            Log.e("SystemEventReceiver", "Failed to write to log file", e)
            return
        }

        // Step 2: Trigger the upload worker.
        // Retrieve the saved account name from SharedPreferences.
        val prefs = context.getSharedPreferences("DriveUploadPrefs", Context.MODE_PRIVATE)
        val accountName = prefs.getString("ACCOUNT_NAME", null)

        if (accountName.isNullOrEmpty()) {
            Log.e("SystemEventReceiver", "Cannot trigger upload, account name not found in SharedPreferences.")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf("ACCOUNT_NAME" to accountName)

        val uploadWorkRequest = OneTimeWorkRequestBuilder<SystemEventUploadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        // Use a unique name to prevent multiple workers from queuing up for the same task.
        // If an upload is already pending, this new request will be ignored.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "SystemEventUpload",
            androidx.work.ExistingWorkPolicy.KEEP,
            uploadWorkRequest
        )
        Log.d("SystemEventReceiver", "Enqueued SystemEventUploadWorker.")
    }
}