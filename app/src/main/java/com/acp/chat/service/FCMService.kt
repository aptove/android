package com.acp.chat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.acp.chat.BuildConfig
import com.acp.chat.MainActivity
import com.acp.chat.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging service for handling push notifications.
 * 
 * Receives notifications from the push relay when the bridge has
 * new agent activity while the app is disconnected.
 */
class FCMService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
        private const val CHANNEL_ID = "agent_activity"
        private const val CHANNEL_NAME = "Agent Activity"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Called when a new FCM token is generated.
     * This happens on first app start and when the token is refreshed.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "📲 New FCM token received: ${token.take(16)}...")
        
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "🔐 BRUNO TOKEN - Android FCM: $token")
            println("🔐 BRUNO TOKEN - Android FCM: $token")
        }
        
        // Store the token for later registration with bridge
        PushTokenManager.setToken(this, token)
    }

    /**
     * Called when a push notification is received while the app is in the foreground.
     * For background messages, the system handles display automatically.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "📬 Push notification received from: ${message.from}")
        
        val title = message.notification?.title ?: message.data["title"] ?: "Agent Activity"
        val body = message.notification?.body ?: message.data["body"] ?: "Your agent has new activity"
        
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when your agent has new activity"
                enableVibration(true)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

/**
 * Simple helper to store/retrieve the FCM token using SharedPreferences.
 */
object PushTokenManager {
    private const val PREFS_NAME = "push_prefs"
    private const val KEY_FCM_TOKEN = "fcm_token"

    fun setToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FCM_TOKEN, token)
            .apply()
        Log.d("PushTokenManager", "📲 FCM token stored")
    }

    fun getToken(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FCM_TOKEN, null)
    }
}
