package com.screenlive.app

import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Audio capture manager - Mic + Internal (Android 10+)
 * 
 * SPEC 48kHz STEREO AAC-LC (RTMP/YouTube compatible)
 * - Sample Rate: 48000 Hz (hardware native, no resample)
 * - Channels: Stereo (2ch)
 * - Codec: AAC-LC 160kbps
 * - Frame Size: 1024 samples/channel = 4096 bytes (1024 * 2ch * 2bytes)
 * 
 * DC Offset Filter + Soft Limiter to eliminate "rè rè" noise
 */
class AudioCaptureManager(
    private val appContext: Context,
    private val mediaProjection: MediaProjection?
) {
    companion object {
        private const val TAG = "AudioCapture"
        
        // 48kHz PIPELINE - hardware native (no resample)
        const val SAMPLE_RATE = 48000         // 48kHz (hardware std)
        const val CHANNELS = 2                // Stereo
        const val BITRATE = 160_000           // 160 kbps AAC-LC
        const val FRAME_SAMPLES = 1024        // AAC-LC frame = 1024 samples
        const val FRAME_SIZE = FRAME_SAMPLES * CHANNELS * 2  // 4096 bytes
    }
    
    // DC offset filter (high-pass 20Hz)
    private class DCFilter(private val sampleRate: Int = SAMPLE_RATE) {
        private var yL = 0.0
        private var xL = 0.0
        private val rc = 1.0 / (2 * Math.PI * 20.0)
        private val alpha = rc / (rc + 1.0 / sampleRate)
        
        fun process(sample: Int): Int {
            val x = sample.toDouble()
            val y = alpha * (yL + x - xL)
            xL = x
            yL = y
            return y.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt()
        }
    }
    
    // Audio capture sources
    private var micRecord: AudioRecord? = null
    private var gameRecord: AudioRecord? = null
    
    // Encoder
    private var aacEncoder: MediaCodec? = null
    
    // PTS tracking
    @Volatile private var audioPtsUs = 0L
    @Volatile private var frameCount = 0L
    
    // State
    @Volatile private var isCapturing = false
    private var ascSent = false
    
    // Toggle state (public for UI controls)
    @Volatile var isMicEnabled = true
    @Volatile var isGameAudioEnabled = true
    
    // DC filters (stereo L/R for mic and game)
    private val dcFilterMicL = DCFilter()
    private val dcFilterMicR = DCFilter()
    private val dcFilterGameL = DCFilter()
    private val dcFilterGameR = DCFilter()
    
    /**
     * Initialize audio capture (mic + internal if Android 10+)
     * @return true if at least mic is OK
     */
    fun initialize(): Boolean {
        try {
            // Log hardware sample rate
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val hwSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            Log.i(TAG, "[PTL] HW outputSampleRate=$hwSampleRate")
            
            // 1. Setup mic (primary source) - 48kHz stereo
            val micBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            if (micBufferSize <= 0) {
                Log.e(TAG, "[PTL] Invalid mic buffer size: $micBufferSize")
                return false
            }
            
            Log.i(TAG, "[PTL] Mic minBuffer=$micBufferSize, using=${micBufferSize * 2}")
            
            micRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // AEC/NS enabled
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                micBufferSize * 2
            )
            
            if (micRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "[PTL] Mic init FAILED - state=${micRecord?.state}")
                micRecord?.release()
                micRecord = null
                return false
            }
            
            Log.i(TAG, "[PTL] Mic init OK - ${SAMPLE_RATE}Hz stereo, buffer=$micBufferSize")
            
            // 2. Setup internal audio (Android 10+ only) - 48kHz stereo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                try {
                    val apc = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                    
                    val playbackFormat = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                    
                    val pbBufferSize = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    
                    Log.i(TAG, "[PTL] Internal minBuffer=$pbBufferSize, using=${pbBufferSize * 2}")
                    
                    gameRecord = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(apc)
                        .setAudioFormat(playbackFormat)
                        .setBufferSizeInBytes(pbBufferSize * 2)
                        .build()
                    
                    if (gameRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        Log.i(TAG, "[PTL] Internal audio init OK - ${SAMPLE_RATE}Hz stereo")
                    } else {
                        Log.w(TAG, "[PTL] Internal audio init FAILED - falling back to mic only")
                        gameRecord?.release()
                        gameRecord = null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[PTL] PlaybackCapture not available: ${e.message}")
                    gameRecord = null
                }
            } else {
                Log.i(TAG, "[PTL] Android < 10 or no MediaProjection - mic only")
            }
            
            // 3. Setup AAC encoder (48kHz stereo AAC-LC 160kbps)
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE,
                CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            
            aacEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aacEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aacEncoder?.start()
            
            Log.i(TAG, "[PTL] AAC encoder init OK - ${SAMPLE_RATE}Hz ${CHANNELS}ch ${BITRATE}bps")
            Log.i(TAG, "[PTL] Frame size: $FRAME_SAMPLES samples = $FRAME_SIZE bytes")
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "[PTL] Audio init FAILED", e)
            cleanup()
            return false
        }
    }
    
    /**
     * Start audio recording
     */
    fun start() {
        isCapturing = true
        audioPtsUs = 0L
        ascSent = false
        
        micRecord?.startRecording()
        gameRecord?.startRecording()
        
        Log.i(TAG, "[PTL] Audio capture STARTED")
    }
    
    /**
     * Read and encode one audio frame
     * SOFT-MUTE strategy: Always feed encoder, use silence when muted
     * @return AudioFrame (config or data) or null if no data
     */
    fun captureAndEncode(): AudioFrame? {
        if (!isCapturing) return null
        
        try {
            // Prepare buffers
            val micBuf = ByteArray(FRAME_SIZE)
            val gameBuf = ByteArray(FRAME_SIZE)
            val mixedBuf = ByteArray(FRAME_SIZE)
            val silenceBuf = ByteArray(FRAME_SIZE) // Pre-zeroed silence
            
            // SOFT-MUTE: Read from sources ONLY if enabled
            // If disabled, we keep AudioRecord alive but don't read (let it drain to internal buffer)
            val micRead = if (isMicEnabled) {
                micRecord?.read(micBuf, 0, FRAME_SIZE) ?: 0
            } else {
                // Drain mic buffer to prevent overflow (but don't use data)
                micRecord?.read(micBuf, 0, FRAME_SIZE)
                0 // Use silence
            }
            
            val gameRead = if (isGameAudioEnabled) {
                gameRecord?.read(gameBuf, 0, FRAME_SIZE) ?: 0
            } else {
                // Drain game buffer to prevent overflow (but don't use data)
                gameRecord?.read(gameBuf, 0, FRAME_SIZE)
                0 // Use silence
            }
            
            // Check underflow (only when enabled)
            if (isMicEnabled && micRead > 0 && micRead < FRAME_SIZE) {
                Log.w(TAG, "[AUDIO] Mic underflow: read=$micRead < frameSize=$FRAME_SIZE")
            }
            if (isGameAudioEnabled && gameRead > 0 && gameRead < FRAME_SIZE) {
                Log.w(TAG, "[AUDIO] Game underflow: read=$gameRead < frameSize=$FRAME_SIZE")
            }
            
            // Apply DC filter + limiter ONLY to active sources
            if (micRead > 0) {
                processAudioFrame(micBuf, micRead, dcFilterMicL, dcFilterMicR)
            }
            if (gameRead > 0) {
                processAudioFrame(gameBuf, gameRead, dcFilterGameL, dcFilterGameR)
            }
            
            // Choose final buffer: Mix, single source, or SILENCE
            val finalBuf = when {
                micRead > 0 && gameRead > 0 -> {
                    // Both enabled: mix
                    mixPcm(micBuf, gameBuf, mixedBuf, minOf(micRead, gameRead))
                    mixedBuf
                }
                micRead > 0 -> micBuf  // Only mic
                gameRead > 0 -> gameBuf // Only game
                else -> {
                    // Both disabled: send silence to keep encoder running (soft-mute)
                    if (frameCount % 100 == 0L) {
                        Log.d(TAG, "[SOFT-MUTE] Feeding silence frame (mic=${isMicEnabled}, game=${isGameAudioEnabled})")
                    }
                    silenceBuf
                }
            }
            
            val bytesRead = maxOf(micRead, gameRead, FRAME_SIZE) // Ensure non-zero
            
            // 4. Feed to encoder in 1024-sample chunks
            var offset = 0
            while (offset + FRAME_SIZE <= bytesRead) {
                val inputIdx = aacEncoder?.dequeueInputBuffer(5000) ?: break
                if (inputIdx >= 0) {
                    val inputBuf = aacEncoder?.getInputBuffer(inputIdx) ?: break
                    inputBuf.clear()
                    inputBuf.put(finalBuf, offset, FRAME_SIZE)
                    
                    // PTS: increment by FRAME_SAMPLES (1024 samples per frame)
                    val incUs = (FRAME_SAMPLES * 1_000_000L) / SAMPLE_RATE
                    audioPtsUs += incUs
                    aacEncoder?.queueInputBuffer(inputIdx, 0, FRAME_SIZE, audioPtsUs, 0)
                    offset += FRAME_SIZE
                    frameCount++ // Track frames for debug logging
                }
            }
            
            // 5. Get encoder output
            val info = MediaCodec.BufferInfo()
            val outputIdx = aacEncoder?.dequeueOutputBuffer(info, 0) ?: return null
            
            if (outputIdx >= 0) {
                val outputBuf = aacEncoder?.getOutputBuffer(outputIdx) ?: return null
                
                val frame = if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // AudioSpecificConfig (ASC) - MUST send once before first frame
                    if (!ascSent && info.size > 0) {
                        val asc = ByteArray(info.size)
                        outputBuf.get(asc)
                        ascSent = true
                        Log.i(TAG, "[PTL] Got ASC (AudioSpecificConfig) ${info.size}B")
                        AudioFrame(isConfig = true, data = asc, ptsUs = 0L)
                    } else {
                        null
                    }
                } else if (info.size > 0) {
                    // Regular AAC frame
                    val aac = ByteArray(info.size)
                    outputBuf.get(aac)
                    AudioFrame(isConfig = false, data = aac, ptsUs = info.presentationTimeUs)
                } else {
                    null
                }
                
                aacEncoder?.releaseOutputBuffer(outputIdx, false)
                return frame
            }
            
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "[PTL] Capture error", e)
            return null
        }
    }
    
    /**
     * Stop recording
     */
    fun stop() {
        isCapturing = false
        micRecord?.stop()
        gameRecord?.stop()
        Log.i(TAG, "[PTL] Audio capture STOPPED")
    }
    
    /**
     * Toggle microphone on/off
     */
    fun toggleMic(): Boolean {
        isMicEnabled = !isMicEnabled
        Log.i(TAG, "[PTL] Mic ${if (isMicEnabled) "ENABLED" else "DISABLED"}")
        return isMicEnabled
    }
    
    /**
     * Toggle game audio on/off
     */
    fun toggleGameAudio(): Boolean {
        isGameAudioEnabled = !isGameAudioEnabled
        Log.i(TAG, "[PTL] Game audio ${if (isGameAudioEnabled) "ENABLED" else "DISABLED"}")
        return isGameAudioEnabled
    }
    
    /**
     * Release all resources
     */
    fun cleanup() {
        stop()
        
        micRecord?.release()
        micRecord = null
        
        gameRecord?.release()
        gameRecord = null
        
        try {
            aacEncoder?.stop()
            aacEncoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "[PTL] Encoder release error", e)
        }
        aacEncoder = null
        
        Log.i(TAG, "[PTL] Audio cleanup DONE")
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Process audio frame: DC filter + headroom + soft limiter
     * Eliminates "rè rè" noise from DC offset and clipping
     */
    private fun processAudioFrame(buf: ByteArray, len: Int, dcL: DCFilter, dcR: DCFilter) {
        var i = 0
        while (i < len - 3) { // Stereo: 4 bytes per frame (L+R)
            // Read little-endian 16-bit samples
            var L = (buf[i].toInt() and 0xFF) or ((buf[i + 1].toInt() shl 8))
            var R = (buf[i + 2].toInt() and 0xFF) or ((buf[i + 3].toInt() shl 8))
            
            // Convert to signed
            L = if (L > 32767) L - 65536 else L
            R = if (R > 32767) R - 65536 else R
            
            // 1. DC offset filter (high-pass 20Hz)
            L = dcL.process(L)
            R = dcR.process(R)
            
            // 2. Headroom -6dB (0.5x gain)
            L = (L * 0.5).toInt()
            R = (R * 0.5).toInt()
            
            // 3. Soft limiter (avoid hard clipping)
            fun softClip(x: Int): Int {
                var y = x
                if (y > 30000) y = 30000 + (y - 30000) / 8
                if (y < -30000) y = -30000 + (y + 30000) / 8
                return y.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            }
            L = softClip(L)
            R = softClip(R)
            
            // Write back little-endian
            buf[i] = (L and 0xFF).toByte()
            buf[i + 1] = ((L ushr 8) and 0xFF).toByte()
            buf[i + 2] = (R and 0xFF).toByte()
            buf[i + 3] = ((R ushr 8) and 0xFF).toByte()
            
            i += 4
        }
    }
    
    /**
     * Mix two PCM buffers (scaled sum + clip)
     * @param mic Microphone PCM (already DC-filtered)
     * @param game Game/internal PCM (already DC-filtered)
     * @param out Output buffer
     * @param len Length to mix
     */
    private fun mixPcm(mic: ByteArray, game: ByteArray, out: ByteArray, len: Int) {
        var i = 0
        while (i < len - 1) {
            // 16-bit little-endian PCM
            val s1 = (mic[i].toInt() and 0xFF) or ((mic[i + 1].toInt() shl 8))
            val s2 = (game[i].toInt() and 0xFF) or ((game[i + 1].toInt() shl 8))
            
            // Convert to signed
            val mic16 = if (s1 > 32767) s1 - 65536 else s1
            val game16 = if (s2 > 32767) s2 - 65536 else s2
            
            // Mix (already headroom-adjusted, so use 0.8 here for fuller sound)
            var mixed = (mic16 * 0.8 + game16 * 0.8).toInt()
            
            // Clip to 16-bit range
            if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE.toInt()
            if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE.toInt()
            
            // Write back as little-endian
            out[i] = (mixed and 0xFF).toByte()
            out[i + 1] = ((mixed shr 8) and 0xFF).toByte()
            
            i += 2
        }
    }
    
    /**
     * Compute audio PTS (monotonic)
     */
    private fun computeAudioPts(bytesRead: Int): Long {
        val bytesPerSample = 2 // PCM_16BIT
        val frames = bytesRead / (CHANNELS * bytesPerSample)
        val incUs = frames * 1_000_000L / SAMPLE_RATE
        audioPtsUs += incUs
        return audioPtsUs
    }
    
    /**
     * Audio frame container
     */
    data class AudioFrame(
        val isConfig: Boolean,  // true = ASC, false = AAC frame
        val data: ByteArray,
        val ptsUs: Long
    )
}
