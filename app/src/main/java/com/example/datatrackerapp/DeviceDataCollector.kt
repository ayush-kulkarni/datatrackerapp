package com.example.datatrackerapp

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.util.Calendar
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AppUsageEvent(
    val packageName: String?,
    val eventType: Int,
    val timestamp: Long
)

class DeviceDataCollector(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun getUsageStats(): String {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startTime = cal.timeInMillis
        val endTime = System.currentTimeMillis()

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)

        // The list now holds your new data class
        val events = mutableListOf<AppUsageEvent>()

        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)

            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.ACTIVITY_PAUSED) {

                // Create an instance of your data class to snapshot the data
                val appEvent = AppUsageEvent(
                    packageName = event.packageName,
                    eventType = event.eventType,
                    timestamp = event.timeStamp
                )
                events.add(appEvent)
            }
        }

        events.sortBy { it.timestamp }

        val result = StringBuilder()
        result.append("## App Usage Events (Today)\n")
        // The loop now uses your AppUsageEvent object
        events.forEach {
            result.append("- **Package:** ${it.packageName}, **Event:** ${getEventType(it.eventType)}, **Timestamp:** ${it.timestamp}\n")
        }

        return result.toString()
    }

    private fun getEventType(eventType: Int): String {
        return when (eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
            UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
            else -> "UNKNOWN_EVENT"
        }
    }

    fun getInstalledApps(): String {
        val result = StringBuilder("## Installed Applications\n")
        val packages = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        for (packageInfo in packages) {
            val appName = packageInfo.applicationInfo?.loadLabel(packageManager).toString()
            val packageName = packageInfo.packageName
            val versionName = packageInfo.versionName
            val installTime = packageInfo.firstInstallTime
            val lastUpdateTime = packageInfo.lastUpdateTime
            val installerPackageName = packageManager.getInstallSourceInfo(packageName).installingPackageName

            result.append("### $appName ($packageName)\n")
            result.append("- **Version:** $versionName\n")
            result.append("- **Install Time:** $installTime\n")
            result.append("- **Last Update Time:** $lastUpdateTime\n")
            result.append("- **Installer:** $installerPackageName\n")

            val permissions = packageInfo.requestedPermissions
            if (permissions != null) {
                result.append("- **Permissions:**\n")
                permissions.forEach { permission -> result.append("  - $permission\n") }
            }
        }
        return result.toString()
    }

    // TODO: Fix getCurrentLocation()
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): String = suspendCancellableCoroutine { continuation ->
        val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                val result = StringBuilder("## Current Location\n")
                if (location != null) {
                    result.append("- **Latitude:** ${location.latitude}\n")
                        .append("- **Longitude:** ${location.longitude}\n")
                        .append("- **Accuracy:** ${location.accuracy} meters\n")
                } else {
                    result.append("- Location not available.\n")
                }
                continuation.resume(result.toString())
            }
            .addOnFailureListener { exception ->
                continuation.resumeWithException(exception)
            }
    }

    // TODO: Fix getSensorData()
    fun getSensorData(): String {
        val result = StringBuilder("## Sensor Data\n")
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // ... add other sensors like gyroscope, etc.

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null) {
                    val values = event.values.joinToString(", ")
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> result.append("- **Accelerometer:** $values\n")
                    }
                } else {
                    result.append("- Sensor not available.\n")
                }
                sensorManager.unregisterListener(this)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        return result.toString()
    }

    @SuppressLint("MissingPermission")
    fun getCellInfo(): String {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val result = StringBuilder("## Cell Tower Information\n")
        val allCellInfo: List<CellInfo> = telephonyManager.allCellInfo
        if (allCellInfo.isNotEmpty()) {
            for (cellInfo in allCellInfo) {
                when (cellInfo) {
                    is CellInfoGsm -> {
                        result.append("- **GSM:** CID=${cellInfo.cellIdentity.cid}, LAC=${cellInfo.cellIdentity.lac}, Signal Strength=${cellInfo.cellSignalStrength.dbm} dBm\n")
                    }
                    is CellInfoLte -> {
                        result.append("- **LTE:** CI=${cellInfo.cellIdentity.ci}, TAC=${cellInfo.cellIdentity.tac}, Signal Strength=${cellInfo.cellSignalStrength.dbm} dBm\n")
                    }
                    is CellInfoWcdma -> {
                        result.append("- **WCDMA:** CID=${cellInfo.cellIdentity.cid}, LAC=${cellInfo.cellIdentity.lac}, Signal Strength=${cellInfo.cellSignalStrength.dbm} dBm\n")
                    }
                }
            }
        } else {
            result.append("- No cell information available.\n")
        }
        return result.toString()
    }

    fun getSystemInfo(): String {
        val uptimeMillis = SystemClock.elapsedRealtime()
        val uptimeSeconds = uptimeMillis / 1000
        val uptimeMinutes = uptimeSeconds / 60
        val uptimeHours = uptimeMinutes / 60

        val batteryIntent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level: Int = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = level * 100 / scale.toFloat()
        val status: Int = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val result = StringBuilder("## System & Battery\n")
        result.append("- **Device Uptime:** $uptimeHours hours, ${uptimeMinutes % 60} minutes, ${uptimeSeconds % 60} seconds\n")
        result.append("- **Battery Level:** $batteryPct%\n")
        result.append("- **Charging Status:** ${if (isCharging) "Charging" else "Not Charging"}\n")

        return result.toString()
    }
}