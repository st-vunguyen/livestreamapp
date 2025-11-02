package com.screenlive.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.ByteBuffer
import com.screenlive.app.overlay.OverlayService

/**
 * RootEncoder MVP - Minimal working RTMPS streaming
 * 
 * Fixed preset: 720p60, ~3.8Mbps, H.264 + AAC 128kbps
 * 
 * TODO:
 * - Audio Playback Capture (Android 10+)
 * - Foreground Service
 * - Reconnect/backoff
 * - Bitrate adaptation
 * - HUD metrics
 * - Secure storage
 */
class RootEncoderService(
    private val activity: Activity
) {
    companion object {
        private const val TAG = "RootEncoder"
        
        // [ADAPTIVE] Encoder profile data class
        data class EncProfile(
            val width: Int,
            val height: Int,
            val fps: Int,
            val bitrate: Int,      // bps
            val avcLevel: Int      // MediaCodecInfo.CodecProfileLevel.*
        )
        
        // [ADAPTIVE] Scale mode for encoder
        enum class ScaleMode {
            FIT,        // Giữ tỉ lệ máy, không crop (nét nhất, YouTube letterbox)
            FILL_16_9   // Ép về 16:9, crop nhẹ (không viền player)
        }
        
        // Audio: 48kHz Stereo 128kbps (YouTube recommended for standard quality)
        private const val AUDIO_SAMPLE_RATE = 48000  // 48kHz (hardware std)
        private const val AUDIO_BITRATE = 128_000    // 128 kbps AAC-LC (YouTube standard)
        private const val AUDIO_CHANNELS = 2         // Stereo
        private const val AUDIO_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
        
        const val REQUEST_CODE_PROJECTION = 1001
        
        // [ADAPTIVE] Align to multiple of 2/16 for encoder compatibility
        private fun align(v: Int, mult: Int = 2): Int = (v / mult) * mult
        
        /**
         * [ADAPTIVE] Get native screen size in landscape orientation
         * Works on API 30+ and older devices
         */
        private fun getNativeLandscapeSize(ctx: Context): Pair<Int, Int> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val bounds = wm.currentWindowMetrics.bounds
                val w = bounds.width()
                val h = bounds.height()
                if (w >= h) w to h else h to w  // Landscape
            } else {
                @Suppress("DEPRECATION")
                val dm = ctx.resources.displayMetrics
                val w = dm.widthPixels
                val h = dm.heightPixels
                if (w >= h) w to h else h to w  // Landscape
            }
        }
        
        /**
         * [ADAPTIVE] Compute encoder size based on native screen + mode
         * @param nativeW Native width (landscape)
         * @param nativeH Native height (landscape)
         * @param maxShortEdge Maximum short edge (default 1080p)
         * @param mode FIT (keep aspect) or FILL_16_9 (force 16:9)
         * @return Pair of (encoderWidth, encoderHeight)
         */
        private fun computeEncoderSize(
            nativeW: Int,
            nativeH: Int,
            maxShortEdge: Int = 1080,
            mode: ScaleMode = ScaleMode.FIT
        ): Pair<Int, Int> {
            val aspectRatio = nativeW.toFloat() / nativeH.toFloat()  // Landscape aspect
            
            return when (mode) {
                ScaleMode.FIT -> {
                    // Keep native aspect ratio, scale down to maxShortEdge
                    val targetH = kotlin.math.min(nativeH, maxShortEdge)
                    val targetW = (targetH * aspectRatio).toInt()
                    align(targetW, 2) to align(targetH, 2)
                }
                ScaleMode.FILL_16_9 -> {
                    // Force 16:9 aspect ratio (crop if needed)
                    val targetAR = 16f / 9f
                    val baseH = kotlin.math.min(nativeH, maxShortEdge)
                    val baseW = (baseH * targetAR).toInt()
                    align(baseW, 2) to align(baseH, 2)
                }
            }
        }
        
        /**
         * [ADAPTIVE] Suggest bitrate based on resolution and FPS
         * Following YouTube recommended upload encoding settings
         */
        private fun suggestBitrate(width: Int, height: Int, fps: Int): Int {
            val shortEdge = minOf(width, height)
            return when {
                shortEdge >= 1440 -> if (fps > 30) 20_000_000 else 16_000_000  // 1440p
                shortEdge >= 1080 -> if (fps > 30) 12_000_000 else 9_000_000   // 1080p
                shortEdge >= 720  -> if (fps > 30) 6_000_000 else 4_500_000    // 720p
                else              -> if (fps > 30) 3_000_000 else 2_000_000    // 540p-
            }
        }
        
        /**
         * [ADAPTIVE] Select AVC Level based on resolution and FPS
         */
        private fun avcLevelFor(width: Int, height: Int, fps: Int): Int {
            val pixelsPerSec = width * height * fps
            return when {
                // 1080p60 ~ 124M px/s → Level 5
                pixelsPerSec > 118_800_000 -> MediaCodecInfo.CodecProfileLevel.AVCLevel5
                // 1080p30 or 720p60 → Level 4.2
                fps > 30 || width >= 1920 || height >= 1080 ->
                    MediaCodecInfo.CodecProfileLevel.AVCLevel42
                // 720p30 and below → Level 4
                else -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            }
        }
    }
    
    // MethodChannel for callbacks to Flutter
    internal var methodChannel: MethodChannel? = null
    
    fun setMethodChannel(channel: MethodChannel) {
        methodChannel = channel
        Log.i(TAG, "[PTL] MethodChannel registered in RootEncoderService")
    }
    
    // [CRASH-RECOVERY] Mark flags when stream starts
    private fun markStartFlags() {
        prefs.manualStop = false
        prefs.wasStreaming = true
        PtlLogger.i(TAG, "[RECOVERY] Flags set: manualStop=false, wasStreaming=true")
    }
    
    // [CRASH-RECOVERY] Mark flags when stream stops
    private fun markStopFlags(manual: Boolean) {
        prefs.manualStop = manual
        if (manual) {
            // User stopped → clear wasStreaming so we don't auto-restart
            prefs.wasStreaming = false
            PtlLogger.i(TAG, "[RECOVERY] Manual stop: wasStreaming=false")
        } else {
            // Crash/reconnect-failed → keep wasStreaming=true for auto-restart
            PtlLogger.i(TAG, "[RECOVERY] Non-manual stop: wasStreaming kept true")
        }
    }
    
    // Public method for overlay to stop stream
    fun stopFromOverlay(result: MethodChannel.Result) {
        Log.i(TAG, "[PTL] stopFromOverlay() called")
        PtlLogger.i(TAG, "[FIX] User clicked STOP in overlay - setting manualStop=true")
        manualStop = true  // [FIX-CRITICAL] User explicitly stopped - don't reconnect
        markStopFlags(manual = true)  // [CRASH-RECOVERY] Persist flags
        stop(result)
    }
    
    // State
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    
    // [V2] CleanAudioMixer with drift correction, HPF, soft-limiter
    private var cleanAudioMixer: CleanAudioMixer? = null
    
    private var flvMuxer: MinimalFlvMuxer? = null
    private var rtmpsClient: com.screenlive.app.rtmp.MinimalRtmpsClient? = null
    
    internal var isStreaming = false  // [PTL] Made internal for PiP mode detection in MainActivity
    @Volatile private var isEncoding = false  // [PTL FIX] Track if encoding loops are already running
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var encodingJob: Job? = null
    private var reconnectJob: Job? = null  // [FIX] Track reconnect coroutine to cancel on manual stop

    @Volatile private var rtmpsDisconnectHandled = false
    
    // [FIX-CRITICAL] Track whether user explicitly stopped (vs network/crash auto-stop)
    // This prevents reconnect when user clicks STOP, but allows reconnect on network issues
    @Volatile private var manualStop = false
    
    // [CRASH-RECOVERY] Prefs helper for persistent state (manualStop + wasStreaming)
    private lateinit var prefs: Prefs
    
    // [ADAPTIVE] Computed encoder profile (set during initialization)
    private lateinit var encoderProfile: EncProfile
    
    private var pendingResult: MethodChannel.Result? = null
    private var pendingRtmpsUrl: String? = null
    private var pendingStreamKey: String? = null
    private var pendingResultCode: Int? = null
    private var pendingResultData: Intent? = null
    
    // PTL: Track sequence headers
    private var videoConfigSent = false
    private var audioConfigSent = false
    private var frameCount = 0
    private var keyframeCount = 0
    private var lastKeyframeTime = 0L
    private var totalBytesSent = 0L
    private var streamStartTime = 0L  // [PTL] Track stream start time for overlay metrics
    
    // [PTL] Track broadcast receiver for proper cleanup
    private var fgsReadyReceiver: android.content.BroadcastReceiver? = null
    
    // [FIX] BroadcastReceiver to handle overlay stop requests
    private var overlayStopReceiver: android.content.BroadcastReceiver? = null
    
    init {
        // [CRASH-RECOVERY] Initialize Prefs helper for state persistence
        prefs = Prefs(activity)
        
        // Register receiver for overlay stop requests
        overlayStopReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == "com.screenlive.app.ACTION_STOP_STREAM") {
                    Log.i(TAG, "[PTL] Received STOP_STREAM broadcast from overlay")
                    PtlLogger.i(TAG, "User stopped stream from overlay tap")
                    
                    // Stop streaming
                    stop(object : MethodChannel.Result {
                        override fun success(result: Any?) {
                            Log.i(TAG, "[PTL] Stream stopped successfully from overlay")
                            
                            // [FIX] Notify Flutter that stream stopped so it can navigate to setup screen
                            activity.runOnUiThread {
                                methodChannel?.invokeMethod("stopStreamCompleted", null)
                                Log.i(TAG, "[PTL] Notified Flutter: stopStreamCompleted")
                            }
                            
                            // Stop overlay service - use correct action constant
                            val stopOverlayIntent = Intent(activity, OverlayService::class.java)
                            stopOverlayIntent.action = "com.screenlive.app.overlay.STOP"
                            activity.startService(stopOverlayIntent)
                            Log.i(TAG, "[PTL] Stopped overlay service")
                        }
                        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                            Log.e(TAG, "[PTL] Failed to stop stream from overlay: $errorMessage")
                        }
                        override fun notImplemented() {}
                    })
                }
            }
        }
        
        val filter = android.content.IntentFilter("com.screenlive.app.ACTION_STOP_STREAM")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(overlayStopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(overlayStopReceiver, filter)
        }
        
        Log.i(TAG, "[PTL] RootEncoderService initialized, overlay stop receiver registered")
    }
    
    // ==================== Public API ====================
    
    fun handle(call: MethodCall, result: MethodChannel.Result) {
        // [PTL] Log immediately when method is called
        Log.i(TAG, "[PTL] MethodChannel handle() called: method=${call.method}")
        android.util.Log.i("[PTL] RootEncoder", "MethodChannel handle() called: method=${call.method}")
        
        when (call.method) {
            "start" -> start(call, result)
            "stop" -> stop(result)
            else -> result.notImplemented()
        }
    }
    
    // [PTL] Public cleanup method for MainActivity to call in onDestroy
    fun cleanupAll() {
        cleanup()
    }
    
    // [V2] Audio control methods for overlay (using CleanAudioMixer)
    fun toggleMic(): Boolean {
        cleanAudioMixer?.let {
            it.micEnabled = !it.micEnabled
            Log.i(TAG, "[V2] toggleMic() -> ${it.micEnabled}")
            return it.micEnabled
        }
        return false
    }
    
    fun toggleGameAudio(): Boolean {
        cleanAudioMixer?.let {
            it.gameEnabled = !it.gameEnabled
            Log.i(TAG, "[V2] toggleGameAudio() -> ${it.gameEnabled}")
            return it.gameEnabled
        }
        return false
    }
    
    fun isMicEnabled(): Boolean = cleanAudioMixer?.micEnabled ?: true
    fun isGameAudioEnabled(): Boolean = cleanAudioMixer?.gameEnabled ?: true
    
    private fun start(call: MethodCall, result: MethodChannel.Result) {
        try {
            val rtmpsUrl = call.argument<String>("rtmpsUrl") 
                ?: return result.error("INVALID_ARGS", "rtmpsUrl required", null)
            val streamKey = call.argument<String>("streamKey")
                ?: return result.error("INVALID_ARGS", "streamKey required", null)
            
            if (!rtmpsUrl.startsWith("rtmps://") && !rtmpsUrl.startsWith("rtmp://")) {
                return result.error("INVALID_URL", "URL must start with rtmp:// or rtmps://", null)
            }
            
            if (streamKey.isEmpty()) {
                return result.error("INVALID_KEY", "Stream key cannot be empty", null)
            }
            
            Log.i(TAG, "=== STARTING ROOTENCODER STREAM ===")
            Log.i(TAG, "URL: $rtmpsUrl")
            Log.i(TAG, "Key: ***${streamKey.takeLast(4)}")
            
            // PTL: Use centralized logger
            PtlLogger.i(TAG, "=== STARTING ROOTENCODER STREAM ===")
            PtlLogger.i(TAG, "URL: $rtmpsUrl")
            PtlLogger.i(TAG, "Key: $streamKey")  // Auto-masked by PtlLogger
            
            // [CRASH-RECOVERY] Mark stream start flags for auto-restart on crash
            markStartFlags()
            
            // Store for after projection grant
            pendingResult = result
            pendingRtmpsUrl = rtmpsUrl
            pendingStreamKey = streamKey
            
            // Request MediaProjection
            val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = projectionManager.createScreenCaptureIntent()
            activity.startActivityForResult(intent, REQUEST_CODE_PROJECTION)
            
        } catch (e: Exception) {
            Log.e(TAG, "Start failed", e)
            result.error("START_ERROR", e.message, null)
        }
    }
    
    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        try {
            if (resultCode != Activity.RESULT_OK || data == null) {
                pendingResult?.error("PROJECTION_DENIED", "User denied screen capture", null)
                pendingResult = null
                return
            }
            
            Log.i(TAG, "[PTL] MediaProjection permission granted, starting Android 14 compliant flow...")
            PtlLogger.i(TAG, "[PTL] MediaProjection permission granted, starting Android 14 compliant flow...")
            
            // Store for later use in onFgsReady()
            pendingResultCode = resultCode
            pendingResultData = data
            
            // ANDROID 14 COMPLIANT FLOW:
            // Step 1: MainActivity will register FGS_READY receiver
            // Step 2: Start FGS with MEDIA_PROJECTION type
            val serviceIntent = Intent(activity, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            
            Log.i(TAG, "[PTL] Starting foreground service with mediaProjection type...")
            PtlLogger.i(TAG, "[PTL] Starting foreground service with mediaProjection type...")
            
            activity.startForegroundService(serviceIntent)
            
        } catch (e: Exception) {
            Log.e(TAG, "onMediaProjectionResult error", e)
            pendingResult?.error("PROJECTION_ERROR", e.message, null)
            pendingResult = null
        }
    }
    
    // [PTL] Called by MainActivity when FGS_READY broadcast is received
    fun onFgsReady() {
        try {
            Log.i(TAG, "[PTL] onFgsReady() called - proceeding with MediaProjection setup")
            PtlLogger.i(TAG, "[PTL] onFgsReady() called - proceeding with MediaProjection setup")
            
            val resultCode = pendingResultCode ?: run {
                Log.e(TAG, "[PTL] onFgsReady() called but no pending resultCode")
                return
            }
            val data = pendingResultData ?: run {
                Log.e(TAG, "[PTL] onFgsReady() called but no pending resultData")
                return
            }
            
            // Android 14 requires 800-1200ms delay after FGS active before MediaProjection
            Log.i(TAG, "[PTL] Scheduling MediaProjection init with 1000ms A14-safe delay...")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    // [PTL] REMOVED: Diagnostic probe causes YouTube to reject duplicate stream key
                    // Only use WireDiagnostic.run() for debugging, not in production flow
                    // val streamKey = pendingStreamKey ?: ""
                    // Log.i(TAG, "[PTL] Calling WireDiagnostic.run with key: ***${streamKey.takeLast(4)}")
                    // com.screenlive.app.rtmp.WireDiagnostic.run(streamKey)
                    
                    createMediaProjectionSafely(resultCode, data)
                } catch (t: Throwable) {
                    Log.e(TAG, "[PTL] Error in delayed MediaProjection init", t)
                    pendingResult?.error("PROJECTION_SETUP_ERROR", t.message, null)
                    pendingResult = null
                }
            }, 1000L) // 1000ms delay for Android 14 safety
            
        } catch (e: Exception) {
            Log.e(TAG, "[PTL] onFgsReady error", e)
            pendingResult?.error("FGS_READY_ERROR", e.message, null)
            pendingResult = null
        }
    }
    
    private fun createMediaProjectionSafely(resultCode: Int, data: Intent) {
        try {
            Log.i(TAG, "Step 2: FGS ready, now creating MediaProjection...")
            PtlLogger.i(TAG, "Step 2: FGS ready, now creating MediaProjection...")
            
            // Step 3: Now safe to create MediaProjection (FGS with correct type is running)
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    PtlLogger.i(TAG, "MediaProjection stopped by system")
                    cleanup()
                }
            }
            
            val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            
            if (mediaProjection == null) {
                pendingResult?.error("PROJECTION_FAILED", "Failed to create MediaProjection", null)
                pendingResult = null
                return
            }
            
            mediaProjection?.registerCallback(callback, null)
            
            Log.i(TAG, "✅ MediaProjection created successfully (Android 14 flow)!")
            PtlLogger.i(TAG, "✅ MediaProjection created successfully (Android 14 flow)!")
            
            // ===== [PTL] ANCHOR: Log before wire launch =====
            Log.i(TAG, "[PTL] ANCHOR: About to launch RTMPS wire coroutine")
            com.screenlive.app.rtmp.PtlLog.i("ANCHOR: About to launch RTMPS wire coroutine")
            
            // ===== [PTL] Begin: Force RTMPS Wire =====
            scope.launch(Dispatchers.IO) {
                com.screenlive.app.rtmp.PtlLog.i("ANCHOR: Inside RTMPS wire coroutine - START")
                try {
                    com.screenlive.app.rtmp.PtlLog.i("WIRE: Starting TLS PING...")
                    Log.i(TAG, "[PTL] WIRE: Starting TLS PING test...")
                    try {
                        if (com.screenlive.app.rtmp.TlsPing.connect("a.rtmps.youtube.com", 443, 5000)) {
                            com.screenlive.app.rtmp.PtlLog.i("TLS PING OK: a.rtmps.youtube.com:443")
                        }
                    } catch (e: Exception) {
                        com.screenlive.app.rtmp.PtlLog.e("TLS PING FAIL: a.rtmps.youtube.com:443", e)
                        // Fail-fast để biết lỗi mạng/TLS
                        withContext(Dispatchers.Main) {
                            pendingResult?.error("TLS_FAILED", "Cannot reach YouTube: ${e.message}", null)
                            pendingResult = null
                        }
                        return@launch
                    }

                    val streamKey = pendingStreamKey
                    val rtmpsUrl = pendingRtmpsUrl  // [PTL] Get URL from pending variable
                    com.screenlive.app.rtmp.PtlLog.i("WIRE: Checking stream key... isNull=${streamKey == null}, isBlank=${streamKey?.isBlank()}")
                    Log.i(TAG, "[PTL] WIRE: Stream key check: isNull=${streamKey == null}, isBlank=${streamKey?.isBlank()}, last4=${streamKey?.takeLast(4)}")
                    
                    if (streamKey.isNullOrBlank() || rtmpsUrl.isNullOrBlank()) {
                        com.screenlive.app.rtmp.PtlLog.e("WIRE: streamKey or URL is blank → skip RTMPS")
                        withContext(Dispatchers.Main) {
                            pendingResult?.error("NO_STREAM_KEY", "Stream key and URL are required", null)
                            pendingResult = null
                        }
                        return@launch
                    }

                    // [PTL] Parse URL to extract host, port, app
                    val (parsedHost, parsedPort, parsedApp) = parseRtmpUrl(rtmpsUrl)
                    com.screenlive.app.rtmp.PtlLog.i("WIRE: Parsed URL → host=$parsedHost, port=$parsedPort, app=$parsedApp")
                    com.screenlive.app.rtmp.PtlLog.i("WIRE: Creating MinimalRtmpsClient for key: ***${streamKey.takeLast(4)}")
                    
                    rtmpsDisconnectHandled = false
                    val client = com.screenlive.app.rtmp.MinimalRtmpsClient(
                        host = parsedHost,
                        port = parsedPort,
                        app = parsedApp,
                        streamKey = streamKey,
                        rtmpUrl = rtmpsUrl,  // [PTL] Pass full URL for RTMPS:443 normalization
                        onDisconnected = { err -> handleRtmpsDisconnect(err) }  // [PTL] Pass error for reconnect logic
                    )
                    
                    // [PTL] Setup callback to start encoders when publish is confirmed
                    client.onPublishStarted = {
                        scope.launch {
                            onPublishConfirmed(client)
                        }
                    }
                    
                    rtmpsClient = client
                    
                    // [PTL] Initialize encoders BEFORE publish (but don't start loops yet)
                    initializeVideoEncoder()
                    initializeAudioEncoder()
                    initializeFlvMuxer()
                    
                    client.connectBlocking(15000)

                    if (!client.published) {
                        com.screenlive.app.rtmp.PtlLog.e("WIRE: RTMPS publish NOT started (timeout without exception)")
                        withContext(Dispatchers.Main) {
                            pendingResult?.error("RTMPS_TIMEOUT", "YouTube did not confirm publish start", null)
                            pendingResult = null
                        }
                    }
                    // Note: If published=true, onPublishStarted callback will handle encoder startup
                } catch (t: Throwable) {
                    com.screenlive.app.rtmp.PtlLog.e("WIRE: RTMPS failed", t)
                    withContext(Dispatchers.Main) {
                        pendingResult?.error("RTMPS_ERROR", "RTMPS connection failed: ${t.message}", null)
                        pendingResult = null
                    }
                }
            }
            // ===== [PTL] End: Force RTMPS Wire =====
        } catch (e: Exception) {
            Log.e(TAG, "createMediaProjectionSafely error", e)
            pendingResult?.error("PROJECTION_SETUP_ERROR", e.message, null)
            pendingResult = null
        }
    }
    
    // [PTL] Called when NetStream.Publish.Start received from YouTube
    private suspend fun onPublishConfirmed(client: com.screenlive.app.rtmp.MinimalRtmpsClient) {
        try {
            com.screenlive.app.rtmp.PtlLog.i("[PTL] PUBLISH ACK – starting encoder pipeline")
            PtlLogger.i(TAG, "[PTL] PUBLISH ACK – starting encoder pipeline")
            PtlLogger.i(TAG, "[PTL] onPublishConfirmed: manualStop=$manualStop isStreaming=$isStreaming isEncoding=$isEncoding")
            
            // Ensure encoders are ready
            if (videoEncoder == null || cleanAudioMixer == null) {
                PtlLogger.e(TAG, "❌ Encoders not initialized!")
                withContext(Dispatchers.Main) {
                    pendingResult?.error("ENCODER_ERROR", "Encoders not initialized", null)
                    pendingResult = null
                }
                return
            }
            
            // Send @setDataFrame("onMetaData") BEFORE first frames
            client.sendMetadata(
                width = encoderProfile.width,
                height = encoderProfile.height,
                fps = encoderProfile.fps,
                videoBitrate = encoderProfile.bitrate,
                audioBitrate = AUDIO_BITRATE
            )
            
            // [PTL FIX] Only start encoding loops ONCE
            // On reconnect, encoding loops are already running → just resume transport
            if (!isEncoding) {
                // FIRST publish: start encoding loops
                streamStartTime = System.currentTimeMillis()  // [PTL] Initialize stream start time
                isStreaming = true
                isEncoding = true
                startEncoding()
                PtlLogger.i(TAG, "[PTL] ✅ Started encoding loops (first publish)")
                
                // [PTL] Start overlay service to show streaming metrics
                startOverlayService()
                
            } else {
                // RECONNECT: encoding loops already running
                isStreaming = true  // Resume sending frames
                PtlLogger.i(TAG, "[PTL] ✅ Reconnected - reusing existing encoders")
            }
            
            withContext(Dispatchers.Main) {
                pendingResult?.success(mapOf("ok" to true))
                pendingResult = null
            }
            
            // Safety timer: Check if frames are flowing within 500ms
            delay(500)
            if (frameCount == 0 && !isEncoding) {  // Only check on first publish
                PtlLogger.e(TAG, "[PTL] ⚠️ No video frames within 500ms – encoder issue!")
                // Could attempt restart here if needed
            } else {
                PtlLogger.i(TAG, "✓ Encoder pipeline healthy (frameCount=$frameCount)")
            }
            
        } catch (e: Exception) {
            PtlLogger.e(TAG, "onPublishConfirmed failed", e)
            withContext(Dispatchers.Main) {
                pendingResult?.error("PUBLISH_ERROR", e.message, null)
                pendingResult = null
            }
        }
    }
    
    // [PTL] Separate method for encoder initialization (without starting encoding loops)
    private suspend fun initializeEncodersAndStart() {
        try {
            // Continue with encoder initialization
            if (mediaProjection == null) {
                withContext(Dispatchers.Main) {
                    pendingResult?.error("PROJECTION_FAILED", "MediaProjection is null", null)
                    pendingResult = null
                }
                return
            }
            
            initializeVideoEncoder()
            initializeAudioEncoder()
            initializeFlvMuxer()
            startEncoding()
                    
            withContext(Dispatchers.Main) {
                pendingResult?.success(mapOf("ok" to true))
                pendingResult = null
            }
                    
        } catch (e: Exception) {
            Log.e(TAG, "Encoder initialization failed", e)
            PtlLogger.e(TAG, "Encoder initialization failed: ${e.message}")
            cleanup()
            withContext(Dispatchers.Main) {
                pendingResult?.error("INIT_ERROR", e.message, null)
                pendingResult = null
            }
        }
    }
    
    private fun stop(result: MethodChannel.Result) {
        Log.i(TAG, "Stopping stream...")
        PtlLogger.i(TAG, "[FIX] stop() called - setting manualStop=true")
        manualStop = true  // [FIX-CRITICAL] User explicitly stopped - don't reconnect
        markStopFlags(manual = true)  // [CRASH-RECOVERY] Persist flags
        cleanup("manual stop")
        result.success(mapOf("ok" to true))
    }

    // ==================== Initialization ====================
    
    private fun initializeVideoEncoder() {
        Log.d(TAG, "[ADAPTIVE] Initializing video encoder with auto-profile...")
        
        // 1) Get native screen size in landscape orientation
        val (nativeW, nativeH) = getNativeLandscapeSize(activity)
        Log.i(TAG, "[ADAPTIVE] Native screen: ${nativeW}x${nativeH} (${String.format("%.2f", nativeW.toFloat()/nativeH)}:1)")
        
        // 2) Choose scale mode: FIT (keep aspect, no crop) or FILL_16_9 (force 16:9, may crop)
        val scaleMode = ScaleMode.FIT  // TODO: Make configurable via SharedPreferences
        
        // 3) Compute encoder size (limit to 1080p short edge)
        val (encW, encH) = computeEncoderSize(nativeW, nativeH, maxShortEdge = 1080, mode = scaleMode)
        
        // 4) FPS: 30 (safe for YouTube, no "framerate too high" warning)
        // TODO: Make configurable (30/60) based on network bandwidth test
        val fps = 30
        
        // 5) Bitrate: YouTube recommended for resolution + FPS
        val bitrate = suggestBitrate(encW, encH, fps)
        
        // 6) AVC Level: Based on resolution + FPS
        val avcLevel = avcLevelFor(encW, encH, fps)
        
        // Store profile for later use (overlay metrics, reconnect, etc.)
        encoderProfile = EncProfile(encW, encH, fps, bitrate, avcLevel)
        
        Log.i(TAG, "[ADAPTIVE] Encoder profile → ${encW}x${encH}@${fps}fps, ${bitrate/1_000_000}Mbps, level=$avcLevel, mode=$scaleMode")
        PtlLogger.i(TAG, "[ADAPTIVE] Profile: ${encW}x${encH}@${fps}fps, ${bitrate/1_000_000}Mbps")
        
        // 7) Create MediaFormat with computed settings
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)  // 2s GOP
            
            // CBR for consistent quality on YouTube
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            
            // Profile: High, Level: Computed dynamically
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, avcLevel)
            
            // Color metadata (Android 7+)
            if (Build.VERSION.SDK_INT >= 24) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, 1)  // BT.709
                setInteger(MediaFormat.KEY_COLOR_RANGE, 2)     // FULL
            }
            
            // [V2] Real-time encoder optimizations (API 23+)
            if (Build.VERSION.SDK_INT >= 23) {
                // Operating rate: 2x FPS to handle game 90/120fps without "hụt hơi"
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps * 2)
                Log.i(TAG, "[V2] Operating rate: ${fps * 2} (2x FPS)")
                
                // Priority 0 = real-time (minimize latency)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                Log.i(TAG, "[V2] Encoder priority: 0 (real-time)")
            }
        }
        
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        
        // Create input surface
        val surface = videoEncoder!!.createInputSurface()
        
        // Lock surface frame rate to computed FPS (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                surface.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                Log.i(TAG, "[ADAPTIVE] ✓ Surface frame rate locked to ${fps}fps")
            } catch (e: Exception) {
                Log.w(TAG, "[ADAPTIVE] Cannot set surface frame rate: ${e.message}")
            }
        }
        
        // 8) Create VirtualDisplay with encoder size (no scaling)
        val dpi = activity.resources.displayMetrics.densityDpi
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenLive",
            encW,
            encH,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
        
        videoEncoder!!.start()
        Log.i(TAG, "[ADAPTIVE] ✓ Video encoder started (${encW}x${encH}@${fps}fps)")
    }
    
    private fun initializeAudioEncoder() {
        Log.d(TAG, "[V2] Initializing CleanAudioMixer (48kHz stereo AAC-LC 160kbps)...")
        
        // [V2] Use CleanAudioMixer with drift correction, HPF, soft-limiter
        cleanAudioMixer = CleanAudioMixer(mediaProjection)
        cleanAudioMixer!!.init()
        
        // Start mixing with callback for AAC frames
        cleanAudioMixer!!.start { data, isConfig ->
            if (isConfig) {
                // AudioSpecificConfig - send once before first frame
                val tag = flvMuxer?.createAudioConfigTag(data, 0)
                if (tag != null) {
                    rtmpsClient?.sendFlvData(com.screenlive.app.rtmp.RtmpConsts.AUDIO_MESSAGE, tag)
                    Log.d(TAG, "[V2] ✓ AudioSpecificConfig sent (${data.size} bytes)")
                }
            } else {
                // Regular AAC frame
                val ts = ((System.currentTimeMillis() - streamStartTime)).toInt()
                val tag = flvMuxer?.createAudioTag(data, ts)
                if (tag != null) {
                    rtmpsClient?.sendFlvData(com.screenlive.app.rtmp.RtmpConsts.AUDIO_MESSAGE, tag, ts)
                }
            }
        }
        
        Log.i(TAG, "[V2] ✓ CleanAudioMixer started (no-AGC, HPF, limiter, drift-PLL)")
        PtlLogger.i(TAG, "[V2] Audio: drift-corrected, 30Hz HPF, smooth limiter")
    }
    
    private fun initializeFlvMuxer() {
        flvMuxer = MinimalFlvMuxer()
        Log.i(TAG, "✓ FLV muxer initialized")
    }
    
    // ==================== Encoding Loop ====================
    
    private fun startEncoding() {
        // Note: isStreaming flag should already be set by caller
        encodingJob = scope.launch {
            try {
                // Send FLV header + metadata
                sendFlvHeader()
                
                // [V2] Only video loop - audio handled by CleanAudioMixer callback
                launch { encodeVideoLoop() }
                // Audio frames sent via CleanAudioMixer callback in initializeAudioEncoder()
                
            } catch (e: Exception) {
                Log.e(TAG, "Encoding error", e)
                cleanup()
            }
        }
    }
    
    private fun sendFlvHeader() {
        // [FIX] Send metadata to YouTube with correct framerate=30
        // YouTube uses this to validate stream settings
        try {
            rtmpsClient?.sendMetadata(
                width = encoderProfile.width,
                height = encoderProfile.height,
                fps = encoderProfile.fps,  // [FIX] Will be 30fps now
                videoBitrate = encoderProfile.bitrate,
                audioBitrate = AUDIO_BITRATE
            )
            Log.i(TAG, "[FIX] ✓ Metadata sent: ${encoderProfile.width}x${encoderProfile.height}@${encoderProfile.fps}fps, video=${encoderProfile.bitrate/1_000_000}Mbps, audio=${AUDIO_BITRATE/1000}kbps")
            PtlLogger.i(TAG, "Metadata: ${encoderProfile.width}x${encoderProfile.height}@${encoderProfile.fps}fps, ${encoderProfile.bitrate/1_000_000}Mbps")
        } catch (e: Exception) {
            Log.w(TAG, "[FIX] Failed to send metadata: ${e.message}")
            // Non-critical - continue streaming
        }
        
        Log.d(TAG, "✓ RTMP ready for video/audio packets")
    }
    
    /**
     * [V2] Adapt bitrate dynamically based on network conditions
     * Call this periodically (every 2-3s) based on throughput monitoring
     * @param targetKbps Target bitrate in kilobits per second
     */
    fun adaptBitrate(targetKbps: Int) {
        try {
            val targetBps = targetKbps * 1000
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, targetBps)
            }
            videoEncoder?.setParameters(params)
            
            // Update stored profile
            encoderProfile = encoderProfile.copy(bitrate = targetBps)
            
            Log.i(TAG, "[V2] ✓ Bitrate adapted → ${targetKbps}kbps (${targetBps/1_000_000}Mbps)")
            PtlLogger.i(TAG, "Bitrate: ${targetKbps}kbps")
        } catch (e: Exception) {
            Log.w(TAG, "[V2] Failed to adapt bitrate: ${e.message}")
        }
    }
    
    private fun encodeVideoLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        var startTime = System.currentTimeMillis()
        
        PtlLogger.i(TAG, "Video encoding loop started")
        
        while (isStreaming) {
            try {
                val outputIndex = videoEncoder?.dequeueOutputBuffer(bufferInfo, 10_000) ?: continue
                
                if (outputIndex >= 0) {
                    val outputBuffer = videoEncoder?.getOutputBuffer(outputIndex) ?: continue
                    
                    // PTL FIX: Handle codec config (SPS/PPS) separately
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // This is SPS/PPS - send as AVCDecoderConfigurationRecord
                        if (!videoConfigSent && bufferInfo.size > 0) {
                            val configData = ByteArray(bufferInfo.size)
                            outputBuffer.get(configData)
                            
                            val configTag = flvMuxer?.createVideoConfigTag(configData, 0)
                            if (configTag != null) {
                                rtmpsClient?.sendFlvData(com.screenlive.app.rtmp.RtmpConsts.VIDEO_MESSAGE, configTag)
                                totalBytesSent += configTag.size
                                videoConfigSent = true
                                PtlLogger.i(TAG, "✓ Sent SPS/PPS (AVCDecoderConfigurationRecord) - ${configData.size} bytes")
                            }
                        }
                    } else if (bufferInfo.size > 0) {
                        // Regular frame data
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)
                        
                        val timestamp = (System.currentTimeMillis() - startTime).toInt()
                        val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        
                        if (isKeyframe) {
                            keyframeCount++
                            val keyframeInterval = timestamp - lastKeyframeTime
                            lastKeyframeTime = timestamp.toLong()
                            
                            if (keyframeCount % 10 == 0) {
                                PtlLogger.d(TAG, "Keyframe #$keyframeCount @ ${timestamp}ms (interval: ${keyframeInterval}ms)")
                            }
                        }
                        
                        val flvTag = flvMuxer?.createVideoTag(data, timestamp, isKeyframe)
                        if (flvTag != null) {
                            rtmpsClient?.sendFlvData(com.screenlive.app.rtmp.RtmpConsts.VIDEO_MESSAGE, flvTag, timestamp)
                            totalBytesSent += flvTag.size
                        }
                        
                        frameCount++
                        
                        // Log first frame
                        if (frameCount == 1) {
                            PtlLogger.i(TAG, "✓ First video frame @ ${timestamp}ms")
                        }
                        
                        // Periodic stats
                        if (frameCount % 300 == 0) {  // Every 5s @ 60fps
                            val fps = frameCount / ((timestamp + 1) / 1000.0)
                            PtlLogger.d(TAG, "Stats: Frames=$frameCount, Keyframes=$keyframeCount, FPS=${"%.1f".format(fps)}, Bytes=${PtlLogger.formatBytes(totalBytesSent)}")
                        }
                    }
                    
                    videoEncoder?.releaseOutputBuffer(outputIndex, false)
                }
                
            } catch (e: Exception) {
                PtlLogger.e(TAG, "Video encoding error", e)
                break
            }
        }
        
        PtlLogger.i(TAG, "Video encoding loop stopped (frames=$frameCount, keyframes=$keyframeCount)")
    }
    
    // [V2] Old encodeAudioLoop removed - audio handled by CleanAudioMixer callback
    // Audio frames are sent directly in initializeAudioEncoder() via callback:
    // cleanAudioMixer!!.start { data, isConfig -> /* send FLV */ }
    
    // ==================== Overlay Integration ====================
    
    /**
     * [PTL] Start overlay service to display streaming metrics
     */
    private fun startOverlayService() {
        try {
            val intent = Intent(activity, com.screenlive.app.overlay.OverlayService::class.java)
            intent.action = com.screenlive.app.overlay.OverlayService.ACTION_START
            activity.startService(intent)
            PtlLogger.i(TAG, "[PTL] Overlay service started")
        } catch (e: Exception) {
            PtlLogger.e(TAG, "[PTL] Failed to start overlay service", e)
        }
    }
    
    /**
     * [PTL] Stop overlay service
     */
    private fun stopOverlayService() {
        try {
            val intent = Intent(activity, com.screenlive.app.overlay.OverlayService::class.java)
            intent.action = com.screenlive.app.overlay.OverlayService.ACTION_STOP
            activity.startService(intent)
            PtlLogger.i(TAG, "[PTL] Overlay service stopped")
        } catch (e: Exception) {
            PtlLogger.e(TAG, "[PTL] Failed to stop overlay service", e)
        }
    }
    
    /**
     * [PTL] Update overlay with current streaming metrics
     */
    private fun updateOverlayMetrics() {
        try {
            val elapsedMs = System.currentTimeMillis() - streamStartTime
            val elapsedSec = elapsedMs / 1000.0
            
            // Calculate instantaneous bitrate (kbps)
            val bitrateKbps = if (elapsedSec > 0) {
                ((totalBytesSent * 8.0) / elapsedSec / 1000.0).toInt()
            } else 0
            
            // Calculate FPS (approximate from frame count)
            val fps = if (elapsedSec > 0) {
                (frameCount.toDouble() / elapsedSec).toInt()
            } else 0
            
            val intent = Intent(activity, com.screenlive.app.overlay.OverlayService::class.java)
            intent.action = com.screenlive.app.overlay.OverlayService.ACTION_UPDATE_METRICS
            intent.putExtra(com.screenlive.app.overlay.OverlayService.EXTRA_BITRATE_KBPS, bitrateKbps)
            intent.putExtra(com.screenlive.app.overlay.OverlayService.EXTRA_FPS, fps)
            intent.putExtra(com.screenlive.app.overlay.OverlayService.EXTRA_DROPPED_FRAMES, 0) // TODO: track dropped frames
            intent.putExtra(com.screenlive.app.overlay.OverlayService.EXTRA_ELAPSED_MS, elapsedMs)
            activity.startService(intent)
        } catch (e: Exception) {
            // Silent fail - overlay updates are not critical
        }
    }
    
    // ==================== Cleanup ====================
    
    /**
     * [PTL] CRITICAL: Auto-reconnect without stopping encoders (PiP survival)
     * [FIX] Only reconnect if NOT manual stop - allows recovery from network issues
     * Why: 6-8s disconnect after PiP is often temporary (carrier/WiFi power-save)
     * Strategy: Retry with exponential backoff, keep encoders running during reconnect
     */
    private fun handleRtmpsDisconnect(err: Throwable?) {
        if (rtmpsDisconnectHandled) {
            return
        }
        rtmpsDisconnectHandled = true
        
        PtlLogger.e(TAG, "[PTL] RTMPS lost: ${err?.message ?: "unknown"} — checking if auto-reconnect allowed")
        Log.e(TAG, "RTMPS connection lost, attempting auto-reconnect...")
        
        // [PTL] DON'T stop encoders immediately - try reconnect first
        reconnectJob = scope.launch {
            var backoffMs = 500L
            val maxAttempts = com.screenlive.app.config.StreamConfig.maxReconnectAttempts
            
            repeat(maxAttempts) { attempt ->
                // [FIX-CRITICAL] Check if user manually stopped stream OR stream already stopped - abort reconnect
                if (manualStop || !isStreaming) {
                    PtlLogger.i(TAG, "[PTL] Reconnect aborted (manualStop=$manualStop, isStreaming=$isStreaming)")
                    return@launch
                }
                
                delay(backoffMs)
                PtlLogger.i(TAG, "[PTL] Reconnect attempt ${attempt + 1}/$maxAttempts (backoff=${backoffMs}ms)")
                
                val success = rtmpsClient?.reconnect() ?: false
                if (success) {
                    PtlLogger.i(TAG, "[PTL] ✅ Reconnected successfully on attempt ${attempt + 1}")
                    rtmpsDisconnectHandled = false  // Reset for next disconnect
                    return@launch
                }
                
                // Exponential backoff: 0.5s → 0.85s → 1.45s → 2.47s → 4.2s → cap at 10s
                backoffMs = kotlin.math.min(10_000, (backoffMs * 1.7).toLong())
            }
            
            // [PTL] All reconnect attempts failed - check manualStop before cleanup
            if (!manualStop) {
                PtlLogger.e(TAG, "[PTL] ❌ Reconnect failed after $maxAttempts attempts — stopping stream")
                val wasManual = prefs.manualStop
                cleanup("reconnect failed")
                
                // [CRASH-RECOVERY] If not manual stop and was streaming → restart activity
                // to re-request MediaProjection permission
                if (!wasManual && prefs.wasStreaming) {
                    PtlLogger.i(TAG, "[RECOVERY] Scheduling activity restart in 1.5s")
                    RestartHelper.scheduleRestartActivity(activity, 1500L)
                }
            } else {
                PtlLogger.i(TAG, "[PTL] Reconnect failed but manualStop=true → no further action")
            }
        }
    }

    private fun cleanup(reason: String = "unknown") {
        PtlLogger.i(TAG, "[PTL] Cleanup triggered: $reason")
        
        // [CRASH-RECOVERY] Check if this is a manual stop
        val isManual = prefs.manualStop
        
        isStreaming = false
        isEncoding = false  // [PTL FIX] Reset encoding flag
        
        // [FIX] Cancel reconnect job immediately to prevent reconnect after manual stop
        reconnectJob?.cancel()
        reconnectJob = null
        
        encodingJob?.cancel()
        
        // [PTL] Stop overlay service
        stopOverlayService()
        
        try {
            // [V2] Cleanup CleanAudioMixer (replaces AudioCaptureManager)
            runCatching {
                cleanAudioMixer?.cleanup()
                cleanAudioMixer = null
            }
            
            // Safe video encoder cleanup
            try { videoEncoder?.stop() } catch (_: IllegalStateException) {}
            try { videoEncoder?.release() } catch (_: Exception) {}
            videoEncoder = null
            
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
            
            try { mediaProjection?.stop() } catch (_: Exception) {}
            mediaProjection = null
            
            rtmpsClient?.closeQuiet()
            rtmpsClient = null
            
            flvMuxer = null
            
            Log.i(TAG, "✓ Cleanup complete")
            
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
        rtmpsDisconnectHandled = false
        
        // [CRASH-RECOVERY] Only clear wasStreaming if manual stop
        // Keep it true for crash/reconnect-failed so activity can restart
        if (isManual) {
            prefs.wasStreaming = false
            PtlLogger.i(TAG, "[RECOVERY] Manual cleanup: wasStreaming=false")
        } else {
            PtlLogger.i(TAG, "[RECOVERY] Non-manual cleanup: wasStreaming kept true for auto-restart")
        }
        
        // [PTL] Clean up broadcast receiver to prevent memory leaks
        try {
            fgsReadyReceiver?.let { receiver ->
                activity.unregisterReceiver(receiver)
                fgsReadyReceiver = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister FGS receiver (may already be unregistered)", e)
        }
        
        // [FIX] Unregister overlay stop receiver
        try {
            overlayStopReceiver?.let { receiver ->
                activity.unregisterReceiver(receiver)
                overlayStopReceiver = null
                Log.i(TAG, "[PTL] Overlay stop receiver unregistered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister overlay stop receiver (may already be unregistered)", e)
        }
    }
    
    // [PTL] Parse RTMP/RTMPS URL to extract host, port, app
    private fun parseRtmpUrl(url: String): Triple<String, Int, String> {
        // Example: rtmp://a.rtmp.youtube.com/live2 or rtmps://a.rtmps.youtube.com:443/live2
        val regex = Regex("^(rtmps?)://([^:/]+)(?::(\\d+))?(/.*)?$")
        val match = regex.find(url) ?: throw IllegalArgumentException("Invalid RTMP URL: $url")
        
        val scheme = match.groupValues[1]  // "rtmp" or "rtmps"
        val host = match.groupValues[2]     // "a.rtmp.youtube.com"
        val portStr = match.groupValues[3]  // "" or "443"
        val path = match.groupValues[4]     // "/live2"
        
        val port = if (portStr.isNotEmpty()) portStr.toInt() else if (scheme == "rtmps") 443 else 1935
        val app = path.trimStart('/').ifEmpty { "live" }
        
        return Triple(host, port, app)
    }
}
