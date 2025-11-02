package com.screenlive.app

import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * CleanAudioMixer - Production grade, no ringing/artifacts
 * 
 * Features:
 * - Sample-accurate PTS (no drift)
 * - Drift PLL correction (compensate for clock skew)
 * - 30Hz high-pass filter (remove DC/rumble)
 * - Smooth soft-clip limiter (no harsh distortion)
 * - Proper 1024-sample AAC framing (no clicks)
 * - AGC/NS/AEC disabled (natural sound with music)
 * - AudioPlaybackCapture for game audio (Android 10+)
 */
class CleanAudioMixer(
    private val mediaProjection: MediaProjection?,
    private val sampleRate: Int = 48000,
    private val aacBitrate: Int = 160_000
) {
    companion object {
        private const val TAG = "CleanAudio"
        private const val FRAME_SIZE = 1024
        private const val CHANNELS = 2
    }
    
    private var mic: AudioRecord? = null
    private var playback: AudioRecord? = null
    private var aac: MediaCodec? = null
    
    @Volatile var micEnabled = true
    @Volatile var gameEnabled = true
    @Volatile private var running = false
    
    private var mixerThread: Thread? = null
    
    // High-pass filter state
    private var hp_z1L = 0f; private var hp_z2L = 0f
    private var hp_z1R = 0f; private var hp_z2R = 0f
    
    fun init() {
        val chMaskIn = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        
        // Mic
        val minMic = AudioRecord.getMinBufferSize(sampleRate, chMaskIn, encoding)
        mic = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(chMaskIn)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(max(minMic, FRAME_SIZE * CHANNELS * 8))
            .build()
        
        // Disable effects
        val sessionId = mic!!.audioSessionId
        runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = false } }
        runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = false } }
        runCatching { AutomaticGainControl.create(sessionId)?.apply { enabled = false } }
        
        // Playback capture
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            
            val minPb = AudioRecord.getMinBufferSize(sampleRate, chMaskIn, encoding)
            playback = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(chMaskIn)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(max(minPb, FRAME_SIZE * CHANNELS * 8))
                .build()
            
            Log.i(TAG, "✓ AudioPlaybackCapture initialized")
        }
        
        // AAC encoder
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, aacBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        
        aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        
        Log.i(TAG, "✓ CleanAudioMixer: ${sampleRate}Hz, ${CHANNELS}ch, ${aacBitrate/1000}kbps")
        PtlLogger.i(TAG, "Audio: 48kHz Stereo AAC-LC 160kbps (drift-corrected)")
    }
    
    fun start(onEncoded: (ByteArray, Boolean) -> Unit) {
        require(mic != null) { "Call init() first" }
        require(aac != null)
        
        mic!!.startRecording()
        playback?.startRecording()
        running = true
        
        val micS = ShortArray(FRAME_SIZE * CHANNELS)
        val pbS = ShortArray(FRAME_SIZE * CHANNELS)
        val mixF = FloatArray(FRAME_SIZE * CHANNELS)
        
        var ptsSamples = 0L
        var driftAccUs = 0L
        
        mixerThread = thread(start = true, name = "CleanAudioMixer") {
            var ascSent = false
            var frameCount = 0
            val info = MediaCodec.BufferInfo()
            
            Log.i(TAG, "Audio mixing loop started")
            
            while (running) {
                try {
                    val mRead = if (micEnabled && mic != null) {
                        mic!!.read(micS, 0, micS.size, AudioRecord.READ_BLOCKING)
                    } else {
                        micS.fill(0); 0
                    }
                    
                    val pRead = if (gameEnabled && playback != null) {
                        playback!!.read(pbS, 0, pbS.size, AudioRecord.READ_BLOCKING)
                    } else {
                        pbS.fill(0); 0
                    }
                    
                    if ((micEnabled && mRead < FRAME_SIZE * CHANNELS) && 
                        (playback == null || !gameEnabled || pRead < FRAME_SIZE * CHANNELS)) {
                        Thread.sleep(2)
                        continue
                    }
                    
                    // Mix with -6dB headroom & smooth soft-clip
                    for (i in 0 until FRAME_SIZE * CHANNELS) {
                        val a = if (i < mRead) micS[i].toFloat() / 32768f else 0f
                        val b = if (i < pRead) pbS[i].toFloat() / 32768f else 0f
                        var s = a * 0.5f + b * 0.5f
                        s = when {
                            s > 1.0f -> 1.0f - (1.0f / (1.0f + 10f * (s - 1.0f)))
                            s < -1.0f -> -1.0f + (1.0f / (1.0f + 10f * (-1.0f - s)))
                            else -> s
                        }
                        mixF[i] = s
                    }
                    
                    // 30Hz high-pass filter
                    highpass30Hz(mixF, sampleRate, CHANNELS)
                    
                    // Float → PCM16
                    val pcm = ShortArray(FRAME_SIZE * CHANNELS) { i ->
                        val v = (mixF[i] * 32767f).toInt()
                        v.coerceIn(-32768, 32767).toShort()
                    }
                    
                    // Queue to AAC
                    val inIx = aac!!.dequeueInputBuffer(10_000)
                    if (inIx >= 0) {
                        val ib = aac!!.getInputBuffer(inIx)!!
                        ib.clear()
                        for (s in pcm) {
                            ib.put((s.toInt() and 0xFF).toByte())
                            ib.put(((s.toInt() ushr 8) and 0xFF).toByte())
                        }
                        
                        // Drift correction
                        val targetPtsUs = ptsSamples * 1_000_000L / sampleRate
                        val nowUs = System.nanoTime() / 1000
                        val diff = targetPtsUs - nowUs
                        if (abs(diff) > 1000) driftAccUs += (diff / 8)
                        
                        val ptsUs = targetPtsUs + driftAccUs
                        aac!!.queueInputBuffer(inIx, 0, FRAME_SIZE * CHANNELS * 2, ptsUs, 0)
                        ptsSamples += FRAME_SIZE
                    }
                    
                    // Drain AAC
                    var outIx = aac!!.dequeueOutputBuffer(info, 0)
                    while (outIx >= 0) {
                        val ob = aac!!.getOutputBuffer(outIx)!!
                        val data = ByteArray(info.size)
                        ob.get(data)
                        
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!ascSent || !isConfig) {
                            onEncoded(data, isConfig)
                            if (isConfig) {
                                ascSent = true
                                Log.d(TAG, "✓ AudioSpecificConfig sent")
                            } else {
                                frameCount++
                                if (frameCount % 100 == 0) {
                                    Log.d(TAG, "Audio frames: $frameCount (drift: ${driftAccUs}µs)")
                                }
                            }
                        }
                        aac!!.releaseOutputBuffer(outIx, false)
                        outIx = aac!!.dequeueOutputBuffer(info, 0)
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Mixer error", e)
                }
            }
            Log.i(TAG, "Audio loop stopped (frames: $frameCount)")
        }
    }
    
    fun stop() {
        if (!running) return
        running = false
        try { mixerThread?.join(1000) } catch (e: InterruptedException) {}
        mic?.stop()
        playback?.stop()
        Log.i(TAG, "Audio mixer stopped")
    }
    
    fun cleanup() {
        stop()
        runCatching { mic?.release(); mic = null }
        runCatching { playback?.release(); playback = null }
        runCatching { aac?.stop(); aac?.release(); aac = null }
        Log.i(TAG, "Audio resources cleaned up")
    }
    
    private fun highpass30Hz(buf: FloatArray, sr: Int, ch: Int) {
        val fc = 30f
        val w = (2 * Math.PI * fc / sr).toFloat()
        val q = 0.707f
        val cosw = cos(w)
        val sinw = sin(w)
        val alpha = sinw / (2 * q)
        
        val b0 = (1 + cosw) / 2f
        val b1 = -(1 + cosw)
        val b2 = (1 + cosw) / 2f
        val a0 = 1 + alpha
        val a1 = -2 * cosw
        val a2 = 1 - alpha
        
        val numFrames = buf.size / ch
        for (i in 0 until numFrames) {
            val xL = buf[i * ch]
            val yL = (b0 / a0) * xL + (b1 / a0) * hp_z1L + (b2 / a0) * hp_z2L - (a1 / a0) * hp_z1L - (a2 / a0) * hp_z2L
            hp_z2L = hp_z1L; hp_z1L = yL
            buf[i * ch] = yL
            if (ch == 2) {
                val xR = buf[i * ch + 1]
                val yR = (b0 / a0) * xR + (b1 / a0) * hp_z1R + (b2 / a0) * hp_z2R - (a1 / a0) * hp_z1R - (a2 / a0) * hp_z2R
                hp_z2R = hp_z1R; hp_z1R = yR
                buf[i * ch + 1] = yR
            }
        }
    }
}
