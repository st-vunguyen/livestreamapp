package com.screenlive.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 Video Encoder for screen capture
 * Encodes frames from VirtualDisplay surface to H.264/AVC format
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateMbps: Int
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var isRunning = false
    
    fun getSurface(): Surface? = inputSurface
    
    fun start(): Boolean {
        return try {
            val bitrateKbps = bitrateMbps * 1_000_000
            
            Log.i(TAG, "Configuring H.264 encoder: ${width}x${height}@${fps}fps, bitrate=${bitrateKbps}bps")
            
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // Keyframe every 2 seconds
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // High profile for better quality
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
                    // CBR for stable RTMP streaming
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
            }
            
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec?.createInputSurface()
            codec?.start()
            
            isRunning = true
            Log.i(TAG, "✓ Video encoder started successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video encoder", e)
            false
        }
    }
    
    fun dequeueOutputBuffer(timeoutUs: Long = 10_000): EncodedFrame? {
        if (!isRunning || codec == null) return null
        
        val bufferInfo = MediaCodec.BufferInfo()
        val index = codec!!.dequeueOutputBuffer(bufferInfo, timeoutUs)
        
        return when {
            index >= 0 -> {
                val buffer = codec!!.getOutputBuffer(index) ?: return null
                val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                
                // Extract SPS/PPS for keyframes
                val data = ByteArray(bufferInfo.size)
                buffer.get(data)
                buffer.rewind()
                
                val frame = EncodedFrame(
                    data = data,
                    isKeyframe = isKeyframe,
                    timestampUs = bufferInfo.presentationTimeUs,
                    bufferIndex = index
                )
                
                if (isKeyframe) {
                    Log.d(TAG, "Keyframe encoded: ${data.size} bytes, pts=${bufferInfo.presentationTimeUs}us")
                }
                
                frame
            }
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val format = codec!!.outputFormat
                Log.i(TAG, "Encoder output format changed: $format")
                null
            }
            else -> null
        }
    }
    
    fun releaseOutputBuffer(index: Int) {
        codec?.releaseOutputBuffer(index, false)
    }
    
    fun stop() {
        isRunning = false
        try {
            inputSurface?.release()
            codec?.stop()
            codec?.release()
            Log.i(TAG, "Video encoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping video encoder", e)
        }
        codec = null
        inputSurface = null
    }
    
    fun extractSpssPps(keyframeData: ByteArray): Pair<ByteArray?, ByteArray?> {
        // Parse H.264 NAL units to extract SPS and PPS
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        
        var offset = 0
        while (offset < keyframeData.size - 4) {
            // Find start code (0x00 0x00 0x00 0x01)
            if (keyframeData[offset].toInt() == 0 && 
                keyframeData[offset + 1].toInt() == 0 && 
                keyframeData[offset + 2].toInt() == 0 && 
                keyframeData[offset + 3].toInt() == 1) {
                
                // NAL unit type is in bits 0-4 of the byte after start code
                val nalType = keyframeData[offset + 4].toInt() and 0x1F
                
                // Find next start code
                var nextOffset = offset + 4
                while (nextOffset < keyframeData.size - 4) {
                    if (keyframeData[nextOffset].toInt() == 0 && 
                        keyframeData[nextOffset + 1].toInt() == 0 && 
                        keyframeData[nextOffset + 2].toInt() == 0 && 
                        keyframeData[nextOffset + 3].toInt() == 1) {
                        break
                    }
                    nextOffset++
                }
                
                val nalData = keyframeData.copyOfRange(offset + 4, nextOffset)
                
                when (nalType) {
                    7 -> sps = nalData // SPS
                    8 -> pps = nalData // PPS
                }
                
                offset = nextOffset
            } else {
                offset++
            }
        }
        
        return Pair(sps, pps)
    }
    
    data class EncodedFrame(
        val data: ByteArray,
        val isKeyframe: Boolean,
        val timestampUs: Long,
        val bufferIndex: Int
    )
    
    companion object {
        private const val TAG = "VideoEncoder"
    }
}
