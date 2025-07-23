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
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * TightPollingService is a background service responsible for continuously monitoring
 * the device's location, foreground app usage, and app install events. It logs these
 * events to a local file and triggers a background worker to upload the log to
 * Google Drive on a 5-minute timeout.
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

    // State tracking variables
    private var lastSpeedAlertTime: Long = 0
    private var lastKnownForegroundApp: String? = null
    private var lastKnownSpeedMph: Float = -1.0f

    companion object {
        const val NOTIFICATION_ID = 101
        const val NOTIFICATION_CHANNEL_ID = "TightPollingChannel"
    }

    /**
     * Initializes the service, sets up location client, location callback,
     * registers the app install/uninstall receiver, and initializes the log file.
     */
    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        registerAppInstallUninstallReceiver()
        // Initialize the log file that this service will write to.
        logFile = File(cacheDir, TightPollUploadWorker.LOG_FILE_NAME)
    }

    /**
     * Called when the service is started.
     * Starts the foreground service with a notification, starts location updates,
     * and starts app usage polling.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d("TightPollingService", "Service started.")

        startLocationUpdates()
        startAppUsagePolling()

        return START_STICKY
    }

    /**
     * Creates the notification for the foreground service.
     * Sets up the notification channel and builds the notification.
     * @return The notification object.
     */
    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Tight Polling Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Data Tracker Active")
            .setContentText("Monitoring location and app usage.")
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's icon
            .build()
    }

    /**
     * Sets up the location callback to receive location updates.
     * When a location update is received, it checks the speed and logs a speed alert
     * if the speed is over 20 MPH and it has been more than an hour since the last alert.
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
                                    "Location: https://www.google.com/maps?q=${location.latitude},${location.longitude}"
                            // Log the event to a file.
                            logEvent(logMessage)
                        }
                    }
                }
            }
        }
    }

    /**
     * Starts polling for foreground app usage.
     * Continuously checks the current foreground app and logs an event
     * if the foreground app changes.
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
     * Registers a broadcast receiver to listen for app install and uninstall events.
     * When an app is installed or uninstalled, it logs the event with the app name
     * and package name.
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
     * @param message The message to log.
     * This function is synchronized to prevent concurrent file access issues.
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
     * Enqueues the TightPollUploadWorker. It no longer needs to pass the account name,
     * as the worker will get it from SharedPreferences. Sets network constraints for the worker.
     *
     */
    private fun triggerUploadWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val uploadWorkRequest = OneTimeWorkRequestBuilder<TightPollUploadWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueue(uploadWorkRequest)
    }

    /**
     * Starts location updates using the FusedLocationProviderClient.
     * Requests high accuracy location updates every 2 seconds.
     */
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    /**
     * Called when the service is destroyed.
     * Removes location updates, unregisters the app install/uninstall receiver,
     * cancels the service scope, and logs that the service has stopped.
     */
    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        appInstallUninstallReceiver?.let { unregisterReceiver(it) }
        serviceScope.cancel()
        Log.d("TightPollingService", "Service stopped.")
    }

    /**
     * Returns null as this service is not designed to be bound.
     * @param intent The Intent that was used to bind to this service.
     * @return Return an IBinder through which clients can call on to the service.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Retrieves the application name from its package name.
     * @param packageName The package name of the application.
     * @return The application name, or the package name if the name cannot be found.
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