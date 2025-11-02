package com.screenlive.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Handler for screen capture via MediaProjection (API-002)
 * Implements FEAT-002: Screen capture Android (MediaProjection)
 */
class CaptureHandler(private val activity: Activity) {
    private var mediaProjection: MediaProjection? = null
    private var metricsHandler: MetricsHandler? = null
    private var pendingResult: MethodChannel.Result? = null
    private var captureConfig: CaptureConfig? = null
    private var videoEncoder: VideoEncoder? = null
    private var virtualDisplay: VirtualDisplay? = null

    fun setMetricsHandler(handler: MetricsHandler?) {
        metricsHandler = handler
    }

    fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "requestPermission" -> requestPermission(call, result)
            "startAudio" -> startAudio(call, result)
            "stopCapture" -> stopCapture(result)
            "checkPermissions" -> checkPermissions(result)
            else -> result.notImplemented()
        }
    }

    private fun requestPermission(call: MethodCall, result: MethodChannel.Result) {
        val width = call.argument<Int>("width") ?: 1280
        val height = call.argument<Int>("height") ?: 720
        val fps = call.argument<Int>("fps") ?: 60

        captureConfig = CaptureConfig(width, height, fps)
        pendingResult = result

        android.util.Log.d("CaptureHandler", "Requesting MediaProjection permission")
        
        // Request MediaProjection permission FIRST (service will start AFTER user grants)
        val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        activity.startActivityForResult(intent, REQUEST_CODE_PROJECTION)
    }

    fun onMediaProjectionResult(resultCode: Int, data: Intent) {
        try {
            android.util.Log.d("CaptureHandler", "MediaProjection granted, starting foreground service")
            
            // Start service WITH the projection token (Android 14+ requirement)
            val serviceIntent = Intent(activity, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            activity.startForegroundService(serviceIntent)
            
            // Wait for service to start, then create MediaProjection
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                
                // Get MediaProjection
                val projection = projectionManager.getMediaProjection(resultCode, data)

                if (projection != null && captureConfig != null) {
                    android.util.Log.d("CaptureHandler", "MediaProjection created successfully")
                    
                    // Register callback FIRST (required for Android 14+ before any capture)
                    projection.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            android.util.Log.i("CaptureHandler", "MediaProjection stopped by system")
                            cleanup()
                        }
                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                    
                    // Now assign to field after callback registered
                    mediaProjection = projection
                    
                    // Initialize video encoder
                    val config = captureConfig!!
                    videoEncoder = VideoEncoder(config.width, config.height, config.fps, 6_000_000)
                    
                    if (videoEncoder?.start() == true) {
                        // Create VirtualDisplay and connect to encoder surface
                        val surface = videoEncoder?.getSurface()
                        if (surface != null) {
                            virtualDisplay = mediaProjection?.createVirtualDisplay(
                                "ScreenLive",
                                config.width,
                                config.height,
                                activity.resources.displayMetrics.densityDpi,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                surface,
                                null,
                                null
                            )
                            android.util.Log.i("CaptureHandler", "✓ VirtualDisplay created and connected to encoder")
                        }
                    }
                    
                    pendingResult?.success(true)
                    pendingResult = null
                } else {
                    android.util.Log.w("CaptureHandler", "MediaProjection failed")
                    pendingResult?.success(false)
                    pendingResult = null
                }
            }, 500)
        } catch (e: Exception) {
            android.util.Log.e("CaptureHandler", "Error in onMediaProjectionResult", e)
            pendingResult?.error("CAPTURE_ERROR", "Failed to start capture: ${e.message}", null)
            pendingResult = null
        }
    }

    fun onMediaProjectionDenied() {
        android.util.Log.w("CaptureHandler", "MediaProjection permission denied by user")
        pendingResult?.error("PERMISSION_DENIED", "MediaProjection permission denied", null)
        pendingResult = null
    }

    private fun startAudio(call: MethodCall, result: MethodChannel.Result) {
        val preferredSource = call.argument<String>("preferredSource") ?: "microphone"
        
        // TODO: Implement Audio Playback Capture for Android 10+ (API-003)
        // TODO: Fallback to microphone if game doesn't permit capture
        // For now, always return microphone
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && preferredSource == "game") {
            // TODO: Attempt AudioPlaybackCapture
            // If successful: result.success("game")
            // If denied: fallback to mic
            result.success("microphone")
        } else {
            result.success("microphone")
        }
    }

    private fun stopCapture(result: MethodChannel.Result) {
        virtualDisplay?.release()
        virtualDisplay = null
        
        videoEncoder?.stop()
        videoEncoder = null
        
        mediaProjection?.stop()
        mediaProjection = null
        captureConfig = null
        
        // Stop foreground service
        val serviceIntent = Intent(activity, ScreenCaptureService::class.java)
        activity.stopService(serviceIntent)
        
        android.util.Log.i("CaptureHandler", "Capture stopped")
        result.success(null)
    }

    private fun checkPermissions(result: MethodChannel.Result) {
        val permissions = mapOf(
            "mediaProjection" to (mediaProjection != null),
            "audioPlaybackCapture" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q),
            "microphone" to hasMicrophonePermission()
        )
        result.success(permissions)
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun getVideoEncoder(): VideoEncoder? = videoEncoder
    fun getMediaProjection(): MediaProjection? = mediaProjection

    fun cleanup() {
        try {
            android.util.Log.d("CaptureHandler", "Cleaning up MediaProjection")
            virtualDisplay?.release()
            videoEncoder?.stop()
            mediaProjection?.stop()
            mediaProjection = null
            virtualDisplay = null
            videoEncoder = null
        } catch (e: Exception) {
            android.util.Log.e("CaptureHandler", "Error during cleanup", e)
        }
    }

    private data class CaptureConfig(val width: Int, val height: Int, val fps: Int)

    companion object {
        private const val REQUEST_CODE_PROJECTION = 1001
        private const val REQUEST_CODE_AUDIO = 1002
    }
}
