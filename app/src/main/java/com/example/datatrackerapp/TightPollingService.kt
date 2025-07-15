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
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * TightPollingService is a background service responsible for continuously monitoring
 * the device's location and foreground app usage. It sends email alerts based on
 * specific triggers like speeding or app launches.
 */
class TightPollingService : Service() {

    // Coroutine scope for managing background tasks within the service.
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val emailSender = EmailSender()

    // State tracking variables
    private var lastSpeedAlertTime: Long = 0
    private var lastKnownForegroundApp: String? = null
    private var lastKnownSpeedMph: Float = -1.0f // Initialize to a value that will never be matched
    private var appInstallUninstallReceiver: BroadcastReceiver? = null

    companion object {
        // Unique ID for the foreground service notification.
        const val NOTIFICATION_ID = 101
        // Channel ID for the notification.
        const val NOTIFICATION_CHANNEL_ID = "TightPollingChannel"
    }

    /**
     * Called when the service is first created. Initializes location services and
     * registers a broadcast receiver for app install/uninstall events.
     */
    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        registerAppInstallUninstallReceiver()
    }

    /**
     * Called when the service is started. Promotes the service to a foreground service
     * and starts location and app usage polling.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d("TightPollingService", "Service started.")

        startLocationUpdates()
        startAppUsagePolling()

        return START_STICKY
    }

    private fun createNotification(): Notification {
        // Create a notification channel for Android Oreo and above.
        // This is required for foreground services.
        // The channel defines the importance and behavior of notifications.

        // Notification channel for the foreground service.
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Tight Polling Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        // Build the notification that will be displayed to the user.
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Data Tracker Active")
            .setContentText("Monitoring location and app usage.")
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with actual app's icon
            .build()
    }

    /**
     * Sets up the callback for receiving location updates. This callback processes
     * location data, calculates speed, and triggers speed alerts if necessary.
     */
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    // Speed is in meters/second. Convert to MPH (float).
                    val speedMph = location.speed * 2.23694f

                    // Only process if the speed has changed significantly.
                    // Using abs() and a small threshold (e.g., 0.1) prevents logging tiny fluctuations.
                    if (abs(speedMph - lastKnownSpeedMph) > 0.1) {
                        Log.d("TightPollingService", "Speed changed to: ${"%.2f".format(speedMph)} MPH")
                        lastKnownSpeedMph = speedMph // Update the last known speed

                        // Now check if this new speed triggers an alert.
                        val currentTime = System.currentTimeMillis()
                        val oneHourInMillis = TimeUnit.HOURS.toMillis(1)

                        if (speedMph > 20 && (currentTime - lastSpeedAlertTime > oneHourInMillis)) {
                            lastSpeedAlertTime = currentTime
                            val subject = "Speed Alert: Exceeded 20 MPH"
                            val body = "Speed of ${"%.2f".format(speedMph)} MPH detected at ${Date()}.\n" +
                                    "Location: https://www.google.com/maps?q=${location.latitude},${location.longitude}"
                            serviceScope.launch {
                                emailSender.sendEmail(subject, body)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Starts requesting location updates from the FusedLocationProviderClient.
     * Location updates are configured for high accuracy and frequent updates.
     */
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(2000)
            .setMaxUpdateDelayMillis(3000)
            .build()

        // Check for location permission before requesting updates.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    /**
     * Starts a coroutine that periodically polls for the current foreground app.
     * If a new app is launched, an email alert is sent.
     */
    private fun startAppUsagePolling() {
        serviceScope.launch {
            while (isActive) {
                val currentForegroundApp = UsageStatsPoller.getForegroundApp(this@TightPollingService)
                if (currentForegroundApp != null && currentForegroundApp != lastKnownForegroundApp) {
                    lastKnownForegroundApp = currentForegroundApp
                    val subject = "App Launch Detected"
                    val body = "App launched: $currentForegroundApp at ${Date()}"
                    // Launch a new coroutine for the email to not block the loop
                    launch {
                        emailSender.sendEmail(subject, body)
                    }
                }
                delay(2000) // Poll every 2 seconds
            }
        }
    }

    /**
     * Registers a BroadcastReceiver to listen for app installation and uninstallation events.
     * When an app is installed or uninstalled, an email alert is sent.
     */
    private fun registerAppInstallUninstallReceiver() {
        appInstallUninstallReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.data?.schemeSpecificPart?.let { packageName ->
                    val action = intent.action
                    val appName = getAppNameFromPackageName(packageName)
                    val subject: String
                    val body: String

                    if (Intent.ACTION_PACKAGE_ADDED == action) {
                        subject = "App Installed Detected"
                        body = "App installed: $appName ($packageName) at ${Date()}"
                    } else if (Intent.ACTION_PACKAGE_REMOVED == action) {
                        subject = "App Uninstalled Detected"
                        body = "App uninstalled: $appName ($packageName) at ${Date()}"
                    } else {
                        return // Not an action we are interested in
                    }

                    serviceScope.launch {
                        emailSender.sendEmail(subject, body)
                    }
                }
            }
        }
        // Create an IntentFilter to specify the actions the receiver should listen for.
        // In this case, it's package added (installed) and package removed (uninstalled).
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        intentFilter.addDataScheme("package")
        registerReceiver(appInstallUninstallReceiver, intentFilter)
    }

    /**
     * Called when the service is being destroyed. Cleans up resources by removing
     * location updates, unregistering the broadcast receiver, and canceling coroutines.
     */
    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        appInstallUninstallReceiver?.let { unregisterReceiver(it) }
        serviceScope.cancel()
        Log.d("TightPollingService", "Service stopped.")
    }

    /**
     * This service does not support binding, so it returns null.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Helper function to retrieve the application name from its package name.
     * @param packageName The package name of the application.
     * @return The application name, or the package name if the app name cannot be found.
     */
    private fun getAppNameFromPackageName(packageName: String): String {
        return try {
            val packageManager = applicationContext.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName // Fallback to package name if app name not found
        }
    }
}