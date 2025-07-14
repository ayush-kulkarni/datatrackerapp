package com.example.datatrackerapp

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.concurrent.TimeUnit

object UsageStatsPoller {
    fun getForegroundApp(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        // Check the last 10 seconds for events
        val startTime = endTime - TimeUnit.SECONDS.toMillis(10)

        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        if (stats != null && stats.isNotEmpty()) {
            return stats.maxByOrNull { it.lastTimeUsed }?.packageName
        }
        return null
    }
}