package com.example.datatrackerapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Service for tight polling of location and app usage.
 * - Manages foreground notification.
 * - Logs events to a file and triggers uploads.
 * - Responds to app install/uninstall events.
 */
class TightPollingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var appInstallUninstallReceiver: BroadcastReceiver? = null

    // State for file logging and upload timing
    private var lastUploadRequestTime: Long = 0L
    private val uploadInterval = TimeUnit.MINUTES.toMillis(5)
    private lateinit var logFile: File
    private var accountName: String? = null

    // State tracking variables
    private var lastSpeedAlertTime: Long = 0
    private var lastKnownForegroundApp: String? = null
    private var lastKnownSpeedMph: Float = -1.0f

    companion object {
        const val NOTIFICATION_ID = 101
        const val NOTIFICATION_CHANNEL_ID = "TightPollingChannel"
    }

    /**
     * Called when the service is first created.
     * - Initializes location client and callback.
     * - Registers receiver for app install/uninstall.
     */
    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        registerAppInstallUninstallReceiver()
        // Initialize the log file
        logFile = File(cacheDir, TightPollUploadWorker.LOG_FILE_NAME)
    }

    /**
     * Called when the service is started with an intent.
     * - Retrieves the account name.
     * - Starts foreground service and polling.
     * - Returns START_STICKY to ensure service restarts.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Retrieve the account name passed from MainActivity.
        accountName = intent?.getStringExtra("ACCOUNT_NAME")
        if (accountName == null) {
            Log.e("TightPollingService", "Account name not provided. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        startLocationUpdates()
        startAppUsagePolling()
        return START_STICKY
    }

    /**
     * Creates the notification for the foreground service.
     * - Sets up notification channel.
     * - Builds and returns the notification.
     */
    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, "Tight Polling Service", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Data Tracker Active")
            .setContentText("Monitoring location and app usage.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    /**
     * Sets up the location callback to handle location updates.
     * - Calculates speed and logs speed alerts.
     */
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val speedMph = location.speed * 2.23694f
                    if (abs(speedMph - lastKnownSpeedMph) > 0.1) {
                        lastKnownSpeedMph = speedMph
                        val currentTime = System.currentTimeMillis()
                        if (speedMph > 20 && (currentTime - lastSpeedAlertTime > TimeUnit.HOURS.toMillis(1))) {
                            lastSpeedAlertTime = currentTime
                            val logMessage = "Speed Alert: Speed of ${"%.2f".format(speedMph)} MPH detected at ${Date()}.\n" +
                                    "Location: [https://www.google.com/maps?q=$](https://www.google.com/maps?q=$){location.latitude},${location.longitude}"
                            // Log the event to a file instead of emailing.
                            logEvent(logMessage)
                        }
                    }
                }
            }
        }
    }

    /**
     * Starts polling for foreground app usage.
     * - Runs a coroutine to periodically check the foreground app.
     * - Logs app launch events.
     */
    private fun startAppUsagePolling() {
        serviceScope.launch {
            while (isActive) {
                val currentForegroundApp = UsageStatsPoller.getForegroundApp(this@TightPollingService)
                if (currentForegroundApp != null && currentForegroundApp != lastKnownForegroundApp) {
                    lastKnownForegroundApp = currentForegroundApp
                    val logMessage = "App Launch Detected: $currentForegroundApp at ${Date()}"
                    // Log the event to a file.
                    logEvent(logMessage)
                }
                delay(2000)
            }
        }
    }

    /**
     * Registers a BroadcastReceiver to listen for app install and uninstall events.
     * - Creates an intent filter for package added/removed actions.
     * - Logs the detected events.
     */
    private fun registerAppInstallUninstallReceiver() {
        appInstallUninstallReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.data?.schemeSpecificPart?.let { packageName ->
                    val action = intent.action
                    val appName = getAppNameFromPackageName(packageName)
                    val logMessage = when (action) {
                        Intent.ACTION_PACKAGE_ADDED -> "App Installed Detected: $appName ($packageName) at ${Date()}"
                        Intent.ACTION_PACKAGE_REMOVED -> "App Uninstalled Detected: $appName ($packageName) at ${Date()}"
                        else -> return
                    }
                    // Log the event to a file.
                    logEvent(logMessage)
                }
            }
        }
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(appInstallUninstallReceiver, intentFilter)
    }

    /**
     * Appends a message to the log file and triggers an upload if the 5-minute timeout has passed.
     * This method is synchronized to be thread-safe.
     */
    @Synchronized
    private fun logEvent(message: String) {
        try {
            logFile.appendText("$message\n")
            Log.d("TightPollingService", "Logged event: $message")

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUploadRequestTime > uploadInterval) {
                Log.d("TightPollingService", "5-minute threshold passed. Triggering upload worker.")
                lastUploadRequestTime = currentTime
                triggerUploadWorker()
            }
        } catch (e: Exception) {
            Log.e("TightPollingService", "Failed to write to log file", e)
        }
    }

    /**
     * Enqueues the TightPollUploadWorker to run in the background.
     */
    private fun triggerUploadWorker() {
        val currentAccountName = accountName
        if (currentAccountName == null) {
            Log.e("TightPollingService", "Cannot trigger upload, account name is null.")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf("ACCOUNT_NAME" to currentAccountName)

        val uploadWorkRequest = OneTimeWorkRequestBuilder<TightPollUploadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(this).enqueue(uploadWorkRequest)
    }

    /**
     * Starts requesting location updates from the FusedLocationProviderClient.
     * - Checks for location permissions before requesting updates.
     */
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    /**
     * Called when the service is being destroyed.
     * - Cleans up resources: stops location updates, unregisters receiver, cancels coroutine scope.
     * - Logs service stop event.
     */
    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        appInstallUninstallReceiver?.let { unregisterReceiver(it) }
        serviceScope.cancel()
        Log.d("TightPollingService", "Service stopped.")
    }

    /**
     * Called when a client binds to the service.
     * - This service does not support binding, so it returns null.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Retrieves the application name from its package name.
     * - Uses PackageManager to get application info.
     */
    private fun getAppNameFromPackageName(packageName: String): String {
        return try {
            val packageManager = applicationContext.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}