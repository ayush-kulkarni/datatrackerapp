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

class MainActivity : ComponentActivity() {

    private lateinit var systemEventReceiver: SystemEventReceiver
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isReceiverRegistered = false
    private val requestPermissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // This block is called when the user responds to the permission dialog.
            if (permissions.values.all { it }) {
                // All permissions were granted.
                scheduleHourlyWork()
            } else {
                // One or more permissions were denied.
                println("One or more permissions were denied.")
                val deniedPermissions = permissions.filter { !it.value }.keys
                Log.w("Permissions", "Denied permissions: $deniedPermissions")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // BroadcastReceiver stuff:
        val systemEventReceiver = SystemEventReceiver()
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        filter.addAction(Intent.ACTION_BOOT_COMPLETED)
        filter.addAction(Intent.ACTION_BATTERY_LOW)
        filter.addAction(Intent.ACTION_BATTERY_CHANGED)
        filter.addAction(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        ContextCompat.registerReceiver(
            this,
            systemEventReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReceiverRegistered = true

        // Hourly Poll:
        checkPermissionsAndScheduleWork()

        enableEdgeToEdge()
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


    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun checkPermissionsAndScheduleWork() {
        println("1")
        if (!hasUsageStatsPermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        val requiredPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )

        // Check which permissions are already granted.
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            scheduleHourlyWork()
        } else {
            println("here")
            requestPermissionLauncher.launch(permissionsToRequest)
        }
    }

    private fun scheduleHourlyWork() {
        println("Permissions granted. Hourly polling is scheduled.")

        // Define constraints for the work, e.g., requires a network connection.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Create a periodic work request that runs once per hour.
        val periodicWorkRequest = PeriodicWorkRequestBuilder<DataCollectionWorker>(15, TimeUnit.MINUTES) // TODO: Change back to hours after done testing
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