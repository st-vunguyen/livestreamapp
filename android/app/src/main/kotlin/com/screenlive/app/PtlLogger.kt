package com.screenlive.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * PTL Diagnostic Logger
 * 
 * Features:
 * - Centralized [PTL] tag prefix
 * - Stream key masking
 * - In-memory ring buffer (last 200 lines)
 * - Export capability for screenshot/file
 */
object PtlLogger {
    private const val TAG_PREFIX = "[PTL]"
    private const val BUFFER_SIZE = 200
    
    private val logBuffer = ConcurrentLinkedQueue<LogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    
    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )
    
    // ==================== Public API ====================
    
    fun i(tag: String, message: String) {
        val masked = maskSensitive(message)
        Log.i("$TAG_PREFIX $tag", masked)
        addToBuffer("I", tag, masked)
    }
    
    fun d(tag: String, message: String) {
        val masked = maskSensitive(message)
        Log.d("$TAG_PREFIX $tag", masked)
        addToBuffer("D", tag, masked)
    }
    
    fun w(tag: String, message: String) {
        val masked = maskSensitive(message)
        Log.w("$TAG_PREFIX $tag", masked)
        addToBuffer("W", tag, masked)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val masked = maskSensitive(message)
        if (throwable != null) {
            Log.e("$TAG_PREFIX $tag", masked, throwable)
        } else {
            Log.e("$TAG_PREFIX $tag", masked)
        }
        addToBuffer("E", tag, masked + (throwable?.let { "\n${it.stackTraceToString()}" } ?: ""))
    }
    
    /**
     * Get last N log entries
     */
    fun getRecentLogs(count: Int = BUFFER_SIZE): List<LogEntry> {
        return logBuffer.toList().takeLast(count)
    }
    
    /**
     * Export logs as formatted string
     */
    fun exportLogs(): String {
        val sb = StringBuilder()
        sb.appendLine("=== PTL Diagnostic Logs ===")
        sb.appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine()
        
        getRecentLogs().forEach { entry ->
            val time = dateFormat.format(Date(entry.timestamp))
            sb.appendLine("$time [${entry.level}] ${entry.tag}: ${entry.message}")
        }
        
        return sb.toString()
    }
    
    /**
     * Clear buffer
     */
    fun clear() {
        logBuffer.clear()
    }
    
    // ==================== Helpers ====================
    
    private fun addToBuffer(level: String, tag: String, message: String) {
        logBuffer.offer(LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        ))
        
        // Maintain buffer size
        while (logBuffer.size > BUFFER_SIZE) {
            logBuffer.poll()
        }
    }
    
    /**
     * Mask stream keys and sensitive data
     * 
     * Patterns:
     * - "Key: xxxx" -> "Key: ***last4"
     * - "streamKey=xxxx" -> "streamKey=***last4"
     * - URLs with embedded keys -> masked
     */
    private fun maskSensitive(message: String): String {
        var masked = message
        
        // Pattern 1: Key: <value>
        masked = masked.replace(Regex("""Key:\s*([^\s]{4,})""")) { match ->
            val key = match.groupValues[1]
            "Key: ***${key.takeLast(4)}"
        }
        
        // Pattern 2: streamKey= or stream_key=
        masked = masked.replace(Regex("""(streamKey|stream_key)=([^\s&]{4,})""")) { match ->
            val key = match.groupValues[2]
            "${match.groupValues[1]}=***${key.takeLast(4)}"
        }
        
        // Pattern 3: RTMP URL with key in path (heuristic)
        // rtmps://host/app/xxxx-yyyy-zzzz -> rtmps://host/app/***zzzz
        masked = masked.replace(Regex("""(rtmps?://[^/]+/[^/]+/)([a-zA-Z0-9_-]{8,})""")) { match ->
            val key = match.groupValues[2]
            if (key.length >= 8) {
                "${match.groupValues[1]}***${key.takeLast(4)}"
            } else {
                match.value  // Too short, probably not a key
            }
        }
        
        return masked
    }
    
    /**
     * Format bytes for human-readable output
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
    
    /**
     * Format duration (milliseconds) to human-readable
     */
    fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return when {
            hours > 0 -> String.format("%dh %02dm %02ds", hours, minutes % 60, seconds % 60)
            minutes > 0 -> String.format("%dm %02ds", minutes, seconds % 60)
            else -> String.format("%ds", seconds)
        }
    }
}
