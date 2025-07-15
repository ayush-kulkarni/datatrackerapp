package com.example.datatrackerapp

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Object responsible for polling usage statistics to determine the foreground application.
 * This is a singleton object, meaning there's only one instance of it in the application.
 */
object UsageStatsPoller {
    /**
     * Retrieves the package name of the application that was most recently in the foreground.
     *
     * @param context The application context, used to access system services.
     * @return The package name of the foreground app, or null if no app usage is found
     *         or if usage stats are not available.
     */
    fun getForegroundApp(context: Context): String? {
        // Get the UsageStatsManager system service
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // Define the time window for querying usage stats
        val endTime = System.currentTimeMillis()
        // Check the last 10 seconds for events
        val startTime = endTime - TimeUnit.SECONDS.toMillis(10)

        // Query for usage statistics within the defined time window
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        if (stats != null && stats.isNotEmpty()) {
            // Find the app with the most recent lastTimeUsed timestamp
            return stats.maxByOrNull { it.lastTimeUsed }?.packageName
        }
        return null
    }
}