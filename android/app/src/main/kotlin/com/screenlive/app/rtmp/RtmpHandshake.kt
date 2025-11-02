package com.screenlive.app.rtmp

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

internal object RtmpHandshake {
    private val rnd = SecureRandom()

    fun perform(out: OutputStream, `in`: InputStream) {
        val c0c1 = ByteArray(1537)
        c0c1[0] = 0x03
        // C1: time(4) + zero(4) + random(1528)
        val randomPart = ByteArray(1528)
        rnd.nextBytes(randomPart)
        System.arraycopy(randomPart, 0, c0c1, 9, 1528)
        out.write(c0c1)
        out.flush()

        val s0s1s2 = ByteArray(1 + 1536 + 1536)
        readFully(`in`, s0s1s2)
        // optional: validate version and echo

        // C2: echo server S1+S2 parts; for simplicity send 1536 random bytes
        val c2 = ByteArray(1536)
        rnd.nextBytes(c2)
        out.write(c2)
        out.flush()
    }

    private fun readFully(`in`: InputStream, buf: ByteArray) {
        var n = 0
        while (n < buf.size) {
            val r = `in`.read(buf, n, buf.size - n)
            if (r <= 0) throw java.io.EOFException("RTMP handshake closed")
            n += r
        }
    }
}