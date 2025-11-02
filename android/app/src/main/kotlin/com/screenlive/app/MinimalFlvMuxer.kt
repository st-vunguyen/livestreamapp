package com.screenlive.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal FLV Muxer for MVP
 * 
 * Spec: https://www.adobe.com/content/dam/acom/en/devnet/flv/video_file_format_spec_v10.pdf
 */
class MinimalFlvMuxer {
    
    fun createFlvHeader(): ByteArray {
        // FLV signature + version + flags (video+audio) + header size
        return byteArrayOf(
            0x46, 0x4C, 0x56,  // 'FLV'
            0x01,              // version 1
            0x05,              // flags: video + audio
            0x00, 0x00, 0x00, 0x09  // header size 9 bytes
        ) + byteArrayOf(0, 0, 0, 0)  // PreviousTagSize0 = 0
    }
    
    fun createMetadata(width: Int, height: Int, fps: Int, audioSampleRate: Int): ByteArray {
        // Simplified onMetaData script data
        val scriptData = mutableListOf<Byte>()
        
        // AMF0 string "onMetaData"
        scriptData.add(0x02)  // AMF string type
        scriptData.add(0x00)
        scriptData.add(0x0A)  // length = 10
        scriptData.addAll("onMetaData".toByteArray().toList())
        
        // AMF0 object
        scriptData.add(0x08)  // ECMA array type
        scriptData.addAll(intToBytes(5))  // 5 properties
        
        // width
        addProperty(scriptData, "width", width.toDouble())
        
        // height
        addProperty(scriptData, "height", height.toDouble())
        
        // framerate
        addProperty(scriptData, "framerate", fps.toDouble())
        
        // videocodecid (7 = AVC/H.264)
        addProperty(scriptData, "videocodecid", 7.0)
        
        // audiocodecid (10 = AAC)
        addProperty(scriptData, "audiocodecid", 10.0)
        
        // Object end marker
        scriptData.add(0x00)
        scriptData.add(0x00)
        scriptData.add(0x09)
        
        return createTag(0x12, scriptData.toByteArray(), 0)  // 0x12 = script data
    }
    
    /**
     * PTL FIX: Create video config tag (SPS/PPS)
     * AVCPacketType = 0x00 (AVCDecoderConfigurationRecord)
     * 
     * IMPORTANT: For RTMP streaming, return ONLY the payload data
     * (no FLV tag header, no PreviousTagSize)
     */
    fun createVideoConfigTag(configData: ByteArray, timestamp: Int): ByteArray {
        val frameType = 0x10  // Keyframe (bits 7-4: 1=keyframe)
        val codecId = 0x07    // AVC/H.264 (bits 3-0: 7=AVC)
        val header = byteArrayOf(
            (frameType or codecId).toByte(),
            0x00,  // AVCPacketType = 0 (AVCDecoderConfigurationRecord)
            0x00, 0x00, 0x00  // CompositionTime = 0 (24-bit, big-endian)
        )
        
        // For RTMP: return payload only (no FLV tag wrapper)
        return header + configData
    }
    
    fun createVideoTag(data: ByteArray, timestamp: Int, isKeyframe: Boolean): ByteArray {
        // AVC NALU format for RTMP:
        // [frame type + codec ID] [AVC packet type] [composition time] [data]
        
        val frameType = if (isKeyframe) 0x10 else 0x20  // 1=key, 2=inter (bits 7-4)
        val codecId = 0x07  // AVC (bits 3-0)
        val header = byteArrayOf(
            (frameType or codecId).toByte(),
            0x01,  // AVCPacketType = 1 (AVC NALU)
            0x00, 0x00, 0x00  // CompositionTime = 0
        )
        
        // For RTMP: return payload only
        return header + data
    }
    
    /**
     * PTL FIX: Create audio config tag (AudioSpecificConfig)
     * AACPacketType = 0x00 (sequence header)
     * 
     * FLV Audio Tag Format:
     * Bits 7-4: Sound Format (10 = AAC)
     * Bits 3-2: Sound Rate (3 = 44kHz, used for 48kHz too)
     * Bit 1: Sound Size (1 = 16-bit)
     * Bit 0: Sound Type (0 = mono, 1 = stereo)
     * 
     * For RTMP: return payload only (no FLV tag wrapper)
     */
    fun createAudioConfigTag(configData: ByteArray, timestamp: Int): ByteArray {
        // Format byte: [10(AAC) << 4] | [3(44k) << 2] | [1(16bit) << 1] | [0(mono)]
        // = 0xA0 | 0x0C | 0x02 | 0x00 = 0xAE for mono
        val soundFormat = (10 shl 4)  // 0xA0 - AAC
        val soundRate = (3 shl 2)     // 0x0C - 44kHz/48kHz
        val soundSize = (1 shl 1)     // 0x02 - 16-bit
        val soundType = 0             // 0x00 - mono (we're using CHANNEL_IN_MONO)
        
        val header = byteArrayOf(
            (soundFormat or soundRate or soundSize or soundType).toByte(),
            0x00  // AACPacketType = 0 (sequence header/config)
        )
        
        // For RTMP: return payload only
        return header + configData
    }
    
    fun createAudioTag(data: ByteArray, timestamp: Int): ByteArray {
        // AAC format for RTMP:
        // [sound format byte] [AAC packet type] [data]
        
        val soundFormat = (10 shl 4)  // 0xA0 - AAC
        val soundRate = (3 shl 2)     // 0x0C - 44kHz/48kHz
        val soundSize = (1 shl 1)     // 0x02 - 16-bit
        val soundType = 0             // 0x00 - mono
        
        val header = byteArrayOf(
            (soundFormat or soundRate or soundSize or soundType).toByte(),
            0x01  // AACPacketType = 1 (AAC raw data)
        )
        
        // For RTMP: return payload only
        return header + data
    }
    
    // ==================== Helpers ====================
    
    private fun createTag(tagType: Int, data: ByteArray, timestamp: Int): ByteArray {
        val dataSize = data.size
        val tag = ByteBuffer.allocate(11 + dataSize + 4).order(ByteOrder.BIG_ENDIAN)
        
        // Tag header (11 bytes)
        tag.put(tagType.toByte())
        tag.put((dataSize shr 16).toByte())
        tag.put((dataSize shr 8).toByte())
        tag.put(dataSize.toByte())
        tag.put((timestamp shr 16).toByte())
        tag.put((timestamp shr 8).toByte())
        tag.put(timestamp.toByte())
        tag.put((timestamp shr 24).toByte())  // timestamp extended
        tag.put(0)  // stream ID
        tag.put(0)
        tag.put(0)
        
        // Data
        tag.put(data)
        
        // PreviousTagSize
        val tagSize = 11 + dataSize
        tag.putInt(tagSize)
        
        return tag.array()
    }
    
    private fun addProperty(list: MutableList<Byte>, name: String, value: Double) {
        // Property name (string without type marker in ECMA array)
        list.add((name.length shr 8).toByte())
        list.add(name.length.toByte())
        list.addAll(name.toByteArray().toList())
        
        // Property value (AMF0 number)
        list.add(0x00)  // AMF number type
        list.addAll(doubleToBytes(value).toList())
    }
    
    private fun intToBytes(value: Int): List<Byte> {
        return listOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }
    
    private fun doubleToBytes(value: Double): ByteArray {
        val bits = java.lang.Double.doubleToLongBits(value)
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(bits).array()
    }
}
