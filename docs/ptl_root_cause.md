# PTL Root Cause Analysis - Android Screen RTMPS Failure

**Date:** 2025-10-28  
**Status:** Device fails to stream, FFmpeg works with same credentials  
**Severity:** Critical - Zero video appears on ingest

---

## Executive Summary

Terminal FFmpeg can publish to the same RTMPS URL/key and shows "pending live" on YouTube/Facebook. Android app completes initialization without errors but **no video data reaches ingest server**. Root cause identified across 4 pipeline layers.

---

## Critical Issues Identified

### **LAYER 1: MediaCodec Configuration**

#### ❌ **Issue 1.1: Missing SPS/PPS Sequence Header**
**File:** `RootEncoderService.kt:310-328` (encodeVideoLoop)  
**Evidence:**
```kotlin
if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
    // Skips codec config frames entirely
}
```

**Impact:** FLV stream never receives AVCDecoderConfigurationRecord (SPS/PPS). YouTube/Facebook ingest **requires** this before any video data. Without it, server drops all video frames as invalid.

**FFmpeg equivalent:** Automatically sends sequence header before first frame.

---

#### ❌ **Issue 1.2: No FLV Sequence Header Emission**
**File:** `MinimalFlvMuxer.kt:63-77` (createVideoTag)  
**Evidence:**
```kotlin
fun createVideoTag(data: ByteArray, timestamp: Int, isKeyframe: Boolean): ByteArray {
    val header = byteArrayOf(
        (frameType or codecId).toByte(),
        0x01,  // AVC NALU - ALWAYS sends as data packet
        0x00, 0x00, 0x00  // composition time
    )
}
```

**Problem:** AVCPacketType is **hardcoded to 0x01** (data packet). Missing logic for:
- `0x00` = AVCDecoderConfigurationRecord (SPS/PPS) - **REQUIRED** before first frame
- Must be sent with codec config buffer from MediaCodec

**Spec violation:** FLV spec mandates sequence header with AVCPacketType=0 before any AVCPacketType=1 frames.

---

#### ❌ **Issue 1.3: Same Problem for AAC**
**File:** `MinimalFlvMuxer.kt:79-95` (createAudioTag)  
**Evidence:**
```kotlin
val header = byteArrayOf(
    (soundFormat or soundRate or soundSize or soundType).toByte(),
    0x01  // AAC raw - ALWAYS
)
```

**Problem:** AACPacketType hardcoded to `0x01` (raw frames). Missing:
- `0x00` = AudioSpecificConfig (ASC) from MediaCodec output format
- Must be emitted once before audio data

---

### **LAYER 2: RTMP Protocol Issues**

#### ❌ **Issue 2.1: App Path Parsing**
**File:** `MinimalRtmpsClient.kt:139-141`  
**Evidence:**
```kotlin
val app = url.substringAfter("/").substringBefore("/").ifEmpty { "live" }
val tcUrl = url.substringBeforeLast("/")
```

**Test case:**
```
URL: rtmps://a.rtmp.youtube.com/live2
Expected: app="live2"
Actual:   app="" (empty) → falls back to "live"
Result:   tcUrl="rtmps://a.rtmp.youtube.com"
```

**Impact:** Wrong app path sent to YouTube. Server may accept connection but **route to wrong application**, causing silent failure (connection succeeds but video ignored).

**FFmpeg equivalent:** Correctly parses `/live2` as app.

---

#### ❌ **Issue 2.2: Missing SNI for TLS**
**File:** `MinimalRtmpsClient.kt:55-57`  
**Evidence:**
```kotlin
socket = if (isSecure) {
    SSLSocketFactory.getDefault().createSocket(host, port)
} else {
```

**Problem:** `SSLSocketFactory.getDefault()` does **NOT** set Server Name Indication (SNI). CDN servers (Akamai, Fastly) require SNI to route TLS connections correctly.

**Impact:** Connection may succeed but be routed to default vhost, not the RTMPS ingest endpoint.

**Fix required:** Use `SSLSocket.setHost()` or `SSLParameters` with SNI.

---

#### ❌ **Issue 2.3: Chunk Fragmentation Missing**
**File:** `MinimalRtmpsClient.kt:244-271` (sendChunk)  
**Evidence:**
```kotlin
// Send header + data (no fragmentation for now)
output?.write(header.toByteArray())
output?.write(data)
```

**Problem:** Large frames (e.g., 50KB keyframe at 720p60) sent as single chunk. RTMP spec mandates:
- Default chunk size = 128 bytes
- Must fragment messages > chunk size
- Each fragment needs proper header (Type 3 for continuation)

**Impact:** Server may reject oversized chunks or misparse stream, causing silent failure.

---

### **LAYER 3: FLV Muxing**

#### ⚠️ **Issue 3.1: Timestamp Overflow**
**File:** `RootEncoderService.kt:316`  
**Evidence:**
```kotlin
val timestamp = (System.currentTimeMillis() - startTime).toInt()
```

