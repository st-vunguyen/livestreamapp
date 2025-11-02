package com.screenlive.app.diagnostic

import android.app.Activity
import io.flutter.plugin.common.MethodChannel

/**
 * Diagnostic Probe Interface
 * 
 * All probes follow same pattern:
 * - execute() runs the test
 * - Returns ProbeResult with success/failure + metrics
 */
interface DiagnosticProbe {
    suspend fun execute(): ProbeResult
}

/**
 * Probe execution result
 */
data class ProbeResult(
    val success: Boolean,
    val message: String,
    val metrics: Map<String, Any> = emptyMap(),
    val error: String? = null
)

/**
 * Probe statistics for instrumentation
 */
data class ProbeStats(
    var handshakeDurationMs: Long = 0,
    var connectDurationMs: Long = 0,
    var createStreamDurationMs: Long = 0,
    var publishDurationMs: Long = 0,
    var totalDurationMs: Long = 0,
    var bytesSent: Long = 0,
    var bytesReceived: Long = 0,
    var framesGenerated: Int = 0,
    var keyframesGenerated: Int = 0,
    var lastKeyframeTimestamp: Long = 0,
    var firstFrameTimestamp: Long = 0,
    var errorMessage: String? = null
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "handshakeDurationMs" to handshakeDurationMs,
            "connectDurationMs" to connectDurationMs,
            "createStreamDurationMs" to createStreamDurationMs,
            "publishDurationMs" to publishDurationMs,
            "totalDurationMs" to totalDurationMs,
            "bytesSent" to bytesSent,
            "bytesReceived" to bytesReceived,
            "framesGenerated" to framesGenerated,
            "keyframesGenerated" to keyframesGenerated,
            "lastKeyframeTimestamp" to lastKeyframeTimestamp,
            "firstFrameTimestamp" to firstFrameTimestamp
        ).filterValues { it != 0L && it != 0 }
    }
}
