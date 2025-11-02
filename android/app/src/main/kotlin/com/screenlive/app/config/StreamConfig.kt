package com.screenlive.app.config

/**
 * Streaming configuration presets and tunable parameters.
 * All values are YouTube Live compatible.
 */
object StreamConfig {
    
    // RTMP Protocol Settings
    var rtmpChunkSize: Int = 4096  // Bytes per chunk (balance overhead vs latency)
    var ackWindowThreshold: Double = 0.6  // Send ACK at 60% of windowAckSize (1.5MB / 2.5MB)
    var maxReconnectAttempts: Int = 5
    var initialBackoffMs: Long = 500  // Exponential backoff: 0.5s → 1s → 2s → 4s → 8s
    
    // Encoder Presets
    enum class Preset(
        val width: Int,
        val height: Int,
        val fps: Int,
        val videoBitrate: Int,
        val audioBitrate: Int,
        val profile: Int,  // AVCProfileHigh = 8
        val level: Int,    // AVCLevel41 = 0x200, AVCLevel42 = 0x400
        val gopSeconds: Int,
        val bFrames: Int
    ) {
        /**
         * Stable 720p60 preset for YouTube Live.
         * Balanced quality/stability for most devices and networks.
         */
        PRESET_720P60_STABLE(
            width = 1280,
            height = 720,
            fps = 60,
            videoBitrate = 6_000_000,  // 6 Mbps (YouTube recommended 4.5-7.5 Mbps)
            audioBitrate = 160_000,    // 160 kbps AAC
            profile = 8,  // AVCProfileHigh
            level = 0x200,  // AVCLevel41 (4.1)
            gopSeconds = 2,  // Keyframe every 120 frames (2s @ 60fps)
            bFrames = 2
        ),
        
        /**
         * High quality 1080p60 preset for YouTube Live.
         * Requires strong device (Snapdragon 8xx+) and stable 12+ Mbps upload.
         */
        PRESET_1080P60_QUALITY(
            width = 1920,
            height = 1080,
            fps = 60,
            videoBitrate = 9_000_000,  // 9 Mbps (YouTube recommended 7.5-12 Mbps)
            audioBitrate = 192_000,    // 192 kbps AAC stereo
            profile = 8,  // AVCProfileHigh
            level = 0x400,  // AVCLevel42 (4.2)
            gopSeconds = 2,
            bFrames = 2
        )
    }
    
    // Active preset (can be changed at runtime before stream starts)
    var activePreset: Preset = Preset.PRESET_720P60_STABLE
    
    // Low latency mode (strict GOP, no deep B-frame lookahead)
    var lowLatencyMode: Boolean = true
    
    // Audio settings (shared across presets)
    const val AUDIO_SAMPLE_RATE = 48000
    const val AUDIO_CHANNEL_COUNT = 1  // Mono for now (stereo = 2)
    const val AUDIO_AAC_PROFILE = 2  // AAC-LC
    
    // VBV buffer size multiplier (for CBR stability)
    const val VBV_BUFFER_MULTIPLIER = 2.0
}
