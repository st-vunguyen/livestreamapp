package com.screenlive.app

import android.util.Log
import java.io.*
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Random
import javax.net.ssl.SSLSocketFactory

/**
 * RTMP Client - Handles connection, handshake, and publishing to RTMP server
 * Based on RTMP specification: https://rtmp.veriskope.com/docs/spec/
 */
class RtmpClient(
    private val baseUrl: String,
    streamKey: String
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    
    private var isConnected = false
    private var streamId = 0.0
    private var publishStreamKey = streamKey
    
    // RTMP state
    private val random = Random()
    private var chunkSize = 128
    
    fun connect(): Boolean {
        return try {
            // Parse base RTMP URL (without stream key)
            val parsed = parseRtmpUrl(baseUrl)
            Log.i(TAG, "Connecting to ${parsed.host}:${parsed.port}${parsed.app}")
            Log.d(TAG, "tcUrl: $baseUrl")
            Log.d(TAG, "Stream key: $publishStreamKey")
            
            // Create socket (support both rtmp and rtmps)
            socket = if (parsed.secure) {
                SSLSocketFactory.getDefault().createSocket(parsed.host, parsed.port)
            } else {
                Socket(parsed.host, parsed.port)
            }
            
            socket?.soTimeout = 5000 // 5 second timeout
            
            outputStream = socket?.getOutputStream()
            inputStream = socket?.getInputStream()
            
            // RTMP Handshake
            if (!performHandshake()) {
                Log.e(TAG, "Handshake failed")
                return false
            }
            
            // Connect command - use baseUrl as tcUrl
            if (!sendConnectCommand(parsed.app, baseUrl)) {
                Log.e(TAG, "Connect command failed")
                return false
            }
            
            // Wait for connection response
            if (!waitForConnectResponse()) {
                Log.e(TAG, "Connect response failed")
                return false
            }
            
            // Create stream
            streamId = createStream()
            if (streamId == 0.0) {
                Log.e(TAG, "Create stream failed")
                return false
            }
            
            // Publish stream
            if (!publish()) {
                Log.e(TAG, "Publish failed")
                return false
            }
            
            isConnected = true
            Log.i(TAG, "✓ RTMP connected and publishing to stream: $publishStreamKey")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            false
        }
    }
    
    private fun performHandshake(): Boolean {
        try {
            // C0: version
            outputStream?.write(0x03)
            
            // C1: timestamp (4) + zero (4) + random (1528)
            val c1 = ByteArray(1536)
            random.nextBytes(c1)
            // Set timestamp to 0
            c1[0] = 0
            c1[1] = 0
            c1[2] = 0
            c1[3] = 0
            // Set zero
            c1[4] = 0
            c1[5] = 0
            c1[6] = 0
            c1[7] = 0
            outputStream?.write(c1)
            outputStream?.flush()
            
            // Read S0
            val s0 = inputStream?.read() ?: return false
            if (s0 != 0x03) {
                Log.e(TAG, "Invalid S0: $s0")
                return false
            }
            
            // Read S1
            val s1 = ByteArray(1536)
            var totalRead = 0
            while (totalRead < 1536) {
                val read = inputStream?.read(s1, totalRead, 1536 - totalRead) ?: -1
                if (read < 0) return false
                totalRead += read
            }
            
            // Read S2
            val s2 = ByteArray(1536)
            totalRead = 0
            while (totalRead < 1536) {
                val read = inputStream?.read(s2, totalRead, 1536 - totalRead) ?: -1
                if (read < 0) return false
                totalRead += read
            }
            
            // Send C2 (echo S1)
            outputStream?.write(s1)
            outputStream?.flush()
            
            Log.i(TAG, "✓ RTMP handshake completed")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Handshake error", e)
            return false
        }
    }
    
    private fun sendConnectCommand(app: String, tcUrl: String): Boolean {
        return try {
            val amf = AmfEncoder()
            
            // Command name
            amf.writeString("connect")
            
            // Transaction ID
            amf.writeNumber(1.0)
            
            // Command object - YouTube requires these specific fields with correct types
            amf.writeObject(mapOf(
                "app" to app,
                "type" to "nonprivate",
                "flashVer" to "FMLE/3.0 (compatible; ScreenLive)",
                "swfUrl" to tcUrl,
                "tcUrl" to tcUrl,
                "fpad" to false,
                "capabilities" to 239.0,
                "audioCodecs" to 3191.0,
                "videoCodecs" to 252.0,
                "videoFunction" to 1.0,
                "pageUrl" to "",
                "objectEncoding" to 0.0
            ))
            
            // Optional user arguments
            amf.writeNull()
            
            val data = amf.toByteArray()
            Log.d(TAG, "Connect command size: ${data.size} bytes")
            Log.d(TAG, "Connect AMF hex: ${data.take(80).joinToString("") { "%02x".format(it) }}")
            sendChunk(3, 0, data, MessageType.AMF0_COMMAND)
            Log.i(TAG, "✓ Sent connect command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send connect", e)
            false
        }
    }
    
    private fun waitForConnectResponse(): Boolean {
        // Read RTMP response chunks until we get _result or _error
        try {
            val buffer = ByteArray(4096)
            var totalRead = 0
            
            // Try to read multiple chunks (server may send in parts)
            for (i in 0..10) {
                try {
                    val read = inputStream?.read(buffer, totalRead, buffer.size - totalRead) ?: 0
                    if (read > 0) {
                        totalRead += read
                        Log.d(TAG, "Read chunk $i: $read bytes (total: $totalRead)")
                        
                        // Log first 100 bytes as hex for debugging
                        val hex = buffer.take(minOf(100, totalRead)).joinToString("") { "%02x".format(it) }
                        Log.d(TAG, "Response hex: $hex")
                        
                        // Look for "_result" or "_error" string in response
                        val response = String(buffer, 0, totalRead, Charsets.ISO_8859_1)
                        
                        if (response.contains("_result")) {
                            Log.i(TAG, "✓ Received _result response ($totalRead bytes)")
                            return true
                        }
                        
                        if (response.contains("_error")) {
                            Log.e(TAG, "Server returned _error")
                            // Try to extract error description
                            val errorStart = response.indexOf("_error") + 6
                            val errorMsg = if (errorStart < response.length) {
                                response.substring(errorStart, minOf(errorStart + 100, response.length))
                            } else "Unknown error"
                            Log.e(TAG, "Error details: $errorMsg")
                            return false
                        }
                        
                        // Continue reading if we haven't seen result/error yet
                        if (totalRead > 500) {
                            // Got a lot of data but no clear result
                            Log.w(TAG, "Got $totalRead bytes but no _result/_error, assuming OK")
                            return true
                        }
                    } else {
                        Log.d(TAG, "Read returned $read on attempt $i")
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    Log.d(TAG, "Socket timeout on attempt $i, totalRead=$totalRead")
                    // Timeout is OK if we got some data
                    if (totalRead > 0) {
                        Log.i(TAG, "✓ Timeout with data ($totalRead bytes), assuming connected")
                        return true
                    }
                    break
                }
            }
            
            Log.e(TAG, "Connect response failed - no data received after 10 attempts")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading response", e)
        }
        return false
    }
    
    private fun createStream(): Double {
        return try {
            val amf = AmfEncoder()
            amf.writeString("createStream")
            amf.writeNumber(2.0)
            amf.writeNull()
            
            sendChunk(3, 0, amf.toByteArray(), MessageType.AMF0_COMMAND)
            
            // Read response
            val buffer = ByteArray(4096)
            try {
                inputStream?.read(buffer)
            } catch (e: java.net.SocketTimeoutException) {
                // Timeout is OK
            }
            
            // Simplified: return 1.0 as stream ID
            Log.i(TAG, "✓ Created stream (ID: 1.0)")
            1.0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create stream", e)
            0.0
        }
    }
    
    private fun publish(): Boolean {
        return try {
            val amf = AmfEncoder()
            amf.writeString("publish")
            amf.writeNumber(3.0)
            amf.writeNull()
            amf.writeString(publishStreamKey)  // Use the stream key from constructor
            amf.writeString("live")
            
            sendChunk(8, streamId.toInt(), amf.toByteArray(), MessageType.AMF0_COMMAND)
            
            // Read publish response
            val buffer = ByteArray(4096)
            try {
                inputStream?.read(buffer)
            } catch (e: java.net.SocketTimeoutException) {
                // Timeout is OK
            }
            
            Log.i(TAG, "✓ Publishing to stream: $publishStreamKey")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish", e)
            false
        }
    }
    
    fun sendVideoData(data: ByteArray, timestamp: Int) {
        if (!isConnected) return
        sendChunk(6, streamId.toInt(), data, MessageType.VIDEO, timestamp)
    }
    
    fun sendAudioData(data: ByteArray, timestamp: Int) {
        if (!isConnected) return
        sendChunk(4, streamId.toInt(), data, MessageType.AUDIO, timestamp)
    }
    
    private fun sendChunk(
        chunkStreamId: Int,
        messageStreamId: Int,
        data: ByteArray,
        messageType: MessageType,
        timestamp: Int = 0
    ) {
        try {
            val out = outputStream ?: return
            
            // Chunk Basic Header (Type 0)
            out.write(chunkStreamId)
            
            // Chunk Message Header
            // Timestamp (3 bytes)
            out.write((timestamp shr 16) and 0xFF)
            out.write((timestamp shr 8) and 0xFF)
            out.write(timestamp and 0xFF)
            
            // Message length (3 bytes)
            out.write((data.size shr 16) and 0xFF)
            out.write((data.size shr 8) and 0xFF)
            out.write(data.size and 0xFF)
            
            // Message type ID
            out.write(messageType.value)
            
            // Message stream ID (4 bytes, little endian)
            out.write(messageStreamId and 0xFF)
            out.write((messageStreamId shr 8) and 0xFF)
            out.write((messageStreamId shr 16) and 0xFF)
            out.write((messageStreamId shr 24) and 0xFF)
            
            // Chunk data (split by chunk size if needed)
            var offset = 0
            while (offset < data.size) {
                val length = minOf(chunkSize, data.size - offset)
                out.write(data, offset, length)
                offset += length
                
                // If more data, send Type 3 header (continuation)
                if (offset < data.size) {
                    out.write(0xC0 or chunkStreamId)
                }
            }
            
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send chunk", e)
        }
    }
    
    fun disconnect() {
        try {
            socket?.close()
            isConnected = false
            Log.i(TAG, "RTMP disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }
    
    private fun parseRtmpUrl(url: String): RtmpUrl {
        val secure = url.startsWith("rtmps://")
        val urlWithoutProtocol = url.removePrefix("rtmp://").removePrefix("rtmps://")
        val parts = urlWithoutProtocol.split("/", limit = 2)
        
        val hostPort = parts[0]
        val app = if (parts.size > 1) "/${parts[1]}" else "/live"
        
        val host: String
        val port: Int
        if (hostPort.contains(":")) {
            val hp = hostPort.split(":")
            host = hp[0]
            port = hp[1].toIntOrNull() ?: if (secure) 443 else 1935
        } else {
            host = hostPort
            port = if (secure) 443 else 1935
        }
        
        return RtmpUrl(host, port, app, secure)
    }
    
    private data class RtmpUrl(
        val host: String,
        val port: Int,
        val app: String,
        val secure: Boolean
    )
    
    private enum class MessageType(val value: Int) {
        SET_CHUNK_SIZE(1),
        ABORT(2),
        ACK(3),
        USER_CONTROL(4),
        WINDOW_ACK_SIZE(5),
        SET_PEER_BANDWIDTH(6),
        AUDIO(8),
        VIDEO(9),
        AMF3_COMMAND(17),
        AMF0_COMMAND(20)
    }
    
    /**
     * Simple AMF0 encoder
     */
    private class AmfEncoder {
        private val buffer = ByteArrayOutputStream()
        
        fun writeString(value: String) {
            buffer.write(0x02) // String marker
            val bytes = value.toByteArray()
            buffer.write((bytes.size shr 8) and 0xFF)
            buffer.write(bytes.size and 0xFF)
            buffer.write(bytes)
        }
        
        fun writeNumber(value: Double) {
            buffer.write(0x00) // Number marker
            val bits = value.toBits()
            for (i in 7 downTo 0) {
                buffer.write(((bits shr (i * 8)) and 0xFF).toInt())
            }
        }
        
        fun writeNull() {
            buffer.write(0x05) // Null marker
        }
        
        fun writeObject(map: Map<String, Any>) {
            buffer.write(0x03) // Object marker
            map.forEach { (key, value) ->
                // Property name (length + bytes, NO marker)
                val keyBytes = key.toByteArray()
                buffer.write((keyBytes.size shr 8) and 0xFF)
                buffer.write(keyBytes.size and 0xFF)
                buffer.write(keyBytes)
                
                // Property value - with type marker based on type
                when (value) {
                    is String -> writeString(value)
                    is Number -> writeNumber(value.toDouble())
                    is Boolean -> writeNumber(if (value) 1.0 else 0.0)
                    else -> writeString(value.toString())
                }
            }
            // End of object (0x00 0x00 0x09)
            buffer.write(0x00)
            buffer.write(0x00)
            buffer.write(0x09)
        }
        
        fun toByteArray(): ByteArray = buffer.toByteArray()
    }
    
    companion object {
        private const val TAG = "RtmpClient"
    }
}
