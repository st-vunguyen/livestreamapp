package com.screenlive.app.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    
    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "overlay_channel"
        private const val CHANNEL_NAME = "Streaming Overlay"
        
        const val ACTION_START = "com.screenlive.app.overlay.START"
        const val ACTION_STOP = "com.screenlive.app.overlay.STOP"
        const val ACTION_UPDATE_METRICS = "com.screenlive.app.overlay.UPDATE_METRICS"
        
        const val EXTRA_BITRATE_KBPS = "bitrate_kbps"
        const val EXTRA_FPS = "fps"
        const val EXTRA_DROPPED_FRAMES = "dropped_frames"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
    }
    
    private var isOverlayActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "[PTL] OverlayService: onCreate()")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        
        when (action) {
            ACTION_START -> {
                if (!isOverlayActive) {
                    startForegroundWithNotification()
                    OverlayController.start(this)
                    
                    OverlayController.onStopRequested = {
                        Log.i(TAG, "[PTL] OverlayService: Stop requested from overlay tap")
                        // Call MainActivity static method to stop stream
                        com.screenlive.app.MainActivity.stopStreamFromOverlay()
                    }
                    
                    isOverlayActive = true
                    Log.i(TAG, "[PTL] OverlayService: Started (overlay visible)")
                }
            }
            
            ACTION_UPDATE_METRICS -> {
                val bitrateKbps = intent?.getIntExtra(EXTRA_BITRATE_KBPS, 0) ?: 0
                val fps = intent?.getIntExtra(EXTRA_FPS, 0) ?: 0
                val droppedFrames = intent?.getIntExtra(EXTRA_DROPPED_FRAMES, 0) ?: 0
                val elapsedMs = intent?.getLongExtra(EXTRA_ELAPSED_MS, 0L) ?: 0L
                
                OverlayController.setMetrics(bitrateKbps, fps, droppedFrames, elapsedMs)
            }
            
            ACTION_STOP -> {
                if (isOverlayActive) {
                    OverlayController.stop(this)
                    isOverlayActive = false
                    Log.i(TAG, "[PTL] OverlayService: Stopped (overlay hidden)")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (isOverlayActive) {
            OverlayController.stop(this)
            isOverlayActive = false
        }
        Log.i(TAG, "[PTL] OverlayService: onDestroy()")
        super.onDestroy()
    }
    
    private fun startForegroundWithNotification() {
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        Log.i(TAG, "[PTL] OverlayService: Foreground service active")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Streaming overlay controls"
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
            .setContentText("Streaming controls active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
