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
        
        // Fixed MVP presets
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 60
        private const val VIDEO_BITRATE = 6_000_000  // 6 Mbps (YouTube recommended 4.5-7.5 Mbps for 720p60)
        private const val VIDEO_IFRAME_INTERVAL = 2  // 2 seconds (GOP = 120 frames @ 60fps)
        
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_BITRATE = 160_000  // 160 kbps (increased from 128)
        private const val AUDIO_CHANNELS = 1  // Mono for MVP
        
        const val REQUEST_CODE_PROJECTION = 1001
    }
    
    // MethodChannel for callbacks to Flutter
    internal var methodChannel: MethodChannel? = null
    
    fun setMethodChannel(channel: MethodChannel) {
        methodChannel = channel
        Log.i(TAG, "[PTL] MethodChannel registered in RootEncoderService")
    }
    
    // Public method for overlay to stop stream
    fun stopFromOverlay(result: MethodChannel.Result) {
        Log.i(TAG, "[PTL] stopFromOverlay() called")
        stop(result)
    }
    
    // State
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioBufferSize: Int = 0 // [PTL] Store audio buffer size for encoding loop
    
    private var flvMuxer: MinimalFlvMuxer? = null
    private var rtmpsClient: com.screenlive.app.rtmp.MinimalRtmpsClient? = null
    
    internal var isStreaming = false  // [PTL] Made internal for PiP mode detection in MainActivity
    @Volatile private var isEncoding = false  // [PTL FIX] Track if encoding loops are already running
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var encodingJob: Job? = null

    @Volatile private var rtmpsDisconnectHandled = false
    
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
            Log.i(TAG, "Preset: ${VIDEO_WIDTH}x${VIDEO_HEIGHT}@${VIDEO_FPS}fps, ${VIDEO_BITRATE/1_000_000}Mbps")
            
            // PTL: Use centralized logger
            PtlLogger.i(TAG, "=== STARTING ROOTENCODER STREAM ===")
            PtlLogger.i(TAG, "URL: $rtmpsUrl")
            PtlLogger.i(TAG, "Key: $streamKey")  // Auto-masked by PtlLogger
            PtlLogger.i(TAG, "Preset: ${VIDEO_WIDTH}x${VIDEO_HEIGHT}@${VIDEO_FPS}fps, ${VIDEO_BITRATE/1_000_000}Mbps")
            
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
            
            // Ensure encoders are ready
            if (videoEncoder == null || audioEncoder == null) {
                PtlLogger.e(TAG, "❌ Encoders not initialized!")
                withContext(Dispatchers.Main) {
                    pendingResult?.error("ENCODER_ERROR", "Encoders not initialized", null)
                    pendingResult = null
                }
                return
            }
            
            // Send @setDataFrame("onMetaData") BEFORE first frames
            client.sendMetadata(
                width = VIDEO_WIDTH,
                height = VIDEO_HEIGHT,
                fps = VIDEO_FPS,
                videoBitrate = VIDEO_BITRATE,
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
        cleanup()
        result.success(mapOf("ok" to true))
    }

    // ==================== Initialization ====================
    
    private fun initializeVideoEncoder() {
        Log.d(TAG, "Initializing video encoder...")
        
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        
        // Create VirtualDisplay
        val surface = videoEncoder!!.createInputSurface()
        val metrics = activity.resources.displayMetrics
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenLive",
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
        
        videoEncoder!!.start()
        Log.i(TAG, "✓ Video encoder started")
    }
    
    private fun initializeAudioEncoder() {
        Log.d(TAG, "Initializing audio encoder...")
        
        // TODO: Audio Playback Capture (Android 10+)
        // For MVP: Use microphone
        
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNELS
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192) // [PTL FIX] Ensure sufficient input buffer
        }
        
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        
        // Initialize AudioRecord
        audioBufferSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            audioBufferSize
        ).apply {
            startRecording()
        }
        
        Log.i(TAG, "✓ Audio encoder started (microphone, bufferSize=$audioBufferSize)")
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
                
                // Parallel encoding
                launch { encodeVideoLoop() }
                launch { encodeAudioLoop() }
                
            } catch (e: Exception) {
                Log.e(TAG, "Encoding error", e)
                cleanup()
            }
        }
    }
    
    private fun sendFlvHeader() {
        // [PTL FIX] For RTMP streaming, we DON'T send FLV file header or metadata
        // RTMP expects direct video/audio messages after publish
        // The metadata is optional and YouTube doesn't require it
        
        PtlLogger.i(TAG, "RTMP ready - no metadata needed for YouTube")
        Log.d(TAG, "✓ RTMP ready for video/audio packets")
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
    
    private fun encodeAudioLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        // [PTL FIX] Use actual audio buffer size, not hardcoded 4096
        val pcmBuffer = ByteArray(maxOf(audioBufferSize, 4096))
        var startTime = System.currentTimeMillis()
        var audioFrameCount = 0
        
        PtlLogger.i(TAG, "Audio encoding loop started (bufferSize=${pcmBuffer.size})")
        
        while (isStreaming) {
            try {
                // Read PCM from AudioRecord
                val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: 0
                if (read > 0) {
                    // Feed to encoder
                    val inputIndex = audioEncoder?.dequeueInputBuffer(10_000) ?: continue
                    if (inputIndex >= 0) {
                        val inputBuffer = audioEncoder?.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            // [PTL FIX] Only put data that fits in the input buffer
                            val bytesToWrite = minOf(read, inputBuffer.remaining())
                            if (bytesToWrite < read) {
                                PtlLogger.w(TAG, "Audio buffer truncated: read=$read, capacity=${inputBuffer.remaining()}")
                            }
                            inputBuffer.put(pcmBuffer, 0, bytesToWrite)
                            audioEncoder?.queueInputBuffer(inputIndex, 0, bytesToWrite, System.nanoTime() / 1000, 0)
                        }
                    }
                }
                
                // Get encoded AAC
                val outputIndex = audioEncoder?.dequeueOutputBuffer(bufferInfo, 0) ?: continue
                if (outputIndex >= 0) {
                    val outputBuffer = audioEncoder?.getOutputBuffer(outputIndex) ?: continue
                    
                    // PTL FIX: Handle AAC config separately
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // This is AudioSpecificConfig
                        PtlLogger.i(TAG, "Audio: Received CODEC_CONFIG flag (size=${bufferInfo.size})")
                        if (!audioConfigSent && bufferInfo.size > 0) {
                            val configData = ByteArray(bufferInfo.size)
                            outputBuffer.get(configData)
                            
                            val configTag = flvMuxer?.createAudioConfigTag(configData, 0)
                            if (configTag != null) {
                                rtmpsClient?.sendFlvData(com.screenlive.app.rtmp.RtmpConsts.AUDIO_MESSAGE, configTag)
                                totalBytesSent += configTag.size
                                audioConfigSent = true
                                PtlLogger.i(TAG, "✓ Sent AAC config (AudioSpecificConfig) - ${configData.size} bytes → totalSent=$totalBytesSent")
                            } else {
                                PtlLogger.w(TAG, "⚠️ Failed to create audio config FLV tag!")
                            }
                        }
                    } else if (bufferInfo.size > 0) {
                        // Regular AAC frames
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)
                        
                        val timestamp = (System.currentTimeMillis() - startTime).toInt()
                        val flvTag = flvMuxer?.createAudioTag(data, timestamp)
                        if (flvTag != null) {
                            rtmpsClient?.sendFlvData(com.screenlive.app.rtmp.RtmpConsts.AUDIO_MESSAGE, flvTag, timestamp)
                            totalBytesSent += flvTag.size
                        }
                        
                        audioFrameCount++
                        
                        if (audioFrameCount == 1) {
                            PtlLogger.i(TAG, "✓ First audio frame @ ${timestamp}ms")
                        }
                        
                        // [PTL] Update overlay metrics every 30 frames (~1 second at 30fps audio)
                        if (audioFrameCount % 30 == 0) {
                            updateOverlayMetrics()
                        }
                    }
                    
                    audioEncoder?.releaseOutputBuffer(outputIndex, false)
                }
                
            } catch (e: Exception) {
                PtlLogger.e(TAG, "Audio encoding error", e)
                break
            }
        }
        
        PtlLogger.i(TAG, "Audio encoding loop stopped (frames=$audioFrameCount)")
    }
    
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
     * Why: 6-8s disconnect after PiP is often temporary (carrier/WiFi power-save)
     * Strategy: Retry with exponential backoff, keep encoders running during reconnect
     */
    private fun handleRtmpsDisconnect(err: Throwable?) {
        if (rtmpsDisconnectHandled) {
            return
        }
        rtmpsDisconnectHandled = true
        
        PtlLogger.e(TAG, "[PTL] RTMPS lost: ${err?.message ?: "unknown"} — will auto-reconnect")
        Log.e(TAG, "RTMPS connection lost, attempting auto-reconnect...")
        
        // [PTL] DON'T stop encoders immediately - try reconnect first
        scope.launch {
            var backoffMs = 500L
            val maxAttempts = com.screenlive.app.config.StreamConfig.maxReconnectAttempts
            
            repeat(maxAttempts) { attempt ->
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
            
            // [PTL] All reconnect attempts failed - NOW stop encoders
            PtlLogger.e(TAG, "[PTL] ❌ Reconnect failed after $maxAttempts attempts — stopping stream")
            cleanup("reconnect failed")
        }
    }

    private fun cleanup(reason: String = "unknown") {
        PtlLogger.i(TAG, "[PTL] Cleanup triggered: $reason")
        isStreaming = false
        isEncoding = false  // [PTL FIX] Reset encoding flag
        encodingJob?.cancel()
        
        // [PTL] Stop overlay service
        stopOverlayService()
        
        try {
            // Safe audio cleanup
            try { audioRecord?.stop() } catch (_: IllegalStateException) {}
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
            
            // Safe audio encoder cleanup  
            try { audioEncoder?.stop() } catch (_: IllegalStateException) {}
            try { audioEncoder?.release() } catch (_: Exception) {}
            audioEncoder = null
            
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
