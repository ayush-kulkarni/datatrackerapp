package com.example.datatrackerapp

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataCollectionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("DataCollectionWorker", "Worker starting...")
        val dataCollector = DeviceDataCollector(applicationContext)
        val emailSender = EmailSender()

        return try {
            // Collect all data
            val usageStats = dataCollector.getUsageStats()
            val installedApps = dataCollector.getInstalledApps()
            val sensorData = dataCollector.getSensorData()
            val cellInfo = dataCollector.getCellInfo()
            val systemInfo = dataCollector.getSystemInfo()
            // The location function needs to be a suspend function now
            val locationInfo = dataCollector.getCurrentLocation()

            // Combine data into one string
            val allData = StringBuilder()
                .append(usageStats).append("\n\n")
                .append(installedApps).append("\n\n")
                .append(locationInfo).append("\n\n")
                .append(sensorData).append("\n\n")
                .append(cellInfo).append("\n\n")
                .append(systemInfo)
                .toString()

            // Prepare and send the email
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val subject = "Device Data Poll Report - $timestamp"
            emailSender.sendEmail(subject, allData)

            Log.d("DataCollectionWorker", "Work finished successfully.")
            Result.success()
        } catch (e: Exception) {
            Log.e("DataCollectionWorker", "Work failed", e)
            Result.failure()
        }
    }
}