**Problem:** Casts `Long` to `Int`. After ~24.8 days, wraps to negative. For shorter sessions, OK for MVP but risky.

**Better:** Use modulo or cap at 24-bit max (16,777,215 ms = 4.6 hours).

---

#### ⚠️ **Issue 3.2: No StreamID Validation**
**File:** `MinimalRtmpsClient.kt:194-201` (receiveCreateStreamResponse)  
**Evidence:**
```kotlin
if (chunk.size >= 8) {
    val buffer = ByteBuffer.wrap(chunk, chunk.size - 8, 8).order(ByteOrder.BIG_ENDIAN)
    val streamIdDouble = java.lang.Double.longBitsToDouble(buffer.long)
    streamId = streamIdDouble.toInt()
} else {
    streamId = 1  // Default
}
```

**Problem:** Assumes last 8 bytes are stream ID. RTMP response format varies:
- `_result` command (AMF string) + transaction ID + null + stream ID
- Parsing from end is **fragile** - can grab wrong data

**Impact:** If streamId=0 or invalid, publish command sent to wrong stream → silent failure.

---

### **LAYER 4: MediaProjection & Display**

#### ✅ **Issue 4.1: VirtualDisplay Setup (OK)**
**File:** `RootEncoderService.kt:187-197`  
**Evidence:** Correct - size matches encoder, surface from encoder, densityDpi from display.

---

#### ⚠️ **Issue 4.2: No Frame Verification**
**File:** `RootEncoderService.kt:310-328` (encodeVideoLoop)  
**Evidence:** No logging of:
- First frame timestamp
- Keyframe intervals
- Actual bytes per frame
- SPS/PPS presence

**Impact:** No visibility into whether MediaProjection is producing frames or if encoder is generating black/invalid data.

---

## Attack Surface Summary

| Layer | Critical | Warning | Total |
|-------|----------|---------|-------|
| MediaCodec | 3 | 0 | 3 |
| RTMP Protocol | 2 | 1 | 3 |
| FLV Muxing | 0 | 2 | 2 |
| MediaProjection | 0 | 1 | 1 |
| **TOTAL** | **5** | **4** | **9** |

---

## Why FFmpeg Works But App Doesn't

| Aspect | FFmpeg (Works) | Android App (Fails) |
|--------|----------------|---------------------|
| **SPS/PPS** | Sends AVCDecoderConfigurationRecord before first frame | **Skips codec config entirely** |
| **AAC Config** | Sends AudioSpecificConfig | **Skips codec config** |
| **App Path** | Correctly parses `/live2` | **Falls back to "live"** (wrong app) |
| **SNI** | Enabled by default in libavformat | **Missing** - wrong vhost routing |
| **Chunk Size** | Fragments large frames properly | **Sends entire keyframe as one chunk** |
| **Stream ID** | Parses full AMF response | **Guesses from last 8 bytes** |

---

## Minimal Fix Priority

### **P0 (Blocker - Must Fix for Probe C)**
1. **SPS/PPS emission** - Capture CODEC_CONFIG buffers, send as AVCPacketType=0x00
2. **AAC ASC emission** - Capture CSD-0 from audio format, send as AACPacketType=0x00
3. **App path parsing** - Fix URL parsing for YouTube `/live2`
4. **SNI for TLS** - Add `SSLSocket.setHost(hostname)`

### **P1 (High - Impacts Reliability)**
5. **Chunk fragmentation** - Split messages > 128 bytes with Type 3 headers
6. **Stream ID parsing** - Parse full AMF response, not byte guessing

### **P2 (Medium - Telemetry)**
7. **Frame logging** - Add [PTL] logs for first frame, keyframes, bytes sent
8. **Timestamp safety** - Cap at 24-bit or use modulo

### **P3 (Low - Technical Debt)**
9. **Error handling** - Don't swallow exceptions in encoding loops

---

## Evidence Links

- **FLV Spec:** Adobe FLV v10.1 §E.4.3.1 (VIDEODATA), §E.4.2.1 (AUDIODATA)
- **RTMP Spec:** Adobe RTMP 1.0 §5.3.1 (Chunking), §7.2.1 (connect), §7.2.2 (publish)
- **H.264 Annex B:** ITU-T H.264 Annex B (NAL unit stream format)
- **AAC ADTS:** ISO/IEC 14496-3 (AudioSpecificConfig structure)

---

## Next Steps

1. Implement **DiagnosticMode** with 3 probes (A/B/C)
2. Fix **P0 issues** for Probe C to work
3. Add **[PTL]** logging with key masking
4. Create **troubleshooting guide** with ADB commands
5. **Build APK** and verify on device

---

**Prepared by:** PTL Audit  
**Reviewed by:** Pending  
**Status:** Ready for Implementation
