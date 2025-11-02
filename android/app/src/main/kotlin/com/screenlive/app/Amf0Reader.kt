package com.screenlive.app

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Minimal AMF0 Reader for RTMP protocol
 * Supports: Number, Boolean, String, Object, Null, Undefined
 */
class Amf0Reader(data: ByteArray) {
    private val input = DataInputStream(ByteArrayInputStream(data))
    
    companion object {
        private const val AMF0_NUMBER = 0x00
        private const val AMF0_BOOLEAN = 0x01
        private const val AMF0_STRING = 0x02
        private const val AMF0_OBJECT = 0x03
        private const val AMF0_NULL = 0x05
        private const val AMF0_UNDEFINED = 0x06
        private const val AMF0_OBJECT_END = 0x09
    }
    
    fun readValue(): Any? {
        if (input.available() == 0) return null
        
        val type = input.readUnsignedByte()
        return when (type) {
            AMF0_NUMBER -> input.readDouble()
            AMF0_BOOLEAN -> input.readBoolean()
            AMF0_STRING -> readString()
            AMF0_OBJECT -> readObject()
            AMF0_NULL, AMF0_UNDEFINED -> null
            else -> {
                android.util.Log.w("Amf0Reader", "Unknown AMF0 type: 0x${type.toString(16)}")
                null
            }
        }
    }
    
    fun readNumber(): Double {
        val type = input.readUnsignedByte()
        if (type != AMF0_NUMBER) {
            throw IOException("Expected AMF0 Number, got 0x${type.toString(16)}")
        }
        return input.readDouble()
    }
    
    fun readString(): String {
        val type = input.readUnsignedByte()
        if (type != AMF0_STRING) {
            throw IOException("Expected AMF0 String, got 0x${type.toString(16)}")
        }
        val length = input.readUnsignedShort()
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
    
    fun readBoolean(): Boolean {
        val type = input.readUnsignedByte()
        if (type != AMF0_BOOLEAN) {
            throw IOException("Expected AMF0 Boolean, got 0x${type.toString(16)}")
        }
        return input.readBoolean()
    }
    
    fun readNull() {
        val type = input.readUnsignedByte()
        if (type != AMF0_NULL && type != AMF0_UNDEFINED) {
            throw IOException("Expected AMF0 Null/Undefined, got 0x${type.toString(16)}")
        }
    }
    
    fun readObject(): Map<String, Any?> {
        val type = input.readUnsignedByte()
        if (type != AMF0_OBJECT) {
            throw IOException("Expected AMF0 Object, got 0x${type.toString(16)}")
        }
        
        val obj = mutableMapOf<String, Any?>()
        while (true) {
            val keyLength = input.readUnsignedShort()
            if (keyLength == 0) {
                // Object end marker (0x00 0x00 0x09)
                val endMarker = input.readUnsignedByte()
                if (endMarker != AMF0_OBJECT_END) {
                    throw IOException("Expected object end marker, got 0x${endMarker.toString(16)}")
                }
                break
            }
            
            val keyBytes = ByteArray(keyLength)
            input.readFully(keyBytes)
            val key = String(keyBytes, StandardCharsets.UTF_8)
            
            val value = readValue()
            obj[key] = value
        }
        
        return obj
    }
    
    fun hasMore(): Boolean = input.available() > 0
    
    /**
     * Helper to skip to next value without parsing
     */
    fun skip() {
        readValue()
    }
}
