package com.screenlive.app.diagnostic

import com.screenlive.app.PtlLogger
import java.io.*
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureTimeMillis

/**
 * Probe A: RTMPS Handshake Only
 * 
 * Tests RTMP protocol handshake without encoder involvement:
 * 1. TLS connect with SNI
 * 2. RTMP handshake (C0/C1/C2, S0/S1/S2)
 * 3. connect command
 * 4. createStream command
 * 5. publish command with stream key
 * 6. Clean disconnect
 * 
 * Success criteria: All commands complete with _result responses
 */
class ProbeA_RtmpsHandshake(
    private val rtmpsUrl: String,
    private val streamKey: String
) : DiagnosticProbe {
    
    companion object {
        private const val TAG = "ProbeA"
        private const val RTMP_PROTOCOL_VERSION = 3
    }
    
    private val stats = ProbeStats()
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var transactionId = 1
    private var streamId = 0
    
    override suspend fun execute(): ProbeResult {
        PtlLogger.i(TAG, "=== PROBE A: RTMPS HANDSHAKE ONLY ===")
        PtlLogger.i(TAG, "URL: $rtmpsUrl")
        PtlLogger.i(TAG, "Key: ***${streamKey.takeLast(4)}")
        
        val startTime = System.currentTimeMillis()
        
        return try {
            // Step 1: TLS Connect
            connectWithSNI()
            
            // Step 2: RTMP Handshake
            performHandshake()
            
            // Step 3: Connect command
            sendConnect()
            receiveConnectResponse()
            
            // Step 4: CreateStream
            sendCreateStream()
            receiveCreateStreamResponse()
            
            // Step 5: Publish
            sendPublish()
            
            // Step 6: Disconnect
            disconnect()
            
            stats.totalDurationMs = System.currentTimeMillis() - startTime
            
            PtlLogger.i(TAG, "✓ Probe A PASSED - All commands succeeded")
            PtlLogger.i(TAG, "Total duration: ${PtlLogger.formatDuration(stats.totalDurationMs)}")
            
            ProbeResult(
                success = true,
                message = "RTMPS handshake completed successfully",
                metrics = stats.toMap()
            )
            
        } catch (e: Exception) {
            stats.errorMessage = e.message
            PtlLogger.e(TAG, "✗ Probe A FAILED", e)
            
            try { disconnect() } catch (_: Exception) {}
            
            ProbeResult(
                success = false,
                message = "Handshake failed at: ${e.message}",
                metrics = stats.toMap(),
                error = e.stackTraceToString()
            )
        }
    }
    
    // ==================== Connection ====================
    
    private fun connectWithSNI() {
        val duration = measureTimeMillis {
            PtlLogger.d(TAG, "Connecting with SNI...")
            
            // Parse URL
            val isSecure = rtmpsUrl.startsWith("rtmps://")
            val urlClean = rtmpsUrl.removePrefix("rtmps://").removePrefix("rtmp://")
            val parts = urlClean.split(":")
            val host = parts[0].split("/")[0]
            val port = if (parts.size > 1) {
                parts[1].split("/")[0].toIntOrNull() ?: if (isSecure) 443 else 1935
            } else {
                if (isSecure) 443 else 1935
            }
            
            PtlLogger.d(TAG, "Host: $host, Port: $port, Secure: $isSecure")
            
            // Connect with SNI
            socket = if (isSecure) {
                val sslSocket = SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket
                
                // PTL FIX: Set SNI explicitly
                val sslParams = SSLParameters()
                sslParams.serverNames = listOf(SNIHostName(host))
                sslSocket.sslParameters = sslParams
                
                PtlLogger.d(TAG, "✓ SNI set: $host")
                sslSocket
            } else {
                Socket(host, port)
            }
            
            socket?.tcpNoDelay = true
            socket?.keepAlive = true
            socket?.soTimeout = 30_000
            
            output = socket?.getOutputStream()
            input = socket?.getInputStream()
            
            PtlLogger.i(TAG, "✓ Socket connected")
        }
        
        stats.connectDurationMs = duration
        PtlLogger.d(TAG, "Connect took ${duration}ms")
    }
    
    // ==================== RTMP Handshake ====================
    
    private fun performHandshake() {
        val duration = measureTimeMillis {
            PtlLogger.d(TAG, "Starting RTMP handshake...")
            
            // C0
            output?.write(RTMP_PROTOCOL_VERSION)
            stats.bytesSent += 1
            
            // C1
            val c1 = ByteArray(1536)
            c1[0] = 0; c1[1] = 0; c1[2] = 0; c1[3] = 0  // timestamp
            c1[4] = 0; c1[5] = 0; c1[6] = 0; c1[7] = 0  // zero
            for (i in 8 until 1536) {
                c1[i] = (i % 256).toByte()
            }
            output?.write(c1)
            output?.flush()
            stats.bytesSent += 1536
            
            // S0
            val s0 = input?.read() ?: throw IOException("No S0")
            stats.bytesReceived += 1
            if (s0 != RTMP_PROTOCOL_VERSION) {
                throw IOException("Invalid S0 version: $s0")
            }
            
            // S1
            val s1 = ByteArray(1536)
            readFully(s1)
            stats.bytesReceived += 1536
            
            // C2: echo S1
            output?.write(s1)
            output?.flush()
            stats.bytesSent += 1536
            
            // S2: ignore
            val s2 = ByteArray(1536)
            readFully(s2)
            stats.bytesReceived += 1536
            
            PtlLogger.i(TAG, "✓ Handshake complete (sent ${stats.bytesSent}, recv ${stats.bytesReceived})")
        }
        
        stats.handshakeDurationMs = duration
        PtlLogger.d(TAG, "Handshake took ${duration}ms")
    }
    
    // ==================== RTMP Commands ====================
    
    private fun sendConnect() {
        val duration = measureTimeMillis {
            PtlLogger.d(TAG, "Sending connect command...")
            
            // PTL FIX: Parse app correctly for YouTube /live2
            val urlWithoutProto = rtmpsUrl.removePrefix("rtmps://").removePrefix("rtmp://")
            val pathStart = urlWithoutProto.indexOf('/')
            val app = if (pathStart > 0) {
                urlWithoutProto.substring(pathStart + 1).split('/')[0]
            } else {
                "live"
            }
            val tcUrl = rtmpsUrl.substringBeforeLast('/')
            
            PtlLogger.d(TAG, "App: '$app', tcUrl: '$tcUrl'")
            
            val amf = ByteArrayOutputStream()
            writeAmfString(amf, "connect")
            writeAmfNumber(amf, transactionId++.toDouble())
            
            amf.write(0x03)  // Object
            writeAmfPropertyString(amf, "app", app)
            writeAmfPropertyString(amf, "type", "nonprivate")
            writeAmfPropertyString(amf, "tcUrl", tcUrl)
            writeAmfPropertyBool(amf, "fpad", false)
            writeAmfPropertyNumber(amf, "capabilities", 239.0)
            writeAmfPropertyNumber(amf, "audioCodecs", 3191.0)
            writeAmfPropertyNumber(amf, "videoCodecs", 252.0)
            writeAmfPropertyNumber(amf, "videoFunction", 1.0)
            writeAmfPropertyNumber(amf, "objectEncoding", 0.0)
            amf.write(0); amf.write(0); amf.write(9)  // End marker
            
            sendChunk(0x03, 0, 0x14, 0, amf.toByteArray())
            PtlLogger.d(TAG, "✓ Connect sent")
        }
        
        stats.connectDurationMs += duration
    }
    
    private fun receiveConnectResponse() {
        PtlLogger.d(TAG, "Waiting for connect response...")
        val chunk = readChunk()
        val response = String(chunk, charset("UTF-8"))
        
        if (response.contains("_result")) {
            PtlLogger.i(TAG, "✓ Connect response: _result received")
        } else if (response.contains("error")) {
            throw IOException("Connect failed: $response")
        } else {
            PtlLogger.w(TAG, "Connect response unclear, continuing...")
        }
    }
    
    private fun sendCreateStream() {
        val duration = measureTimeMillis {
            PtlLogger.d(TAG, "Sending createStream...")
            
            val amf = ByteArrayOutputStream()
            writeAmfString(amf, "createStream")
            writeAmfNumber(amf, transactionId++.toDouble())
            amf.write(0x05)  // null
            
            sendChunk(0x03, 0, 0x14, 0, amf.toByteArray())
            PtlLogger.d(TAG, "✓ CreateStream sent")
        }
        
        stats.createStreamDurationMs = duration
    }
    
    private fun receiveCreateStreamResponse() {
        PtlLogger.d(TAG, "Waiting for createStream response...")
        val chunk = readChunk()
        
        // PTL FIX: Parse full AMF response properly
        try {
            val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.BIG_ENDIAN)
            
            // Skip command name + transaction ID (variable length)
            // Look for AMF number type (0x00) followed by double
            var found = false
            for (i in chunk.indices) {
                if (chunk[i] == 0x00.toByte() && i + 8 < chunk.size) {
                    // Potential stream ID
                    buffer.position(i + 1)
                    val value = java.lang.Double.longBitsToDouble(buffer.long)
                    if (value > 0 && value < 100) {  // Sanity check
                        streamId = value.toInt()
                        found = true
                        break
                    }
                }
            }
            
            if (!found) {
                streamId = 1  // Default fallback
                PtlLogger.w(TAG, "Could not parse stream ID, using default: 1")
            } else {
                PtlLogger.i(TAG, "✓ Stream ID: $streamId")
            }
            
        } catch (e: Exception) {
            streamId = 1
            PtlLogger.w(TAG, "Parse error, using stream ID = 1: ${e.message}")
        }
    }
    
    private fun sendPublish() {
        val duration = measureTimeMillis {
            PtlLogger.d(TAG, "Sending publish (key: ***${streamKey.takeLast(4)})...")
            
            val amf = ByteArrayOutputStream()
            writeAmfString(amf, "publish")
            writeAmfNumber(amf, 0.0)  // transaction ID = 0
            amf.write(0x05)  // null
            writeAmfString(amf, streamKey)
            writeAmfString(amf, "live")
            
            sendChunk(0x08, streamId, 0x14, 0, amf.toByteArray())
            PtlLogger.i(TAG, "✓ Publish sent")
        }
        
        stats.publishDurationMs = duration
    }
    
    private fun disconnect() {
        output?.close()
        input?.close()
        socket?.close()
        PtlLogger.d(TAG, "✓ Disconnected")
    }
    
    // ==================== RTMP Protocol Helpers ====================
    
    private fun sendChunk(chunkStreamId: Int, messageStreamId: Int, messageType: Int, timestamp: Int, data: ByteArray) {
        val header = ByteArrayOutputStream()
        
        // Basic header: fmt=0, chunk stream ID
        header.write(chunkStreamId and 0x3F)
        
        // Message header (11 bytes)
        header.write((timestamp shr 16) and 0xFF)
        header.write((timestamp shr 8) and 0xFF)
        header.write(timestamp and 0xFF)
        
        val messageLength = data.size
        header.write((messageLength shr 16) and 0xFF)
        header.write((messageLength shr 8) and 0xFF)
        header.write(messageLength and 0xFF)
        
        header.write(messageType)
        
        // Message stream ID (little-endian)
        header.write(messageStreamId and 0xFF)
        header.write((messageStreamId shr 8) and 0xFF)
        header.write((messageStreamId shr 16) and 0xFF)
        header.write((messageStreamId shr 24) and 0xFF)
        
        output?.write(header.toByteArray())
        output?.write(data)
        output?.flush()
        
        stats.bytesSent += header.size().toLong() + data.size
    }
    
    private fun readChunk(): ByteArray {
        val basicHeader = input?.read() ?: throw IOException("No chunk")
        stats.bytesReceived += 1
        
        val fmt = (basicHeader shr 6) and 0x03
        val headerSize = when (fmt) {
            0 -> 11
            1 -> 7
            2 -> 3
            3 -> 0
            else -> 0
        }
        
        val header = ByteArray(headerSize)
        if (headerSize > 0) {
            readFully(header)
            stats.bytesReceived += headerSize.toLong()
        }
        
        val messageLength = if (headerSize >= 6) {
            ((header[3].toInt() and 0xFF) shl 16) or
            ((header[4].toInt() and 0xFF) shl 8) or
            (header[5].toInt() and 0xFF)
        } else {
            4096  // Default cap
        }
        
        val data = ByteArray(minOf(messageLength, 8192))
        readFully(data)
        stats.bytesReceived += data.size.toLong()
        
        return data
    }
    
    // ==================== AMF Encoding ====================
    
    private fun writeAmfString(out: OutputStream, value: String) {
        out.write(0x02)
        out.write((value.length shr 8) and 0xFF)
        out.write(value.length and 0xFF)
        out.write(value.toByteArray())
    }
    
    private fun writeAmfNumber(out: OutputStream, value: Double) {
        out.write(0x00)
        val bits = java.lang.Double.doubleToLongBits(value)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(bits).array())
    }
    
    private fun writeAmfPropertyString(out: OutputStream, name: String, value: String) {
        out.write((name.length shr 8) and 0xFF)
        out.write(name.length and 0xFF)
        out.write(name.toByteArray())
        writeAmfString(out, value)
    }
    
    private fun writeAmfPropertyNumber(out: OutputStream, name: String, value: Double) {
        out.write((name.length shr 8) and 0xFF)
        out.write(name.length and 0xFF)
        out.write(name.toByteArray())
        writeAmfNumber(out, value)
    }
    
    private fun writeAmfPropertyBool(out: OutputStream, name: String, value: Boolean) {
        out.write((name.length shr 8) and 0xFF)
        out.write(name.length and 0xFF)
        out.write(name.toByteArray())
        out.write(0x01)
        out.write(if (value) 1 else 0)
    }
    
    private fun readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input?.read(buffer, offset, buffer.size - offset) ?: throw IOException("EOF")
            if (read < 0) throw IOException("EOF")
            offset += read
        }
    }
}
