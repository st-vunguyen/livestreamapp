package com.screenlive.app

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * FLV Muxer - Wraps H.264 and AAC data into FLV tags for RTMP streaming
 */
class FlvMuxer {
    
    private var startTime = 0L
    private var videoConfigSent = false
    private var audioConfigSent = false
    
    fun start() {
        startTime = System.currentTimeMillis()
        videoConfigSent = false
        audioConfigSent = false
        Log.i(TAG, "FLV Muxer started")
    }
    
    /**
     * Create FLV Video Tag - AVC Sequence Header (SPS/PPS)
     * This MUST be sent before any video frames
     */
    fun createVideoConfigTag(sps: ByteArray, pps: ByteArray): FlvTag {
        val body = ByteArrayOutputStream()
        
        // Video tag header
        body.write(0x17) // 0x10 (keyframe) | 0x07 (AVC)
        body.write(0x00) // AVC sequence header
        body.write(0x00) // CompositionTime
        body.write(0x00)
        body.write(0x00)
        
        // AVCDecoderConfigurationRecord
        body.write(0x01) // configurationVersion
        body.write(sps[1].toInt() and 0xFF) // AVCProfileIndication
        body.write(sps[2].toInt() and 0xFF) // profile_compatibility
        body.write(sps[3].toInt() and 0xFF) // AVCLevelIndication
        body.write(0xFF) // lengthSizeMinusOne (4 bytes)
        
        // SPS
        body.write(0xE1) // numOfSequenceParameterSets
        body.write((sps.size shr 8) and 0xFF)
        body.write(sps.size and 0xFF)
        body.write(sps)
        
        // PPS
        body.write(0x01) // numOfPictureParameterSets
        body.write((pps.size shr 8) and 0xFF)
        body.write(pps.size and 0xFF)
        body.write(pps)
        
        videoConfigSent = true
        Log.i(TAG, "✓ Created AVC sequence header (SPS: ${sps.size}B, PPS: ${pps.size}B)")
        
        return FlvTag(
            type = FlvTagType.VIDEO,
            data = body.toByteArray(),
            timestamp = 0
        )
    }
    
    /**
     * Create FLV Audio Tag - AAC Sequence Header (AudioSpecificConfig)
     * This MUST be sent before any audio frames
     */
    fun createAudioConfigTag(asc: ByteArray): FlvTag {
        val body = ByteArrayOutputStream()
        
        // Audio tag header
        body.write(0xAF) // 0xA0 (AAC) | 0x0F (44kHz, 16bit, stereo)
        body.write(0x00) // AAC sequence header
        body.write(asc)
        
        audioConfigSent = true
        Log.i(TAG, "✓ Created AAC sequence header (ASC: ${asc.size}B)")
        
        return FlvTag(
            type = FlvTagType.AUDIO,
            data = body.toByteArray(),
            timestamp = 0
        )
    }
    
    /**
     * Create FLV Video Tag - AVC NALU (actual video frame)
     */
    fun createVideoTag(nalData: ByteArray, isKeyframe: Boolean, timestampUs: Long): FlvTag? {
        if (!videoConfigSent) {
            Log.w(TAG, "Cannot send video frame before AVC sequence header")
            return null
        }
        
        val body = ByteArrayOutputStream()
        
        // Video tag header
        val frameType = if (isKeyframe) 0x10 else 0x20 // keyframe or inter frame
        body.write(frameType or 0x07) // AVC
        body.write(0x01) // AVC NALU
        body.write(0x00) // CompositionTime
        body.write(0x00)
        body.write(0x00)
        
        // NALU length (4 bytes)
        body.write((nalData.size shr 24) and 0xFF)
        body.write((nalData.size shr 16) and 0xFF)
        body.write((nalData.size shr 8) and 0xFF)
        body.write(nalData.size and 0xFF)
        
        // NALU data
        body.write(nalData)
        
        val timestamp = ((timestampUs / 1000) - startTime).toInt()
        
        return FlvTag(
            type = FlvTagType.VIDEO,
            data = body.toByteArray(),
            timestamp = timestamp
        )
    }
    
    /**
     * Create FLV Audio Tag - AAC raw data
     */
    fun createAudioTag(aacData: ByteArray, timestampUs: Long): FlvTag? {
        if (!audioConfigSent) {
            Log.w(TAG, "Cannot send audio frame before AAC sequence header")
            return null
        }
        
        val body = ByteArrayOutputStream()
        
        // Audio tag header
        body.write(0xAF) // AAC, 44kHz, 16bit, stereo
        body.write(0x01) // AAC raw
        body.write(aacData)
        
        val timestamp = ((timestampUs / 1000) - startTime).toInt()
        
        return FlvTag(
            type = FlvTagType.AUDIO,
            data = body.toByteArray(),
            timestamp = timestamp
        )
    }
    
    fun stop() {
        Log.i(TAG, "FLV Muxer stopped")
    }
    
    data class FlvTag(
        val type: FlvTagType,
        val data: ByteArray,
        val timestamp: Int
    )
    
    enum class FlvTagType(val value: Int) {
        AUDIO(8),
        VIDEO(9),
        SCRIPT_DATA(18)
    }
    
    companion object {
        private const val TAG = "FlvMuxer"
    }
}
