package com.screenlive.app.rtmp

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import java.net.URI

/**
 * [PTL] RTMPS endpoint configuration
 * Why: Force RTMPS:443 for YouTube to prevent 1935 port blocks by carriers
 */
data class RtmpEndpoint(
    val host: String,
    val port: Int,
    val app: String,
    val useTls: Boolean,
    val tcUrl: String
)

class MinimalRtmpsClient(
    private val host: String = "a.rtmp.youtube.com",
    private val port: Int = 1935,
    private val app: String = "live2",
    private val streamKey: String,
    private val rtmpUrl: String = "rtmp://a.rtmp.youtube.com/live2",  // [PTL] Full URL for SSL detection
    private val tcUrl: String = "rtmp://a.rtmp.youtube.com/live2",
    private val connectProperties: Map<String, Any?> = defaultConnectProps(),
    var onDisconnected: ((Throwable?) -> Unit)? = null  // [PTL] Reconnect callback with error
) {
    companion object {
        private const val TAG = "RTMPS"
        
        fun defaultConnectProps(): Map<String, Any?> = mapOf(
            "app" to "live2",
            "type" to "nonprivate",
            "tcUrl" to "rtmp://a.rtmp.youtube.com/live2",
            "fpad" to false,
            "capabilities" to 239.0,
            "audioCodecs" to 3575.0,
            "videoCodecs" to 252.0,
            "videoFunction" to 1.0,
            "flashVer" to "LNX 9,0,124,2",
            "swfUrl" to ""
        )
        
        /**
         * [PTL] Normalize YouTube URL to RTMPS:443 with SNI
         * Why: Prevent 1935 port blocks by carriers/firewalls (6-8s disconnects)
         * Transforms rtmp://a.rtmp.youtube.com/live2 → rtmps://a.rtmps.youtube.com:443/rtmp2
         */
        private fun normalizeYouTubeEndpoint(url: String): RtmpEndpoint {
            return try {
                val uri = URI(url)
                val scheme = uri.scheme?.lowercase() ?: "rtmps"
                val inputHost = uri.host ?: "a.rtmps.youtube.com"
                val path = uri.path.trim('/')
                
                // [PTL] FORCE YouTube to RTMPS:443 regardless of input scheme
                val ytHost = when {
                    inputHost.contains("youtube.com") && scheme == "rtmp" -> "a.rtmps.youtube.com"
                    inputHost.contains("youtube.com") -> inputHost.replace(".rtmp.", ".rtmps.")
                    else -> inputHost
                }
                
                val ytPort = when {
                    inputHost.contains("youtube.com") -> 443  // ALWAYS 443 for YouTube
                    uri.port > 0 -> uri.port
                    scheme == "rtmps" -> 443
                    else -> 1935
                }
                
                // YouTube uses "rtmp2" app over RTMPS, not "live2"
                val ytApp = when {
                    inputHost.contains("youtube.com") && path.isBlank() -> "rtmp2"
                    inputHost.contains("youtube.com") && path == "live2" -> "rtmp2"
                    path.isNotBlank() -> path
                    else -> "rtmp2"
                }
                
                val useTls = ytPort == 443 || scheme == "rtmps"
                val tcUrl = if (useTls) {
                    "rtmps://$ytHost:$ytPort/$ytApp"
                } else {
                    "rtmp://$ytHost:$ytPort/$ytApp"
                }
                
                PtlLog.i("RTMPS: Normalized $url → $tcUrl (TLS=$useTls)")
                RtmpEndpoint(ytHost, ytPort, ytApp, useTls, tcUrl)
                
            } catch (e: Exception) {
                PtlLog.e("RTMPS: URL parse failed, using default RTMPS:443", e)
                RtmpEndpoint("a.rtmps.youtube.com", 443, "rtmp2", true, "rtmps://a.rtmps.youtube.com:443/rtmp2")
            }
        }
    }

    private lateinit var socket: Socket
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var assembler: RtmpChunkAssembler
    
    // [PTL FIX] Single reader thread management
    private val readerAlive = java.util.concurrent.atomic.AtomicBoolean(false)
    private val readerRef = java.util.concurrent.atomic.AtomicReference<Thread?>()
    @Volatile private var closed = false
    
    // [PTL] CRITICAL FIX: Track bytes READ from server (not sent!)
    // RTMP Window ACK = "I acknowledge receiving X bytes FROM you (server)"
    private var totalBytesRead: Long = 0
    private var bytesReadSinceLastAck: Long = 0
    private var lastInboundTimestamp: Long = System.currentTimeMillis()

    @Volatile var streamId: Int = -1; private set
    @Volatile var connected = false; private set
    @Volatile var published = false; private set

    @Volatile private var shuttingDown = false
    @Volatile private var disconnectNotified = false
    
    // [PTL] Window Acknowledgement configuration from server
    private var windowAckSize: Int = 0  // Set by server via WindowAckSize message
    private var ackThreshold: Long = 0  // When to send ACK (typically = windowAckSize)
    
    // [PTL] Keep-alive timer to prevent NAT/carrier timeout
    private val keepAliveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var keepAliveJob: Job? = null
    
    // [PTL] Reconnect state
    private var endpoint: RtmpEndpoint? = null
    private var isReconnecting = false

    private enum class TxType { CONNECT, RELEASE_STREAM, FC_PUBLISH, CREATE_STREAM }

    private val pendingTransactions = ConcurrentHashMap<Double, TxType>()

    private val ctrlCommandCsid = 3
    private val streamCommandCsid = 8
    
    // [PTL] Callback to notify when publish is confirmed by server
    var onPublishStarted: (() -> Unit)? = null

    private fun registerTransaction(id: Double, type: TxType) {
        pendingTransactions[id] = type
    }

    fun connectBlocking(timeoutMs: Int = 15000) {
        try {
            shuttingDown = false
            disconnectNotified = false
            
            // [PTL] Normalize URL to RTMPS:443 with SNI (prevent 1935 blocks)
            endpoint = normalizeYouTubeEndpoint(rtmpUrl)
            val ep = endpoint!!
            
            PtlLog.i("RTMPS: Connecting to ${ep.host}:${ep.port} app=${ep.app} (TLS=${ep.useTls} SNI=true)")
            Log.i(TAG, "→ Connecting to ${ep.host}:${ep.port} (${if (ep.useTls) "SSL+SNI" else "plain"})")
            
            socket = if (ep.useTls) {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = factory.createSocket(ep.host, ep.port) as SSLSocket
                sslSocket.apply {
                    // [PTL] Enable SNI for proper TLS routing (critical for CDNs)
                    val sslParams = SSLParameters()
                    sslParams.serverNames = listOf(SNIHostName(ep.host))
                    sslParameters = sslParams
                    
                    // [PTL] Use modern TLS to avoid policy blocks on some ROMs
                    val wanted = arrayOf("TLSv1.3", "TLSv1.2")
                    enabledProtocols = supportedProtocols.filter { it in wanted }.toTypedArray().ifEmpty { supportedProtocols }
                    useClientMode = true
                    soTimeout = timeoutMs
                    
                    // [P0 FIX] Enable TCP_NODELAY to prevent Nagle delays on control messages
                    tcpNoDelay = true
                    // [P0 FIX] Enable SO_KEEPALIVE to detect NAT timeout on mobile networks
                    keepAlive = true
                    
                    // Perform handshake explicitly
                    startHandshake()
                    PtlLog.i("RTMPS: ✓ TLS handshake complete (protocol=${session.protocol} cipher=${session.cipherSuite})")
                }
                sslSocket as Socket
            } else {
                // Plain RTMP socket (for non-YouTube)
                val plainSocket = java.net.Socket(ep.host, ep.port)
                plainSocket.soTimeout = timeoutMs
                // [P0 FIX] Enable TCP options for plain RTMP
                plainSocket.tcpNoDelay = true
                plainSocket.keepAlive = true
                plainSocket
            }
            
            // [PTL] Direct socket streams (no counting wrapper needed - use assembler.totalBytesRead)
            input = DataInputStream(BufferedInputStream(socket.inputStream))
            output = DataOutputStream(BufferedOutputStream(socket.outputStream))
            PtlLog.i("RTMPS: ✓ Connection established")
            Log.i(TAG, "✓ Connection established")

            // RTMP handshake
            PtlLog.i("RTMPS: Handshake C0C1 -> S0S1S2 -> C2")
            Log.i(TAG, "→ RTMP handshake")
            RtmpHandshake.perform(output, input)
            PtlLog.i("RTMPS: ✓ RTMP handshake complete")
            Log.i(TAG, "✓ RTMP handshake complete")

            assembler = RtmpChunkAssembler()
            
            startReaderThread()

            // send Window Acknowledgement size + Set Chunk Size (outgoing) from config
            sendWindowAckSize(2_500_000)
            sendSetChunkSize(com.screenlive.app.config.StreamConfig.rtmpChunkSize)

            // connect
            val txConnect = 1.0
            val cmd = Amf0Writer().apply {
                writeString("connect")
                writeNumber(txConnect)
                writeObject(connectProperties)
                writeEcmaArray(mapOf(
                    "objectEncoding" to 0.0
                ))
            }.toByteArray()
            registerTransaction(txConnect, TxType.CONNECT)
            sendCommand(ctrlCommandCsid, 0, RtmpConsts.COMMAND_AMF0, 0, cmd)
            PtlLog.i("RTMPS: send connect(txn=1)")
            Log.i(TAG, "→ connect")

            // wait for _result(connect) in reader thread; then publish
            waitUntilPublished(60_000)
            PtlLog.i("🎉 PUBLISH STARTED! Ready to stream")
            Log.i(TAG, "🎉 PUBLISH STARTED! Ready to stream")
            
            // [PTL] Start keep-alive timer after publish succeeds
            startKeepAliveTimer()
        } catch (e: Exception) {
            PtlLog.e("❌ RTMP connection failed", e)
            Log.e(TAG, "❌ RTMP connection failed", e)
            closeQuiet()
            throw e
        }
    }

    // [P0 FIX] Reconnect without stopping encoders
    fun reconnect(): Boolean {
        return try {
            PtlLog.i("RTMPS: Reconnect initiated...")
            Log.i(TAG, "→ Reconnecting...")
            
            // Stop reader thread and close old socket first
            stopReaderThread()
            closeQuiet()
            
            // Reset state
            connected = false
            published = false
            streamId = -1
            
            // Re-establish connection
            connectBlocking()
            
            PtlLog.i("RTMPS: ✅ Reconnect successful")
            Log.i(TAG, "✅ Reconnect successful")
            true
        } catch (e: Exception) {
            PtlLog.e("RTMPS: ❌ Reconnect failed", e)
            Log.e(TAG, "❌ Reconnect failed", e)
            false
        }
    }

    private fun startReaderThread() {
        stopReaderThread() // Ensure single reader
        
        readerAlive.set(true)
        closed = false
        
        val t = Thread {
            val readerThreadId = Thread.currentThread().id
            PtlLog.i("RTMPS: Reader loop started (tid=$readerThreadId)")
            
            // Boost priority for low-latency reading
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            
            try {
                var messageCount = 0
                val dataInput = java.io.DataInputStream(input)
                
                while (readerAlive.get() && !closed && !socket.isClosed) {
                    messageCount++
                    
                    // [PTL DEBUG] Heartbeat every 100 messages
                    if (messageCount % 100 == 0) {
                        PtlLog.d("RTMPS: Reader alive (tid=$readerThreadId, msgs=$messageCount)")
                    }
                    
                    // Use new assembler with per-CSID state
                    val msg = assembler.readMessage(dataInput)
                    
                    // Update inbound byte tracking (for Window ACK)
                    // Note: RtmpChunkAssembler doesn't expose totalBytesRead anymore
                    // We track at message level instead
                    bytesReadSinceLastAck += msg.payload.size.toLong()
                    totalBytesRead += msg.payload.size.toLong()
                    lastInboundTimestamp = System.currentTimeMillis()
                    
                    // Check if we need to send Window ACK
                    checkAndSendWindowAck()
                    
                    // Handle message
                    handleIncomingMessage(msg)
                }
            } catch (e: java.io.EOFException) {
                if (!closed) {
                    PtlLog.e("RTMPS: Reader EOF - socket closed by server", e)
                }
            } catch (t: Throwable) {
                if (!closed) {
                    PtlLog.e("RTMPS: Reader crashed", t)
                }
            } finally {
                readerAlive.set(false)
                PtlLog.i("RTMPS: Reader thread exited (tid=$readerThreadId)")
                
                // Notify disconnect for reconnection
                if (!shuttingDown && !disconnectNotified && !closed) {
                    disconnectNotified = true
                    kotlin.runCatching { onDisconnected?.invoke(Exception("Reader thread died")) }
                }
            }
        }.also { 
            it.name = "rtmps-reader"
            it.isDaemon = true 
        }
        
        readerRef.set(t)
        t.start()
    }
    
    private fun stopReaderThread(timeoutMs: Long = 300) {
        readerAlive.set(false)
        readerRef.getAndSet(null)?.let { th ->
            try { 
                th.join(timeoutMs)
                if (th.isAlive) {
                    PtlLog.w("RTMPS: Reader thread did not stop within ${timeoutMs}ms")
                }
            } catch (_: InterruptedException) {}
        }
    }
    
    private fun handleIncomingMessage(msg: RtmpMessage) {
        when (msg.messageTypeId) {
            RtmpConsts.COMMAND_AMF0 -> handleCommand(msg.payload)
            RtmpConsts.USER_CONTROL -> {
                PtlLog.i("RTMPS: ← USER_CONTROL message (len=${msg.payload.size})")
                handleUserControl(msg.payload)
            }
            RtmpConsts.WINDOW_ACK_SIZE -> {
                if (msg.payload.size >= 4) {
                    val newAckSize = ByteBuffer.wrap(msg.payload).order(ByteOrder.BIG_ENDIAN).int
                    windowAckSize = newAckSize
                    ackThreshold = windowAckSize.toLong()
                    PtlLog.i("RTMPS: ← Window ACK Size = $windowAckSize")
                }
            }
            RtmpConsts.SET_CHUNK_SIZE -> {
                if (msg.payload.size >= 4) {
                    val newChunkSize = ByteBuffer.wrap(msg.payload).order(ByteOrder.BIG_ENDIAN).int and 0x7FFFFFFF
                    assembler.onSetChunkSize(newChunkSize)
                    PtlLog.i("RTMPS: ← Set Chunk Size = $newChunkSize")
                }
            }
            RtmpConsts.SET_PEER_BANDWIDTH -> {
                // Informational, no action needed
            }
            RtmpConsts.ACKNOWLEDGEMENT -> {
                // Server acknowledging our sent bytes
            }
            RtmpConsts.ABORT_MESSAGE -> {
                PtlLog.w("RTMPS: ← ABORT_MESSAGE from server")
            }
            else -> {
                // Silent: audio/video data
            }
        }
    }

    private fun handleCommand(pay: ByteArray) {
        try {
            val r = Amf0Reader(pay)
            val name = r.readAny() as? String ?: return
            val txId = (r.readAny() as? Double) ?: 0.0
            val args = mutableListOf<Any?>()
            while (r.hasMore()) {
                args += r.readAny()
            }

            PtlLog.i("RTMPS: recv $name (tx=$txId)")
            Log.d(TAG, "← Received command: $name (tx=$txId, args=${args.size})")

            when (name) {
                "_result" -> handleResult(txId, args)
                "onStatus" -> handleOnStatus(args)
                "_error" -> handleError(txId, args)
                else -> Log.i(TAG, "← $name (tx=$txId) args=${argsDebugString(args)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing command", e)
        }
    }

    private fun handleResult(txId: Double, args: List<Any?>) {
        val txType = pendingTransactions.remove(txId)
        PtlLog.i("RTMPS: _result received tx=$txId type=${txType ?: "unknown"} args=${argsDebugString(args)}")

        when (txType) {
            TxType.CONNECT -> {
                connected = true
                PtlLog.i("RTMPS: connect acknowledged → sending releaseStream/FCPublish/createStream")
                Log.i(TAG, "✓ connect acknowledged")
                afterConnectResult()
            }
            TxType.RELEASE_STREAM -> {
                Log.i(TAG, "✓ releaseStream acknowledged")
            }
            TxType.FC_PUBLISH -> {
                Log.i(TAG, "✓ FCPublish acknowledged")
            }
            TxType.CREATE_STREAM -> onCreateStreamResult(args)
            null -> Log.i(TAG, "← _result(tx=$txId) with no pending transaction; args=${argsDebugString(args)}")
        }
    }

    private fun handleOnStatus(args: List<Any?>) {
        val info = args.getOrNull(1) as? Map<*, *>
        val code = info?.get("code") as? String
        PtlLog.i("RTMPS: onStatus code=$code raw=${argsDebugString(args)}")
        Log.i(TAG, "← onStatus code=$code")

        when (code) {
            "NetStream.Publish.Start" -> {
                published = true
                PtlLog.i("[PTL] PUBLISH ACK received – invoking onPublishStarted()")
                Log.i(TAG, "[PTL] PUBLISH ACK received – invoking onPublishStarted()")
                onPublishStarted?.invoke()
            }
            "NetStream.Publish.BadName" -> Log.e(TAG, "❌ Bad stream key: $code")
            else -> if (code?.contains("error", ignoreCase = true) == true || code?.contains("fail", ignoreCase = true) == true) {
                Log.e(TAG, "❌ Publish error: $code")
            }
        }
    }

    private fun handleError(txId: Double, args: List<Any?>) {
        Log.e(TAG, "← _error (tx=$txId) args=${argsDebugString(args)}")
    }

    // [PTL] Handle User Control messages (Ping/Pong, Stream Begin, etc.)
    /**
     * Handle User Control Messages (message type 4)
     * Events: 0=StreamBegin, 1=StreamEof, 6=PingRequest, 7=PingResponse, etc.
     * See RTMP spec section 7.1.7 for full list.
     */
    private fun handleUserControl(payload: ByteArray) {
        if (payload.size < 2) {
            Log.w(TAG, "UserControl payload too short: ${payload.size} bytes")
            return
        }
        
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val eventType = buf.short.toInt()
        
        when (eventType) {
            0 -> {  // StreamBegin
                if (buf.remaining() >= 4) {
                    val streamId = buf.int
                    PtlLog.i("RTMPS: ← StreamBegin (streamId=$streamId)")
                    Log.i(TAG, "← StreamBegin streamId=$streamId")
                }
            }
            1 -> {  // StreamEof
                if (buf.remaining() >= 4) {
                    val streamId = buf.int
                    PtlLog.w("RTMPS: ← StreamEof (streamId=$streamId) - server closing stream!")
                    Log.w(TAG, "← StreamEof streamId=$streamId")
                }
            }
            6 -> {  // PingRequest
                if (buf.remaining() < 4) {
                    Log.w(TAG, "PingRequest payload too short")
                    return
                }
                val timestamp = buf.int
                PtlLog.i("RTMPS: ← PingRequest ts=$timestamp → sending PingResponse")
                Log.i(TAG, "← PingRequest ts=$timestamp")
                sendPingResponse(timestamp)
            }
            7 -> {  // PingResponse (echo from server)
                if (buf.remaining() >= 4) {
                    val timestamp = buf.int
                    // PtlLog: ← PingResponse (received echo from server)
                    Log.d(TAG, "← PingResponse ts=$timestamp")
                }
            }
            else -> {
                // PtlLog: ← UserControl event unhandled
                Log.d(TAG, "← UserControl event=$eventType")
            }
        }
    }

    // [PTL] Send PingResponse (User Control event 7) with same timestamp
    private fun sendPingResponse(timestamp: Int) {
        val payload = ByteBuffer.allocate(6).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(7)  // eventType = PingResponse
            putInt(timestamp)
        }.array()
        
        sendCommand(csid = 2, messageStreamId = 0, messageType = RtmpConsts.USER_CONTROL, streamId = 0, payload = payload)
        PtlLog.i("RTMPS: send PingResponse ts=$timestamp")
        Log.i(TAG, "→ PingResponse ts=$timestamp")
    }
    
    // [PTL] Send PingRequest (User Control event 6) for keep-alive
    private fun sendPingRequest(timestamp: Int) {
        val payload = ByteBuffer.allocate(6).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(6)  // eventType = PingRequest
            putInt(timestamp)
        }.array()
        
        sendCommand(csid = 2, messageStreamId = 0, messageType = RtmpConsts.USER_CONTROL, streamId = 0, payload = payload)
        PtlLog.i("RTMPS: → PingRequest ts=$timestamp (keep-alive)")
        Log.i(TAG, "→ PingRequest ts=$timestamp (keep-alive)")
    }

    // [P0 FIX] Helper to check if socket is active
    private fun isSocketActive(): Boolean {
        return !shuttingDown && socket?.isConnected == true && socket?.isClosed == false
    }

    // [P0 FIX] Helper to close socket quietly (internal for RootEncoderService access)
    internal fun closeQuiet() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    // [PTL] Start keep-alive timer to prevent NAT/carrier timeout
    private fun startKeepAliveTimer() {
        keepAliveJob?.cancel()
        keepAliveJob = keepAliveScope.launch {
            val keepAliveInterval = 10_000L  // 10 seconds
            val idleThreshold = 8_000L        // 8 seconds of no inbound traffic
            
            while (isActive && isSocketActive()) {
                delay(keepAliveInterval)
                
                val idleTime = System.currentTimeMillis() - lastInboundTimestamp
                if (idleTime > idleThreshold) {
                    val timestamp = (System.currentTimeMillis() and 0xFFFFFFFF).toInt()
                    sendPingRequest(timestamp)
                    PtlLog.i("RTMPS: client PingRequest ts=$timestamp (keep-alive, idle for ${idleTime}ms)")
                }
            }
        }
    }

    // [PTL] Send Acknowledgement (type 3) to report bytes received
    private fun sendAcknowledgement(bytesReceived: Int) {
        val payload = ByteBuffer.allocate(4).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(bytesReceived)
        }.array()
        
        sendCommand(csid = 2, messageStreamId = 0, messageType = RtmpConsts.ACKNOWLEDGEMENT, streamId = 0, payload = payload)
        PtlLog.i("RTMPS: send Acknowledgement bytes=$bytesReceived")
        Log.i(TAG, "→ Acknowledgement bytes=$bytesReceived")
    }

    private fun afterConnectResult() {
        sendReleaseStream()
        sendFCPublish()
        sendCreateStream()
    }

    private fun onCreateStreamResult(args: List<Any?>) {
        val streamIdValue = (args.getOrNull(1) as? Double)?.toInt() ?: -1
        if (streamIdValue <= 0) {
            PtlLog.e("RTMPS: createStream _result missing streamId, args=${argsDebugString(args)}")
            Log.e(TAG, "❌ Invalid streamId in createStream response: args=${argsDebugString(args)}")
            return
        }

        streamId = streamIdValue
        PtlLog.i("RTMPS: createStream acknowledged → streamId=$streamId")
        Log.i(TAG, "✓ createStream returned streamId=$streamId")

        // Increase chunk size to reduce fragment overhead before publish (from config)
        sendSetChunkSize(com.screenlive.app.config.StreamConfig.rtmpChunkSize)

        sendPublish(streamKey, streamId)
    }

    private fun sendReleaseStream() {
        val tx = 2.0
        registerTransaction(tx, TxType.RELEASE_STREAM)
        sendCommandOnCtrl("releaseStream", tx, listOf(streamKey))
        PtlLog.i("RTMPS: send releaseStream(txn=2)")
        Log.i(TAG, "→ releaseStream")
    }

    private fun sendFCPublish() {
        val tx = 3.0
        registerTransaction(tx, TxType.FC_PUBLISH)
        sendCommandOnCtrl("FCPublish", tx, listOf(streamKey))
        PtlLog.i("RTMPS: send FCPublish(txn=3)")
        Log.i(TAG, "→ FCPublish")
    }

    private fun sendCreateStream() {
        val tx = 4.0
        registerTransaction(tx, TxType.CREATE_STREAM)
        sendCommandOnCtrl("createStream", tx, emptyList())
        PtlLog.i("RTMPS: createStream(txn=4)")
        Log.i(TAG, "→ createStream")
    }

    private fun sendPublish(name: String, streamId: Int, type: String = "live") {
        sendCommandOnStream("publish", 0.0, streamId, listOf(name, type))
        PtlLog.i("RTMPS: publish(streamId=$streamId, key=***${name.takeLast(4)}, type=$type)")
        Log.i(TAG, "→ publish(name=$name, type=$type, streamId=$streamId)")
    }

    // [PTL] Send @setDataFrame("onMetaData") - required by YouTube
    fun sendMetadata(width: Int, height: Int, fps: Int, videoBitrate: Int, audioBitrate: Int) {
        val metadata = mapOf(
            "width" to width.toDouble(),
            "height" to height.toDouble(),
            "framerate" to fps.toDouble(),
            "videodatarate" to (videoBitrate / 1000.0),  // YouTube expects kbps
            "videocodecid" to 7.0,  // AVC/H.264
            "audiodatarate" to (audioBitrate / 1000.0),  // kbps
            "audiosamplerate" to 48000.0,
            "audiosamplesize" to 16.0,
            "audiochannels" to 1.0,
            "audiocodecid" to 10.0,  // AAC
            "encoder" to "ScreenLive RTMP Client"
        )
        
        val payload = Amf0Writer().apply {
            writeString("@setDataFrame")
            writeString("onMetaData")
            writeObject(metadata)
        }.toByteArray()
        
        sendCommand(streamCommandCsid, streamId, RtmpConsts.DATA_MESSAGE_AMF0, streamId, payload)
        PtlLog.i("RTMPS: send @setDataFrame(onMetaData) - ${width}x${height}@${fps}fps")
        Log.i(TAG, "→ @setDataFrame(onMetaData) - ${width}x${height}@${fps}fps, vbr=${videoBitrate/1000}kbps, abr=${audioBitrate/1000}kbps")
    }

    private fun sendCommandOnCtrl(commandName: String, txId: Double, args: List<Any?>) {
        val payload = Amf0Writer().apply {
            writeString(commandName)
            writeNumber(txId)
            writeNull()
            args.forEach { writeAny(it) }
        }.toByteArray()
        sendCommand(ctrlCommandCsid, 0, RtmpConsts.COMMAND_AMF0, 0, payload)
    }

    private fun sendCommandOnStream(commandName: String, txId: Double, streamId: Int, args: List<Any?>) {
        val payload = Amf0Writer().apply {
            writeString(commandName)
            writeNumber(txId)
            writeNull()
            args.forEach { writeAny(it) }
        }.toByteArray()
        sendCommand(streamCommandCsid, streamId, RtmpConsts.COMMAND_AMF0, streamId, payload)
    }

    private fun argsDebugString(args: List<Any?>): String =
        args.joinToString(prefix = "[", postfix = "]") { it?.toString() ?: "null" }

    private fun waitUntilPublished(timeoutMs: Long) {
        val start = System.currentTimeMillis()
        var lastLogTime = start
        while (!published) {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > timeoutMs) {
                PtlLog.e("SCREENLIVE_ERR/RTMPS_TIMEOUT PUBLISH_START: YouTube did not send NetStream.Publish.Start within ${timeoutMs}ms")
                Log.e(TAG, "❌ Publish start timeout after ${timeoutMs}ms")
                Log.e(TAG, "Debug state: connected=$connected, streamId=$streamId, published=$published")
                throw java.net.SocketTimeoutException("[SCREENLIVE_ERR/RTMPS_TIMEOUT] PUBLISH_START: Check stream key/region/YouTube Live status")
            }
            // [PTL] Add progress logging every 5 seconds
            if (elapsed - (lastLogTime - start) >= 5000) {
                PtlLog.i("⏳ Waiting for publish start... ${elapsed}ms elapsed (connected=$connected, streamId=$streamId)")
                Log.i(TAG, "⏳ Waiting for publish start... ${elapsed}ms elapsed (connected=$connected, streamId=$streamId)")
                lastLogTime = System.currentTimeMillis()
            }
            Thread.sleep(25)
        }
        PtlLog.i("✅ Published successfully after ${System.currentTimeMillis() - start}ms")
        Log.i(TAG, "✅ Published successfully after ${System.currentTimeMillis() - start}ms")
    }

    // [P0 FIX] Window ACK tracking with thread safety
    private fun checkAndSendWindowAck() {
        synchronized(this) {
            if (bytesReadSinceLastAck >= windowAckSize) {
                try {
                    val ackPayload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(totalBytesRead.toInt()).array()
                    sendCommand(csid = 2, messageStreamId = 0, messageType = RtmpConsts.ACKNOWLEDGEMENT, streamId = 0, payload = ackPayload)
                    bytesReadSinceLastAck = 0
                    PtlLog.i("RTMPS: → Window ACK sent (totalBytesRead=$totalBytesRead)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send Window ACK: ${e.message}")
                }
            }
        }
    }

    // ==== Writers ====
    private var outChunkSize = 4096

    // [P0 FIX] Guard against writing to closed socket
    private fun ensureAliveOrThrow() {
        if (shuttingDown || socket?.isClosed == true || socket?.isConnected == false) {
            throw IOException("Socket is not alive (shuttingDown=$shuttingDown, closed=${socket?.isClosed}, connected=${socket?.isConnected})")
        }
    }

    private fun sendWindowAckSize(size: Int) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(size).array()
        sendCommand(csid = 2, messageStreamId = 0, messageType = RtmpConsts.WINDOW_ACK_SIZE, streamId = 0, payload = b)
        Log.i(TAG, "→ WindowAckSize=$size")
    }

    fun sendSetChunkSize(size: Int) {
        outChunkSize = size
        val b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(size).array()
        sendCommand(csid = 2, messageStreamId = 0, messageType = RtmpConsts.SET_CHUNK_SIZE, streamId = 0, payload = b)
        Log.i(TAG, "→ SetChunkSize=$size")
    }

    private fun sendCommand(csid: Int, messageStreamId: Int, messageType: Int, streamId: Int, payload: ByteArray) {
        // [P0 FIX] Check socket state before writing
        ensureAliveOrThrow()
        
        // Basic header (fmt=0 for new message)
        val hdr = com.screenlive.app.rtmp.ByteArrayOutputStream2()
        val fmt = 0
        val basic = ((fmt and 0x03) shl 6) or (if (csid in 2..63) csid else 3) // we keep csid<=63
        hdr.write(basic)
        if (csid == 2 || csid == 3) { /* already in basic for simplicity */ }

        // Message header (fmt=0): timestamp=0, messageLength, messageType, messageStreamId (LE)
        hdr.write(byteArrayOf(0x00, 0x00, 0x00)) // timestamp 0
        hdr.write(byteArrayOf(((payload.size ushr 16) and 0xFF).toByte(), ((payload.size ushr 8) and 0xFF).toByte(), (payload.size and 0xFF).toByte()))
        hdr.write(messageType)
        hdr.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(messageStreamId).array())

        synchronized(output) {
            output.write(hdr.toByteArray())
            // chunk payload
            var off = 0
            while (off < payload.size) {
                val n = minOf(outChunkSize, payload.size - off)
                output.write(payload, off, n)
                off += n
                if (off < payload.size) {
                    // continuation header (fmt=3, same csid)
                    val cont = (3 shl 6) or (if (csid in 2..63) csid else 3)
                    output.write(cont)
                }
            }
            output.flush()
        }
    }

    fun sendFlvData(messageType: Int, payload: ByteArray, timestamp: Int = 0) {
        ensureAliveOrThrow() // [PTL FIX] Check socket before writing
        
        if (!published || streamId == -1) {
            Log.w(TAG, "Cannot send FLV data: not published (streamId=$streamId, published=$published)")
            return
        }
        
        // Use different CSIDs for video (8) and audio (9)
        val csid = when (messageType) {
            RtmpConsts.VIDEO_MESSAGE -> 8
            RtmpConsts.AUDIO_MESSAGE -> 9
            else -> 10
        }
        
        sendCommandWithTimestamp(csid, streamId, messageType, streamId, payload, timestamp)
    }

    private fun sendCommandWithTimestamp(csid: Int, messageStreamId: Int, messageType: Int, streamId: Int, payload: ByteArray, timestamp: Int) {
        // [P0 FIX] Check socket state before writing
        ensureAliveOrThrow()
        
        val hdr = com.screenlive.app.rtmp.ByteArrayOutputStream2()
        val fmt = 0
        val basic = ((fmt and 0x03) shl 6) or (if (csid in 2..63) csid else 3)
        hdr.write(basic)

        // Message header with timestamp
        hdr.write(byteArrayOf(
            ((timestamp ushr 16) and 0xFF).toByte(),
            ((timestamp ushr 8) and 0xFF).toByte(),
            (timestamp and 0xFF).toByte()
        ))
        hdr.write(byteArrayOf(
            ((payload.size ushr 16) and 0xFF).toByte(),
            ((payload.size ushr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte()
        ))
        hdr.write(messageType)
        hdr.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(messageStreamId).array())

        synchronized(output) {
            output.write(hdr.toByteArray())
            var off = 0
            while (off < payload.size) {
                val n = minOf(outChunkSize, payload.size - off)
                output.write(payload, off, n)
                off += n
                if (off < payload.size) {
                    val cont = (3 shl 6) or (if (csid in 2..63) csid else 3)
                    output.write(cont)
                }
            }
            output.flush()
            // [PTL] Note: Window ACK is now handled in reader loop based on bytes READ
        }
    }

    fun sendMetadata(meta: Map<String, Any>) {
        val payload = Amf0Writer().apply {
            writeString("@setDataFrame")
            writeString("onMetaData")
            writeObject(meta)
        }.toByteArray()
        sendCommand(csid = 4, messageStreamId = streamId, messageType = RtmpConsts.DATA_AMF0, streamId = streamId, payload = payload)
        Log.i(TAG, "→ Metadata sent")
    }
}

// [PTL] Note: RtmpChunkAssembler now handles per-CSID state correctly
