package com.example.datatrackerapp


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Receives system broadcasts (like boot completed or shutdown).
 * Logs these events to a local file.
 * Triggers a background worker to upload the log file.
 */
class SystemEventReceiver : BroadcastReceiver() {

    /**
     * Called when a system broadcast is received.
     * Logs the event and schedules an upload if an account is configured.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "System Event: $action at $time"

        Log.d("SystemEventReceiver", "Received event: $action")
        val logFile = File(context.cacheDir, SystemEventUploadWorker.LOG_FILE_NAME)
        try {
            logFile.appendText("$logMessage\n")
        } catch (e: Exception) {
            Log.e("SystemEventReceiver", "Failed to write to log file", e)
            return
        }

        // The receiver already correctly checks SharedPreferences, but now we simplify the worker call.
        val prefs = context.getSharedPreferences("DriveUploadPrefs", Context.MODE_PRIVATE)
        val accountName = prefs.getString("ACCOUNT_NAME", null)
        if (accountName.isNullOrEmpty()) {
            Log.e("SystemEventReceiver", "Cannot trigger upload, account name not found.")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // No longer need to create and pass inputData
        val uploadWorkRequest = OneTimeWorkRequestBuilder<SystemEventUploadWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "SystemEventUpload",
            androidx.work.ExistingWorkPolicy.KEEP,
            uploadWorkRequest
        )
        Log.d("SystemEventReceiver", "Enqueued SystemEventUploadWorker.")
    }
}