package com.screenlive.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import android.app.PictureInPictureParams
import android.util.Rational
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * Main activity for ScreenLive app
 * Sets up MethodChannels for native communication and handles permissions
 */
class MainActivity: FlutterActivity() {
    private val captureChannelName = "com.screenlive.app/capture"
    private val publishChannelName = "com.screenlive.app/publish"
    private val metricsChannelName = "com.screenlive.app/metrics"
    private val permissionsChannelName = "com.screenlive.app/permissions"
    private val rootEncoderChannelName = "com.screenlive.app/rootEncoder"  // NEW MVP channel

    private var captureHandler: CaptureHandler? = null
    private var publishHandler: PublishHandler? = null
    private var metricsHandler: MetricsHandler? = null
    private var permissionsHelper: PermissionsHelper? = null
    private var rootEncoderService: RootEncoderService? = null  // NEW MVP service
    private var fgsReadyReceiver: BroadcastReceiver? = null  // FGS_READY receiver

    companion object {
        private const val REQUEST_CODE_PROJECTION = 1001
        private const val REQUEST_CODE_AUDIO = 1002
        private const val ACTION_FGS_READY = "com.screenlive.app.FGS_READY"
        
        // Static reference for overlay to call stop stream
        @Volatile
        private var currentInstance: MainActivity? = null
        
        fun stopStreamFromOverlay() {
            currentInstance?.let { activity ->
                activity.rootEncoderService?.let { service ->
                    android.util.Log.i("MainActivity", "[PTL] stopStreamFromOverlay called")
                    activity.runOnUiThread {
                        service.stopFromOverlay(object : io.flutter.plugin.common.MethodChannel.Result {
                            override fun success(result: Any?) {
                                android.util.Log.i("MainActivity", "[PTL] Stream stopped from overlay")
                                
                                // Notify Flutter
                                service.methodChannel?.invokeMethod("stopStreamCompleted", null)
                                
                                // Stop overlay service
                                val stopIntent = android.content.Intent(activity, com.screenlive.app.overlay.OverlayService::class.java)
                                stopIntent.action = "com.screenlive.app.overlay.STOP"
                                activity.startService(stopIntent)
                            }
                            override fun error(code: String, msg: String?, details: Any?) {
                                android.util.Log.e("MainActivity", "[PTL] Failed to stop: $msg")
                            }
                            override fun notImplemented() {}
                        })
                    }
                } ?: android.util.Log.w("MainActivity", "[PTL] RootEncoderService is null")
            } ?: android.util.Log.w("MainActivity", "[PTL] MainActivity instance is null")
        }
        
        // [PTL] Audio control methods for overlay buttons
        fun toggleMicFromOverlay(): Boolean {
            currentInstance?.let { activity ->
                activity.rootEncoderService?.let { service ->
                    val result = service.toggleMic()
                    android.util.Log.i("MainActivity", "[PTL] toggleMicFromOverlay -> $result")
                    return result
                }
            }
            android.util.Log.w("MainActivity", "[PTL] toggleMicFromOverlay: instance or service is null")
            return false
        }
        
        fun toggleGameAudioFromOverlay(): Boolean {
            currentInstance?.let { activity ->
                activity.rootEncoderService?.let { service ->
                    val result = service.toggleGameAudio()
                    android.util.Log.i("MainActivity", "[PTL] toggleGameAudioFromOverlay -> $result")
                    return result
                }
            }
            android.util.Log.w("MainActivity", "[PTL] toggleGameAudioFromOverlay: instance or service is null")
            return false
        }
        
        fun isMicEnabledFromOverlay(): Boolean {
            return currentInstance?.rootEncoderService?.isMicEnabled() ?: true
        }
        
        fun isGameAudioEnabledFromOverlay(): Boolean {
            return currentInstance?.rootEncoderService?.isGameAudioEnabled() ?: true
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        android.util.Log.i("[PTL] MainActivity", "configureFlutterEngine() called - registering channels...")
        
        // Set current instance for overlay callback
        currentInstance = this

        // Initialize handlers
        captureHandler = CaptureHandler(this)
        publishHandler = PublishHandler(this, captureHandler!!)
        metricsHandler = MetricsHandler()
        permissionsHelper = PermissionsHelper(this)
        rootEncoderService = RootEncoderService(this)  // NEW MVP service
        
        android.util.Log.i("[PTL] MainActivity", "RootEncoderService instance created")
        
        // Link handlers
        captureHandler?.setMetricsHandler(metricsHandler)

        // Setup MethodChannels
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, captureChannelName).apply {
            setMethodCallHandler { call, result ->
                captureHandler?.handle(call, result)
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, publishChannelName).apply {
            setMethodCallHandler { call, result ->
                publishHandler?.handle(call, result)
            }
        }
        
        // NEW MVP MethodChannel for RootEncoder
        android.util.Log.i("[PTL] MainActivity", "Registering MethodChannel: $rootEncoderChannelName")
        val rootEncoderChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, rootEncoderChannelName)
        rootEncoderChannel.apply {
            setMethodCallHandler { call, result ->
                android.util.Log.i("[PTL] MainActivity", "MethodCallHandler invoked: method=${call.method}")
                
                // Register FGS_READY receiver BEFORE handling "start" method
                if (call.method == "start") {
                    android.util.Log.i("[PTL] MainActivity", "Registering FGS_READY receiver before starting stream...")
                    registerFgsReadyReceiver()
                }
                
                rootEncoderService?.handle(call, result)
            }
        }
        // Pass channel to service for callbacks
        rootEncoderService?.setMethodChannel(rootEncoderChannel)
        android.util.Log.i("[PTL] MainActivity", "RootEncoder MethodChannel registered successfully")
        
        // Setup Permissions MethodChannel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, permissionsChannelName).apply {
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "requestPermissions" -> {
                        permissionsHelper?.requestAllPermissions()
                        result.success(null)
                    }
                    "hasAllPermissions" -> {
                        val hasAll = permissionsHelper?.hasAllPermissions() ?: false
                        result.success(hasAll)
                    }
                    "getPermissionStatus" -> {
                        val status = permissionsHelper?.getPermissionStatus() ?: emptyMap()
                        result.success(status)
                    }
                    "checkBatteryOptimization" -> {
                        checkAndRequestBatteryOptimization()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
        }

        // Setup EventChannel for metrics streaming
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, metricsChannelName).apply {
            setStreamHandler(metricsHandler)
        }

