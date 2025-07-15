package com.example.datatrackerapp

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.datatrackerapp.ui.theme.DatatrackerappTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.util.concurrent.TimeUnit
import android.os.Build
/**
 * MainActivity is the main entry point of the application.
 * It handles permission requests, sets up the UI, and initiates background services
 * for data collection and system event monitoring.
 */
class MainActivity : ComponentActivity() {

    // Receiver for system-level events like screen on/off, boot completed, etc.
    private lateinit var systemEventReceiver: SystemEventReceiver

    // Coroutine scope for managing background tasks tied to the activity's lifecycle.
    // Dispatchers.IO is used for I/O-bound tasks.
    private val scope = CoroutineScope(Dispatchers.IO)

    // Flag to track if the systemEventReceiver has been registered.
    // This is important to prevent errors when trying to unregister it.
    private var isReceiverRegistered = false

    // ActivityResultLauncher for handling permission requests.
    // This modern approach replaces the deprecated onActivityResult method.
    // It takes an array of permission strings and a callback lambda that is invoked
    // when the user responds to the permission dialog.
    private val requestPermissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // This block is called when the user responds to the permission dialog.
            if (permissions.values.all { it }) {
                // All permissions were granted.
                Log.d("MainActivity", "All permissions granted. Starting services.")
                startServices()
            } else {
                // One or more permissions were denied.
                println("One or more permissions were denied.")
                val deniedPermissions = permissions.filter { !it.value }.keys
                Log.w("Permissions", "Denied permissions: $deniedPermissions")
            }
        }

    /**
     * Called when the activity is first created.
     * This is where you should do all of your normal static set up: create views,
     * bind data to lists, etc. This method also provides you with a Bundle containing
     * the activity's previously frozen state, if there was one.
     *
     * @param savedInstanceState If the activity is being re-initialized after
     *     previously being shut down then this Bundle contains the data it most
     *     recently supplied in onSaveInstanceState(Bundle). Otherwise it is null.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check for necessary permissions and start background services if granted.
        checkPermissionsAndStartServices()
        // Initialize and register the SystemEventReceiver to listen for system broadcasts.
        systemEventReceiver = SystemEventReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_BATTERY_LOW)
            // Note: Intent.ACTION_BATTERY_CHANGED is very frequent and might be resource-intensive.
            // Consider if it's truly needed or if its handling can be optimized.
        }
        // Use applicationContext for receivers that are not tied to the UI lifecycle,
        // ensuring they continue to operate even if the activity is destroyed.
        // RECEIVER_NOT_EXPORTED means the receiver is not intended for use by other apps.
        ContextCompat.registerReceiver(applicationContext, systemEventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        isReceiverRegistered = true // Mark the receiver as registered.

        // Enable edge-to-edge display for a more immersive UI.
        enableEdgeToEdge()

        // Set the content of the activity using Jetpack Compose.
        setContent {
            DatatrackerappTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Text(
                        text = "Device Event Monitor is active.\n\nThis app listens for system events in the background and sends email notifications.\n\nClose this screen; the monitoring will continue.",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    /**
     * Called when the activity is being destroyed.
     * This is the final call the activity receives.
     */
    override fun onDestroy() {
        super.onDestroy()
        // Cancel the coroutine scope when the activity is destroyed
        scope.cancel()
        // Unregister the SystemEventReceiver
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(systemEventReceiver)
                isReceiverRegistered = false
                Log.d("MainActivity", "SystemEventReceiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w("MainActivity", "SystemEventReceiver was not registered or already unregistered: ${e.message}")
            }
        }
    }

    /**
     * Checks if all required permissions are granted. If not, it requests them.
     * If all permissions are granted, it proceeds to start the background services.
     */
    private fun checkPermissionsAndStartServices() {
        // First, check for the special "Usage Access" permission.
        if (!hasUsageStatsPermission()) {
            println("Please grant Usage Access permission")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            println("Waiting for Usage Access permission...")
            // The user will need to restart the app after granting this.
            return
        }

        // Define a list of runtime permissions required by the app.
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )

        // For Android 13 (TIRAMISU) and above, POST_NOTIFICATIONS permission is required for sending notifications.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Identify permissions that have not yet been granted.
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            // All permissions are already granted.
            Log.d("MainActivity", "Permissions already granted. Starting services.")
            startServices()
        } else {
            // Request the missing permissions.
            Log.d("MainActivity", "Requesting permissions: ${permissionsToRequest.joinToString()}")
            requestPermissionLauncher.launch(permissionsToRequest)
        }
    }

    /**
     * Starts the background services required by the application.
     * This includes scheduling hourly data collection work and starting a tight polling service.
     */
    private fun startServices() {
        println("Starting background services...")
        scheduleHourlyWork()
        startTightPollingService()
        println("Hourly and tight-polling services are active.")
    }
    /**
     * Starts the TightPollingService as a foreground service.
     * Foreground services are less likely to be killed by the system.
     */
    private fun startTightPollingService() {
        val serviceIntent = Intent(this, TightPollingService::class.java)
        startForegroundService(serviceIntent)
        Log.d("MainActivity", "Tight polling foreground service started.")
    }

    /**
     * Checks if the app has been granted the "Usage Access" permission.
     * This permission allows the app to access information about app usage.
     *
     * @return True if the permission is granted, false otherwise.
     */
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Schedules a periodic background task using WorkManager to collect data hourly.
     * The task is constrained to run only when there is a network connection.
     */
    private fun scheduleHourlyWork() {
        println("Permissions granted. Hourly polling is scheduled.")

        // Define constraints for the work, e.g., requires a network connection.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Create a periodic work request that runs once per hour.
        val periodicWorkRequest = PeriodicWorkRequestBuilder<DataCollectionWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        // Enqueue the unique work. This ensures only one instance of this hourly poll is running.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "HourlyDataPoll",
            ExistingPeriodicWorkPolicy.KEEP, // Keep the existing work if it's already scheduled
            periodicWorkRequest
        )
    }

}