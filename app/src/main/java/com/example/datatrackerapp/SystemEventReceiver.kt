package com.example.datatrackerapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SystemEventReceiver : BroadcastReceiver() {

    private val emailSender = EmailSender()
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
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
        scope.launch {
            emailSender.sendEmail(subject, body)
        }
    }
}