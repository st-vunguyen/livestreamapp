package com.screenlive.app

import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

/**
 * AAC Audio Encoder
 * Supports both AudioPlaybackCapture (Android 10+) and Microphone fallback
 */
class AudioEncoder(
    private val projection: MediaProjection?,
    private val useMicrophone: Boolean = false
) {
    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var isRunning = false
    private var channelCount = 2
    
    fun start(): Boolean {
        return try {
            // Try AudioPlaybackCapture first (Android 10+)
            if (!useMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projection != null) {
                try {
                    Log.i(TAG, "Attempting AudioPlaybackCapture (Game Audio)")
                    val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .build()
                    
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                    
                    audioRecord = AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(SAMPLE_RATE * 2 * 2) // 48kHz * 16bit * stereo
                        .setAudioPlaybackCaptureConfig(config)
                        .build()
                    
                    channelCount = 2
                    Log.i(TAG, "✓ AudioPlaybackCapture configured (stereo)")
                } catch (e: Exception) {
                    Log.w(TAG, "AudioPlaybackCapture not supported, falling back to microphone", e)
                    audioRecord = null
                }
            }
            
            // Fallback to microphone
            if (audioRecord == null) {
                Log.i(TAG, "Using Microphone fallback (mono)")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    SAMPLE_RATE * 2
                )
                channelCount = 1
            }
            
            // Start recording
            audioRecord?.startRecording()
            Log.i(TAG, "✓ AudioRecord started")
            
            // Configure AAC encoder
            val aacFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (channelCount == 2) 128_000 else 96_000)
            }
            
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec?.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec?.start()
            
            isRunning = true
            Log.i(TAG, "✓ AAC encoder started: ${SAMPLE_RATE}Hz, ${channelCount}ch, ${if (channelCount == 2) 128 else 96}kbps")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio encoder", e)
            false
        }
    }
    
    fun readAndEncode(): EncodedAudio? {
        if (!isRunning || audioRecord == null || codec == null) return null
        
        // Read PCM data from AudioRecord
        val pcmBuffer = ByteArray(BUFFER_SIZE)
        val read = audioRecord!!.read(pcmBuffer, 0, pcmBuffer.size)
        
        if (read > 0) {
            // Queue PCM data to encoder
            val inputIndex = codec!!.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = codec!!.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(pcmBuffer, 0, read)
                
                val timestamp = System.nanoTime() / 1000 // Convert to microseconds
                codec!!.queueInputBuffer(inputIndex, 0, read, timestamp, 0)
            }
        }
        
        // Dequeue encoded AAC data
        val bufferInfo = MediaCodec.BufferInfo()
        val outputIndex = codec!!.dequeueOutputBuffer(bufferInfo, 10_000)
        
        return when {
            outputIndex >= 0 -> {
                val buffer = codec!!.getOutputBuffer(outputIndex) ?: return null
                val data = ByteArray(bufferInfo.size)
                buffer.get(data)
                
                val encoded = EncodedAudio(
                    data = data,
                    timestampUs = bufferInfo.presentationTimeUs,
                    bufferIndex = outputIndex
                )
                
                encoded
            }
            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val format = codec!!.outputFormat
                Log.i(TAG, "Audio encoder output format changed: $format")
                null
            }
            else -> null
        }
    }
    
    fun releaseOutputBuffer(index: Int) {
        codec?.releaseOutputBuffer(index, false)
    }
    
    fun extractAudioSpecificConfig(): ByteArray? {
        // Extract AAC AudioSpecificConfig from encoder output format
        return try {
            val format = codec?.outputFormat
            val csd0 = format?.getByteBuffer("csd-0")
            csd0?.let {
                val asc = ByteArray(it.remaining())
                it.get(asc)
                Log.i(TAG, "Extracted AudioSpecificConfig: ${asc.size} bytes")
                asc
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract AudioSpecificConfig", e)
            null
        }
    }
    
    fun stop() {
        isRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            codec?.stop()
            codec?.release()
            Log.i(TAG, "Audio encoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio encoder", e)
        }
        audioRecord = null
        codec = null
    }
    
    fun isUsingPlaybackCapture(): Boolean = channelCount == 2
    
    data class EncodedAudio(
        val data: ByteArray,
        val timestampUs: Long,
        val bufferIndex: Int
    )
    
    companion object {
        private const val TAG = "AudioEncoder"
        private const val SAMPLE_RATE = 48000
        private const val BUFFER_SIZE = 4096
    }
}
