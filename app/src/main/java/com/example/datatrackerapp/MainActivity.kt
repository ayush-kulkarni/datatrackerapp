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
 * The main activity of the application.
 * Handles user authentication (Google Sign-In) and requests necessary runtime permissions.
 * Once authenticated and permissions are granted, it schedules background tasks for data collection and upload.
 *
 * Key functions:
 * - `onCreate`: Initializes the activity, sets up UI elements, and starts the Google Sign-In process.
 * - `requestGoogleSignIn`: Initiates the Google Sign-In flow, handling new sign-ins or using existing ones.
 * - `checkRuntimePermissions`: Checks for required runtime permissions (Location, Phone State, Usage Stats) after successful Google Sign-In.
 * - `startServices`: If all permissions are granted, this function saves the Google account name and starts background services for data collection and uploading.
 * - `scheduleHourlyUploadWork`: Schedules a periodic background worker (using WorkManager) to collect and upload data hourly.
 * - `startTightPollingService`: Starts a foreground service for more frequent data polling.
 * - `hasUsageStatsPermission`: Checks if the app has the "Usage Access" permission, which is required for app usage tracking.
 *
 * ActivityResultLaunchers:
 * - `googleSignInLauncher`: Handles the result of the Google Sign-In intent.
 * - `requestPermissionLauncher`: Handles the result of runtime permission requests.
 *
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
                    // After successful sign-in, check other permissions.
                    checkRuntimePermissions(account)
                } catch (e: ApiException) {
                    Log.e("MainActivity", "Google Sign-In failed with status code: ${e.statusCode}")
                    statusTextView.text = "Google Sign-In failed. Error code: ${e.statusCode}"
                }
            } else {
                statusTextView.text = "Google Sign-In was cancelled by the user."
            }
        }

    // Launcher for standard runtime permissions (Location, Phone, etc.).
    private val requestPermissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (permissions.values.all { it } && account != null) {
                // If all permissions are granted, start the background services.
                startServices(account)
            } else {
                statusTextView.text = "Permissions denied. App cannot run."
                Log.e("MainActivity", "Not all runtime permissions were granted.")
            }
        }

    /**
     * - Initializes the activity.
     * - Sets the content view and finds the status TextView.
     * - Initiates the Google Sign-In process.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusTextView = findViewById(R.id.status_textview)
        // On app start, immediately begin the Google Sign-In process.
        requestGoogleSignIn()
    }

    /**
     * - Updates the status TextView.
     * - Configures Google Sign-In options, requesting email and Drive file scope.
     * - If the user is not already signed in, launches the Google Sign-In intent.
     * - Otherwise, proceeds to check other runtime permissions.
     */
    private fun requestGoogleSignIn() {
        statusTextView.text = "Requesting Google Sign-In..."
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE)) // Request permission for Drive
            .build()
        val signInClient = GoogleSignIn.getClient(this, gso)
        // Check if a user is already signed in to avoid showing the dialog every time.
        val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (lastSignedInAccount == null) {
            googleSignInLauncher.launch(signInClient.signInIntent)
        } else {
            Log.d("MainActivity", "Already signed in as ${lastSignedInAccount.email}. Checking other permissions.")
            checkRuntimePermissions(lastSignedInAccount)
        }
    }

    /**
     * - Checks if Usage Stats permission is granted. If not, prompts the user to grant it and returns.
     * - Gathers a list of required runtime permissions (Location, Phone State, and Post Notifications for Android Tiramisu+).
     * - Filters out already granted permissions.
     * - If all permissions are granted, starts the background services. Otherwise, requests the missing permissions.
     */
    private fun checkRuntimePermissions(account: GoogleSignInAccount) {
        if (!hasUsageStatsPermission()) {
            Toast.makeText(this, "Please grant Usage Access permission", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            statusTextView.text = "Waiting for Usage Access permission...\nPlease restart the app after granting."
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
     * - Retrieves the Google account name.
     * - Saves the account name to SharedPreferences for other components to access.
     * - Updates the status TextView.
     * - Schedules the hourly data upload worker and starts the tight polling service.
     */
    private fun startServices(account: GoogleSignInAccount) {
        val accountName = account.account?.name ?: run {
            statusTextView.text = "Could not get account name."
            return
        }

        // Save the account name to SharedPreferences so other components like
        // the BroadcastReceiver can access it.
        val prefs: SharedPreferences = getSharedPreferences("DriveUploadPrefs", Context.MODE_PRIVATE)
        prefs.edit { putString("ACCOUNT_NAME", accountName) }
        Log.d("MainActivity", "Saved account name to SharedPreferences.")

        statusTextView.text = "Starting services for ${account.email}"
        scheduleHourlyUploadWork(accountName)
        startTightPollingService(accountName)
//        triggerImmediateUploadForTesting(accountName)
        statusTextView.text = "All services active."
    }

    /**
     * - Defines constraints for the worker (e.g., unmetered network).
     * - Creates input data containing the Google account name.
     * - Builds a periodic work request for `DataCollectionWorker` to run hourly.
     * - Enqueues the unique periodic work request with WorkManager.
     */
    private fun scheduleHourlyUploadWork(accountName: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val inputData = workDataOf("ACCOUNT_NAME" to accountName)

        val workRequest = PeriodicWorkRequestBuilder<DataCollectionWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "HourlyDataPollAndUpload", ExistingPeriodicWorkPolicy.KEEP, workRequest
        )
        Log.d("MainActivity", "Regular hourly upload worker scheduled for account: $accountName")
    }

    /**
     * This function is commented out but was intended for testing immediate uploads.
     */
//    private fun triggerImmediateUploadForTesting(accountName: String) {
//        Log.d("MainActivity", "Triggering immediate one-time upload for testing for account: $accountName")
//        val constraints = Constraints.Builder()
//            .setRequiredNetworkType(NetworkType.CONNECTED)
//            .build()
//
//        val inputData = workDataOf("ACCOUNT_NAME" to accountName)
//
//        val testUploadWorkRequest = OneTimeWorkRequestBuilder<DataCollectionWorker>()
//            .setConstraints(constraints)
//            .setInputData(inputData)
//            .build()
//        WorkManager.getInstance(this).enqueue(testUploadWorkRequest)
//    }

    /**
     * - Creates an Intent for `TightPollingService`.
     * - Puts the Google account name as an extra in the Intent.
     * - Starts the foreground service.
     */
    private fun startTightPollingService(accountName: String) {
        val serviceIntent = Intent(this, TightPollingService::class.java).apply {
            putExtra("ACCOUNT_NAME", accountName)
        }
        startForegroundService(serviceIntent)
        Log.d("MainActivity", "Tight polling foreground service started.")
    }

    /**
     * - Gets the AppOpsManager system service.
     * - Checks the operation status for GET_USAGE_STATS for the current app.
     * - Returns true if the permission is allowed, false otherwise.
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