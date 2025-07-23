package com.example.datatrackerapp

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import java.util.concurrent.TimeUnit
import androidx.core.content.edit

/**
 * Main entry point of the application.
 * Handles user authentication, permission requests, and service initialization.
 */

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView

    // Launcher for the Google Sign-In flow.
    private val googleSignInLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    Log.d("MainActivity", "Google Sign-In successful for ${account.email}")
                    checkRuntimePermissions(account)
                } catch (e: ApiException) {
                    Log.e("MainActivity", "Google Sign-In failed with status code: ${e.statusCode}")
                    "Google Sign-In failed. Error code: ${e.statusCode}".also { statusTextView.text = it }
                }
            } else {
                "Google Sign-In was cancelled by the user.".also { statusTextView.text = it }
            }
        }

    // Launcher for standard runtime permissions (Location, Phone, etc.).
    private val requestPermissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (permissions.values.all { it } && account != null) {
                startServices(account)
            } else {
                "Permissions denied. App cannot run.".also { statusTextView.text = it }
                Log.e("MainActivity", "Not all runtime permissions were granted.")
            }
        }

    /**
     * Called when the activity is first created.
     * Sets up the UI and initiates the Google Sign-In process.
     * @param savedInstanceState If the activity is being re-initialized after previously being shut down then this Bundle contains the data it most recently supplied in onSaveInstanceState(Bundle).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusTextView = findViewById(R.id.status_textview)
        requestGoogleSignIn()
    }

    /**
     * Initiates the Google Sign-In flow.
     * Checks if a user is already signed in.
     * If not, launches the Google Sign-In intent.
     */
    private fun requestGoogleSignIn() {
        "Requesting Google Sign-In...".also { statusTextView.text = it }
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        val signInClient = GoogleSignIn.getClient(this, gso)
        val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (lastSignedInAccount == null) {
            googleSignInLauncher.launch(signInClient.signInIntent)
        } else {
            Log.d("MainActivity", "Already signed in as ${lastSignedInAccount.email}. Checking other permissions.")
            checkRuntimePermissions(lastSignedInAccount)
        }
    }

    /**
     * Checks for standard runtime permissions after Google Sign-In is complete.
     * Verifies Usage Stats permission.
     * Requests location, phone state, and notification (Android Tiramisu+) permissions.
     * @param account The signed-in Google account.
     */
    private fun checkRuntimePermissions(account: GoogleSignInAccount) {
        if (!hasUsageStatsPermission()) {
            Toast.makeText(this, "Please grant Usage Access permission", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            "Waiting for Usage Access permission...\nPlease restart the app after granting.".also { statusTextView.text = it }
            return
        }

        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            startServices(account)
        } else {
            requestPermissionLauncher.launch(permissionsToRequest)
        }
    }

    /**
     * Starts the necessary background services after all permissions are granted.
     * Saves the Google account name to SharedPreferences.
     * Schedules the hourly data collection worker.
     * Starts the tight polling foreground service.
     * @param account The signed-in Google account.
     */
    private fun startServices(account: GoogleSignInAccount) {
        val accountName = account.account?.name ?: return

        // Save the account name to SharedPreferences. This is now the single source of truth.
        val prefs: SharedPreferences = getSharedPreferences("DriveUploadPrefs", Context.MODE_PRIVATE)
        prefs.edit { putString("ACCOUNT_NAME", accountName) }
        Log.d("MainActivity", "Saved account name to SharedPreferences.")

        "Starting services for ${account.email}".also { statusTextView.text = it }
        scheduleHourlyUploadWork() // No longer needs accountName
        startTightPollingService(accountName)
//        triggerImmediateUploadForTesting() // No longer needs accountName
        "All services active.".also { statusTextView.text = it }
    }

    /**
     * Schedules a periodic background worker to collect data and upload it hourly.
     * Configures constraints for the worker (e.g., unmetered network).
     * Enqueues the work request with WorkManager.
     */
    private fun scheduleHourlyUploadWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<DataCollectionWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "HourlyDataPollAndUpload", ExistingPeriodicWorkPolicy.KEEP, workRequest
        )
        Log.d("MainActivity", "Regular hourly upload worker scheduled.")
    }

    /**
     * Triggers an immediate one-time data upload for testing purposes.
     * This function is typically commented out in production builds.
     */
//    private fun triggerImmediateUploadForTesting() { // Removed accountName parameter
//        Log.d("MainActivity", "Triggering immediate one-time upload for testing.")
//        val constraints = Constraints.Builder()
//            .setRequiredNetworkType(NetworkType.CONNECTED)
//            .build()
//
//        val testUploadWorkRequest = OneTimeWorkRequestBuilder<DataCollectionWorker>()
//            .setConstraints(constraints)
//            .build()
//        WorkManager.getInstance(this).enqueue(testUploadWorkRequest)
//    }

    /**
     * Starts the foreground service for tight polling, passing the account name.
     * Creates an intent for the TightPollingService.
     * Passes the Google account name as an extra to the service.
     * @param accountName The name of the signed-in Google account.
     */
    private fun startTightPollingService(accountName: String) {
        val serviceIntent = Intent(this, TightPollingService::class.java).apply {
            putExtra("ACCOUNT_NAME", accountName)
        }
        startForegroundService(serviceIntent)
        Log.d("MainActivity", "Tight polling foreground service started.")
    }

    /**
     * Checks if the app has been granted the PACKAGE_USAGE_STATS permission.
     * This permission is required to access app usage data.
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
}