        // Link handlers
        captureHandler?.setMetricsHandler(metricsHandler)
        publishHandler?.setMetricsHandler(metricsHandler)
        
        // [FIX] Check battery optimization on startup to prevent 5-min OEM kill
        checkAndRequestBatteryOptimization()
        
        // Request permissions on app start
        android.util.Log.d("MainActivity", "Requesting initial permissions")
        permissionsHelper?.requestAllPermissions()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PermissionsHelper.REQUEST_CODE_ALL_PERMISSIONS -> {
                val granted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                android.util.Log.d("MainActivity", "Permissions result: granted=$granted")
                
                // Log individual permission results
                permissions.forEachIndexed { index, permission ->
                    val result = if (grantResults[index] == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
                    android.util.Log.d("MainActivity", "  $permission: $result")
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        android.util.Log.d("MainActivity", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        
        when (requestCode) {
            REQUEST_CODE_PROJECTION -> {
                // CRITICAL: Only use RootEncoderService (Android 14 compliant with 800ms delay)
                // DO NOT use CaptureHandler (150ms delay is too short, causes SecurityException)
                rootEncoderService?.onMediaProjectionResult(resultCode, data)
                
                // OLD DEPRECATED PATH - commented out to avoid double MediaProjection creation
                // if (resultCode == Activity.RESULT_OK && data != null) {
                //     android.util.Log.d("MainActivity", "MediaProjection permission granted")
                //     captureHandler?.onMediaProjectionResult(resultCode, data)
                // } else {
                //     android.util.Log.w("MainActivity", "MediaProjection permission denied")
                //     captureHandler?.onMediaProjectionDenied()
                // }
            }
            REQUEST_CODE_AUDIO -> {
                android.util.Log.d("MainActivity", "Audio permission result: $resultCode")
                // Audio permission is handled by Android system callback
            }
        }
    }

    /**
     * [FIX] Check and request battery optimization whitelist to prevent OEM killing FGS
     * Critical for Meizu/Flyme, Xiaomi/MIUI, Oppo/ColorOS which aggressively kill background apps
     */
    private fun checkAndRequestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.w("MainActivity", "[FIX] App NOT whitelisted from battery optimization - requesting...")
                Log.w("MainActivity", "[FIX] Without this, Meizu/MIUI will kill FGS after ~5 minutes")
                
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Log.i("MainActivity", "[FIX] Battery optimization whitelist dialog shown")
                } catch (e: Exception) {
                    Log.e("MainActivity", "[FIX] Failed to request battery whitelist: ${e.message}")
                    // Fallback: Open battery settings
                    try {
                        val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(settingsIntent)
                        Log.i("MainActivity", "[FIX] Opened battery optimization settings (manual whitelist)")
                    } catch (e2: Exception) {
                        Log.e("MainActivity", "[FIX] Cannot open battery settings: ${e2.message}")
                    }
                }
            } else {
                Log.i("MainActivity", "[FIX] ✓ App already whitelisted from battery optimization")
            }
        }
    }

    override fun onDestroy() {
        currentInstance = null  // Clear reference
        unregisterFgsReadyReceiver()
        captureHandler?.cleanup()
        publishHandler?.cleanupAll()
        metricsHandler?.cleanup()
        rootEncoderService?.cleanupAll()  // [PTL] Clean up broadcast receivers
        super.onDestroy()
    }

    // ========== Picture-in-Picture Mode (Android 8.0+) ==========
    
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        
        // [PTL] When user presses HOME while streaming, just finish activity
        // Overlay bubble stays visible, no PiP window needed
        val isStreaming = rootEncoderService?.isStreaming ?: false
        
        if (isStreaming) {
            android.util.Log.i("[PTL] MainActivity", "User switching apps while streaming → finishing activity, overlay stays visible")
            // moveTaskToBack(true) // Keep in recents but move to background
            // OR finish() to completely remove from screen
            moveTaskToBack(true) // Less aggressive - keeps in recents
        }
    }
    
    private fun createPipParams(): PictureInPictureParams {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Smaller aspect ratio for compact PiP window (more square = smaller)
            val aspectRatio = Rational(4, 3)  // 4:3 aspect ratio for smaller window
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
            
            // Android 12+ supports auto-enter PiP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
            }
            
            builder.build()
        } else {
            throw IllegalStateException("PiP requires API 26+")
        }
    }
    
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        
        // [PTL] CRITICAL: DO NOT stop encoders during PiP - service continues streaming
        if (isInPictureInPictureMode) {
            android.util.Log.i("[PTL] MainActivity", "✅ Entered PiP mode — streaming continues (encoders stay active)")
        } else {
            android.util.Log.i("[PTL] MainActivity", "⬅️ Exited PiP mode — back to normal")
        }
        // Note: MediaProjection, VirtualDisplay, encoders all remain active
        // Only UI transitions to compact PiP window - no lifecycle changes to streaming
    }

    // ========== FGS_READY Broadcast Handling ==========
    
    private fun registerFgsReadyReceiver() {
        if (fgsReadyReceiver != null) {
            Log.i("[PTL] MainActivity", "FGS_READY receiver already registered")
            return
        }
        
        fgsReadyReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                Log.i("[PTL] MainActivity", "✅ FGS_READY received → proceed to MediaProjection pipeline")
                rootEncoderService?.onFgsReady()
            }
        }
        
        // Android 13+: Must specify RECEIVER_NOT_EXPORTED flag
        ContextCompat.registerReceiver(
            this,
            fgsReadyReceiver,
            IntentFilter(ACTION_FGS_READY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.i("[PTL] MainActivity", "FGS_READY receiver registered (RECEIVER_NOT_EXPORTED)")
    }
    
    private fun unregisterFgsReadyReceiver() {
        fgsReadyReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.i("[PTL] MainActivity", "FGS_READY receiver unregistered")
            } catch (e: Exception) {
                Log.w("[PTL] MainActivity", "Failed to unregister FGS_READY receiver: ${e.message}")
            }
        }
        fgsReadyReceiver = null
    }
}
