package com.stresswatch.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class StressApp : Application() {

    companion object {
        const val CHANNEL_ID = "stress_monitor_channel"
        const val CHANNEL_ALERTS_ID = "stress_alerts_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitorChannel = NotificationChannel(
                CHANNEL_ID,
                "Stress Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background stress monitoring service"
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                "Stress Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical stress level alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(monitorChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }
}
