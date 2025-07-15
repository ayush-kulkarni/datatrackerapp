package com.example.datatrackerapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * A BroadcastReceiver that listens for various system events and sends an email notification
 * when one of these events occurs.
 *
 * This receiver is registered in the AndroidManifest.xml to listen for system-wide broadcasts.
 */
class SystemEventReceiver : BroadcastReceiver() {

    private val emailSender = EmailSender()
    // CoroutineScope for launching background tasks (sending email) off the main thread.
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * This method is called when the BroadcastReceiver is receiving an Intent broadcast.
     */
    override fun onReceive(context: Context, intent: Intent) {
        // Get the action of the received intent. If it's null, do nothing.
        val action = intent.action ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        var subject = "System Event"
        val body = "Event: $action at $time"

        when (action) {
            Intent.ACTION_SCREEN_ON -> subject = "Screen ON"
            Intent.ACTION_SCREEN_OFF -> subject = "Screen OFF"
            Intent.ACTION_USER_PRESENT -> subject = "User Present (Unlocked)"
            Intent.ACTION_BOOT_COMPLETED -> subject = "Device Rebooted"
            Intent.ACTION_BATTERY_LOW -> subject = "Battery Low"
            Intent.ACTION_BATTERY_CHANGED -> subject = "Battery Status Changed"
        }
        // Launch a coroutine in the IO dispatcher to send the email in the background.
        scope.launch {
            emailSender.sendEmail(subject, body)
        }
    }
}