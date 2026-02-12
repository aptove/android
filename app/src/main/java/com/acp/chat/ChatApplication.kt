package com.acp.chat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.acp.chat.service.PushTokenManager
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ChatApplication : Application() {

    companion object {
        private const val TAG = "ChatApplication"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        fetchFCMToken()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "agent_activity",
                "Agent Activity",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when your agent has new activity"
                enableVibration(true)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun fetchFCMToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "📲 FCM token fetch failed", task.exception)
                return@addOnCompleteListener
            }

            val token = task.result
            Log.d(TAG, "📲 FCM token: ${token.take(16)}...")
            
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "🔐 BRUNO TOKEN - Android FCM: $token")
                println("🔐 BRUNO TOKEN - Android FCM: $token")
            }
            
            PushTokenManager.setToken(this, token)
        }
    }
}
