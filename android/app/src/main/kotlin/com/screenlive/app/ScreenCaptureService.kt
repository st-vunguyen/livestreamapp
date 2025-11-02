package com.screenlive.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {
    
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val CHANNEL_NAME = "Screen Capture"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
    }
    
    // [PTL] CRITICAL: Prevent CPU sleep and WiFi power-save during streaming (PiP fix)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // [PTL] Acquire WakeLock to prevent CPU sleep during streaming
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "screenlive:wakelock").apply {
            setReferenceCounted(false)
            acquire()
            android.util.Log.i(TAG, "[PTL] ✓ WakeLock acquired (prevent CPU sleep during PiP)")
        }
        
        // [PTL] Acquire WifiLock to prevent WiFi power-save mode
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "screenlive:wifilock").apply {
            setReferenceCounted(false)
            acquire()
            android.util.Log.i(TAG, "[PTL] ✓ WifiLock acquired (HIGH_PERF mode, prevent 6-8s disconnect)")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        
        android.util.Log.i(TAG, "⏳ Starting FGS with mediaProjection|microphone type (Android 14 compliant)...")
        
        // CRITICAL: Must call startForeground with MEDIA_PROJECTION type BEFORE getMediaProjection()
        // Android 14 enforces this at system level regardless of targetSdk
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or 
                   ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        
        startForeground(NOTIFICATION_ID, notification, type)
        
        android.util.Log.i(TAG, "✅ FGS active with correct type - ready for MediaProjection")
        
        // Send broadcast to signal FGS is ready for MediaProjection
        // CRITICAL: Use setPackage() to ensure delivery to MainActivity
        android.util.Log.i(TAG, "[PTL] Sending FGS_READY broadcast...")
        val broadcastIntent = Intent("com.screenlive.app.FGS_READY").apply {
            setPackage(applicationContext.packageName)
        }
        applicationContext.sendBroadcast(broadcastIntent)
        android.util.Log.i(TAG, "[PTL] FGS_READY broadcast sent with package=${applicationContext.packageName}!")
        
        // Service keeps running to maintain app alive during streaming
        // Actual MediaProjection creation happens in RootEncoderService after FGS is active
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen capture notification"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Live")
            .setContentText("Screen capture is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        // [PTL] Release locks to prevent battery drain after streaming stops
        wifiLock?.release()
        wakeLock?.release()
        android.util.Log.i(TAG, "[PTL] ✓ WakeLock + WifiLock released")
        super.onDestroy()
    }
}
