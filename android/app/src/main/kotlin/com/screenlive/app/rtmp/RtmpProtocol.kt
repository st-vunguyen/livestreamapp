package com.screenlive.app.rtmp

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object RtmpConsts {
    const val RTMP_DEFAULT_CHUNK_SIZE = 128
    // Message Type IDs
    const val SET_CHUNK_SIZE = 1
    const val ABORT_MESSAGE = 2
    const val ACKNOWLEDGEMENT = 3
    const val USER_CONTROL = 4
    const val WINDOW_ACK_SIZE = 5
    const val SET_PEER_BANDWIDTH = 6

    const val AUDIO_MESSAGE = 8
    const val VIDEO_MESSAGE = 9
    const val DATA_MESSAGE_AMF0 = 18
    const val DATA_AMF0 = 18  // Alias for metadata
    const val COMMAND_AMF0 = 20
}

internal data class RtmpHeader(
    val fmt: Int,
    val csid: Int,
    var timestamp: Int,
    var messageLength: Int,
    var messageTypeId: Int,
    var messageStreamId: Int
)

internal class ChunkStreamState(
    var timestamp: Int = 0,
    var messageLength: Int = 0,
    var messageTypeId: Int = 0,
    var messageStreamId: Int = 0,
    var remaining: Int = 0,
    val payload: ByteArrayOutputStream2 = ByteArrayOutputStream2()
)

internal class ByteArrayOutputStream2 : java.io.ByteArrayOutputStream() {
    fun toByteArrayAndReset(): ByteArray { val b = toByteArray(); reset(); return b }
}

// [PTL] Old RtmpChunkAssembler REMOVED - replaced by new per-CSID version in RtmpChunkAssembler.kt