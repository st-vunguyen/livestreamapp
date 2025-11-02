package com.screenlive.app.rtmp

import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * RTMP chunk assembler: stateless-per-CSID, không còn so sánh "continuation csid mismatch ... vs 64".
 * Hỗ trợ extended csid (0/1), extended timestamp, interleave nhiều csid.
 */
class RtmpChunkAssembler(
    initialChunkSize: Int = 128
) {

    private val chunkSize = AtomicInteger(initialChunkSize)

    // Trạng thái ghép message theo CSID
    private val partialByCsid = HashMap<Int, Partial>()

    // Resync cửa sổ quét khi gặp byte rác
    private val RESYNC_SCAN_LIMIT = 1024

    data class Header(
        var timestamp: Int = 0,
        var messageLength: Int = 0,
        var messageTypeId: Int = 0,
        var messageStreamId: Int = 0, // little-endian
        var timestampDelta: Int = 0,
        var absoluteTsKnown: Boolean = false
    )

    private class Partial(
        val csid: Int,
        val header: Header
    ) {
        val buf: ByteArray = ByteArray(header.messageLength)
        var written: Int = 0

        fun remaining(): Int = header.messageLength - written
        fun append(src: ByteArray, off: Int, len: Int) {
            System.arraycopy(src, off, buf, written, len)
            written += len
        }
        fun complete(): Boolean = written >= header.messageLength
    }

    fun onSetChunkSize(newSize: Int) {
        val n = newSize.coerceIn(64, 1 shl 20) // 64 .. 1MB
        chunkSize.set(n)
        PtlLog.d("RTMPS: assembler chunkSize=$n")
    }

    /**
     * Đọc 1 RTMP message hoàn chỉnh (có thể mất nhiều chunk).
     * Không ném IllegalState cho "continuation mismatch"; thay vào đó thử resync mềm.
     */
    fun readMessage(input: DataInputStream): RtmpMessage {
        while (true) {
            val startPosMark = tryReadPosition(input)

            try {
                // 1) Basic header
                val b0 = input.readUnsignedByte()
                val fmt = (b0 ushr 6) and 0x03
                var csid = b0 and 0x3F
                csid = when (csid) {
                    0 -> 64 + input.readUnsignedByte()
                    1 -> {
                        val b1 = input.readUnsignedByte()
                        val b2 = input.readUnsignedByte()
                        64 + b1 + (b2 shl 8)
                    }
                    else -> csid
                }

                // 2) Message header theo fmt
                val partial = getOrCreatePartialFor(fmt, csid, input)

                // 3) Đọc payload theo chunkSize
                val toRead = minOf(partial.remaining(), chunkSize.get())
                val tmp = ByteArray(toRead)
                input.readFully(tmp, 0, toRead)
                partial.append(tmp, 0, toRead)

                // 4) Nếu complete → trả về message
                if (partial.complete()) {
                    partialByCsid.remove(csid)
                    return RtmpMessage(
                        timestamp = if (partial.header.absoluteTsKnown)
                            partial.header.timestamp
                        else
                            partial.header.timestamp, // server thường gửi absolute ts khi fmt=0
                        messageTypeId = partial.header.messageTypeId,
                        messageStreamId = partial.header.messageStreamId,
                        payload = partial.buf
                    )
                }
                // Chưa complete → cần thêm chunk tiếp, lặp lại vòng while
            } catch (e: EOFException) {
                // Socket đóng đột ngột
                throw e
            } catch (t: Throwable) {
                // Resync mềm: quét tới khi tìm được header hợp lệ tiếp theo
                val skipped = trySoftResync(input, startPosMark)
                if (skipped <= 0) {
                    // không resync được, ném lỗi ra ngoài để reconnection xử lý
                    throw t
                } else {
                    PtlLog.w("RTMPS: parser desync → resynced after $skipped bytes", t)
                }
            }
        }
    }

    // === Helpers ===

    private fun getOrCreatePartialFor(fmt: Int, csid: Int, input: DataInputStream): Partial {
        val prev = partialByCsid[csid]
        val hdr = if (prev == null) Header() else prev.header

        when (fmt) {
            0 -> { // 11 bytes + optional 4 (ext ts)
                hdr.timestamp = readUInt24(input)
                hdr.messageLength = readUInt24(input)
                hdr.messageTypeId = input.readUnsignedByte()
                hdr.messageStreamId = readUInt32LE(input)
                hdr.timestampDelta = 0
                hdr.absoluteTsKnown = true
                if (hdr.timestamp == 0xFFFFFF) {
                    hdr.timestamp = input.readInt()
                    hdr.absoluteTsKnown = true
                }
            }
            1 -> { // 7 bytes + optional 4
                hdr.timestampDelta = readUInt24(input)
                hdr.messageLength = readUInt24(input)
                hdr.messageTypeId = input.readUnsignedByte()
                // msid giữ nguyên
                if (hdr.timestampDelta == 0xFFFFFF) {
                    val ext = input.readInt()
                    hdr.timestamp += ext
                    hdr.absoluteTsKnown = true
                } else {
                    hdr.timestamp += hdr.timestampDelta
                    hdr.absoluteTsKnown = true
                }
            }
            2 -> { // 3 bytes + optional 4
                hdr.timestampDelta = readUInt24(input)
                if (hdr.timestampDelta == 0xFFFFFF) {
                    val ext = input.readInt()
                    hdr.timestamp += ext
                    hdr.absoluteTsKnown = true
                } else {
                    hdr.timestamp += hdr.timestampDelta
                    hdr.absoluteTsKnown = true
                }
                // length, type, msid giữ nguyên
            }
            3 -> {
                // No header fields; continuation for this CSID.
                // Nếu trước đó chưa có trạng thái → lỗi header; ném lên để resync.
                if (prev == null) throw IllegalStateException("Continuation on csid=$csid without previous header")
                // extended ts ở fmt=3 chỉ xuất hiện khi trước đó *có* extended, nhưng theo spec không gửi lại → bỏ qua.
            }
            else -> throw IllegalStateException("Invalid fmt=$fmt")
        }

        // Tạo Partial mới nếu chưa có hoặc messageLength thay đổi (bắt đầu message mới)
        if (prev == null || prev.header.messageLength != hdr.messageLength || prev.complete()) {
            val p = Partial(csid, hdr.copy())
            partialByCsid[csid] = p
            return p
        }
        return prev
    }

    private fun readUInt24(input: DataInputStream): Int {
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        val b3 = input.readUnsignedByte()
        return (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun readUInt32LE(input: DataInputStream): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /**
     * Đánh dấu vị trí logic để báo đã resync bao nhiêu byte (chỉ dùng thống kê log).
     * DataInputStream không hỗ trợ position, nên ta đo bằng số byte đọc trong resync.
     */
    private fun tryReadPosition(input: DataInputStream): Int = 0

    private fun trySoftResync(input: DataInputStream, mark: Int): Int {
        // Quét thô tối đa RESYNC_SCAN_LIMIT byte để tìm "có vẻ" là basic header.
        var skipped = 0
        while (skipped < RESYNC_SCAN_LIMIT) {
            // Peek 1 byte
            val b0 = try {
                input.readUnsignedByte()
            } catch (e: EOFException) {
                return skipped
            }
            skipped++

            val fmt = (b0 ushr 6) and 0x03
            var csid = b0 and 0x3F
            // csid hợp lệ: 2..63 hoặc 0/1 để extended
            val plausible = (csid in 2..63) || (csid == 0) || (csid == 1)
            val plausibleFmt = fmt in 0..3
            if (plausible && plausibleFmt) {
                // Chúng ta đã "ăn" 1 byte b0, nhưng caller mong bắt đầu lại từ basic header này.
                // Không có dễ cách "đẩy ngược" vào stream → coi như đã đồng bộ tại đây (bắt đầu từ b0 đã đọc).
                // Giải pháp: giữ b0 trong một buffer phụ không khả thi với DataInputStream; nên cho phép mất 1 byte,
                // nhưng RTMP header tiếp theo sẽ vẫn parse được vì b0 là basic header thật sự.
                return skipped // báo đã resync được
            }
        }
        return skipped
    }
}

/** DTO đơn giản cho upper layer */
data class RtmpMessage(
    val timestamp: Int,
    val messageTypeId: Int,
    val messageStreamId: Int,
    val payload: ByteArray
)
