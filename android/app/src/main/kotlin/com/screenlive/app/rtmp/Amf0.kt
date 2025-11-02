package com.screenlive.app.rtmp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.and

/** Minimal AMF0 reader/writer: supports Number, Boolean, String, Null, Object (partial), ECMA Array (partial) */
class Amf0Reader(private val data: ByteArray) {
    private var p = 0

    private fun require(n: Int) {
        if (p + n > data.size) throw EOFException("AMF0: need $n bytes, have ${data.size - p}")
    }

    fun readAny(): Any? {
        val t = readType()
        return when (t) {
            0x00 -> readNumber()
            0x01 -> readBoolean()
            0x02 -> readString()
            0x03 -> readObject()
            0x05 -> { /* null */ null }
            0x06 -> { /* undefined */ null }
            0x08 -> readEcmaArray()
            0x0A -> readStrictArray()
            else -> throw IllegalStateException("AMF0: unsupported type $t at $p")
        }
    }

    fun readType(): Int { require(1); return (data[p++].toInt() and 0xFF) }

    fun readNumber(): Double {
        require(8)
        val bb = ByteBuffer.wrap(data, p, 8).order(ByteOrder.BIG_ENDIAN)
        p += 8
        return bb.double
    }

    fun readBoolean(): Boolean { require(1); return data[p++].toInt() != 0 }

    fun readUInt16(): Int { require(2); val v = ((data[p].toInt() and 0xFF) shl 8) or (data[p+1].toInt() and 0xFF); p+=2; return v }

    fun readString(): String {
        val len = readUInt16()
        require(len)
        val s = String(data, p, len, Charsets.UTF_8)
        p += len
        return s
    }

    fun readLongString(): String {
        require(4)
        val len = ((data[p].toInt() and 0xFF) shl 24) or ((data[p+1].toInt() and 0xFF) shl 16) or ((data[p+2].toInt() and 0xFF) shl 8) or (data[p+3].toInt() and 0xFF)
        p+=4
        require(len)
        val s = String(data, p, len, Charsets.UTF_8)
        p += len
        return s
    }

    fun readObject(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        while (true) {
            if (p + 3 <= data.size && data[p] == 0x00.toByte() && data[p+1] == 0x00.toByte() && data[p+2] == 0x09.toByte()) {
                p += 3 // object end marker
                break
            }
            val keyLen = readUInt16()
            require(keyLen)
            val key = String(data, p, keyLen, Charsets.UTF_8)
            p += keyLen
            val value = readAny()
            map[key] = value
        }
        return map
    }

    fun readEcmaArray(): Map<String, Any?> {
        require(4) // array length (hint)
        p += 4
        return readObject()
    }

    fun readStrictArray(): List<Any?> {
        require(4)
        val count = ((data[p].toInt() and 0xFF) shl 24) or ((data[p+1].toInt() and 0xFF) shl 16) or ((data[p+2].toInt() and 0xFF) shl 8) or (data[p+3].toInt() and 0xFF)
        p += 4
        val out = ArrayList<Any?>(count)
        repeat(count) { out += readAny() }
        return out
    }

    fun hasMore(): Boolean = p < data.size
}

class Amf0Writer {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    fun writeAny(v: Any?) {
        when (v) {
            null -> writeNull()
            is Double -> writeNumber(v)
            is Float -> writeNumber(v.toDouble())
            is Int -> writeNumber(v.toDouble())
            is Long -> writeNumber(v.toDouble())
            is Boolean -> writeBoolean(v)
            is String -> writeString(v)
            is Map<*, *> -> writeObject(v as Map<String, Any?>)
            is List<*> -> writeStrictArray(v as List<Any?>)
            else -> throw IllegalArgumentException("AMF0: unsupported value ${v::class.java}")
        }
    }

    fun writeNumber(d: Double) {
        out.write(0x00)
        val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        bb.putDouble(d)
        out.write(bb.array())
    }

    fun writeBoolean(b: Boolean) {
        out.write(0x01)
        out.write(if (b) 1 else 0)
    }

    fun writeString(s: String) {
        out.write(0x02)
        val bytes = s.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        out.write((len shr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(bytes)
    }

    fun writeNull() { out.write(0x05) }

    fun writeObject(map: Map<String, Any?>) {
        out.write(0x03)
        for ((k, v) in map) {
            val kb = k.toByteArray(Charsets.UTF_8)
            out.write((kb.size shr 8) and 0xFF)
            out.write(kb.size and 0xFF)
            out.write(kb)
            writeAny(v)
        }
        // object end marker
        out.write(byteArrayOf(0x00, 0x00, 0x09))
    }

    fun writeEcmaArray(map: Map<String, Any?>) {
        out.write(0x08)
        val count = map.size
        out.write(byteArrayOf(
            ((count ushr 24) and 0xFF).toByte(),
            ((count ushr 16) and 0xFF).toByte(),
            ((count ushr 8) and 0xFF).toByte(),
            (count and 0xFF).toByte()))
        for ((k, v) in map) {
            val kb = k.toByteArray(Charsets.UTF_8)
            out.write((kb.size shr 8) and 0xFF)
            out.write(kb.size and 0xFF)
            out.write(kb)
            writeAny(v)
        }
        out.write(byteArrayOf(0x00, 0x00, 0x09))
    }

    fun writeStrictArray(list: List<Any?>) {
        out.write(0x0A)
        val count = list.size
        out.write(byteArrayOf(
            ((count ushr 24) and 0xFF).toByte(),
            ((count ushr 16) and 0xFF).toByte(),
            ((count ushr 8) and 0xFF).toByte(),
            (count and 0xFF).toByte()))
        for (v in list) writeAny(v)
    }
}