# Android RTMPS Streaming - Troubleshooting Guide

**Last Updated:** 2025-10-28  
**Target:** ScreenLive Flutter+Android App  
**Audience:** Developers debugging on-device streaming failures

---

## Quick Start - Verify Environment

### 1. Grant Permissions
```bash
# Required permissions
adb shell pm grant com.screenlive.app android.permission.RECORD_AUDIO

# Check granted permissions
adb shell dumpsys package com.screenlive.app | grep permission
```

**Expected output:**
```
android.permission.INTERNET: granted=true
android.permission.RECORD_AUDIO: granted=true
android.permission.WAKE_LOCK: granted=true
```

---

### 2. Install & Launch
```bash
# Clean build
cd "/path/to/ScreenLive"
flutter clean
flutter pub get
flutter build apk --debug

# Install
adb install -r build/app/outputs/flutter-apk/app-debug.apk

# Launch
adb shell am start -n com.screenlive.app/.MainActivity
```

---

### 3. Monitor Logs
```bash
# Filter PTL logs only
adb logcat | grep "\[PTL\]"

# Full logcat with relevant tags
adb logcat -s "PTL:*" "RootEncoder:*" "RtmpsClient:*" "FlvMuxer:*"

# Save to file
adb logcat | grep "\[PTL\]" > stream_debug.log
```

---

## Expected Log Flow (Success Case)

### **Phase 1: Initialization**
```
[PTL] RootEncoder: === STARTING ROOTENCODER STREAM ===
[PTL] RootEncoder: URL: rtmps://a.rtmp.youtube.com/live2
[PTL] RootEncoder: Key: ***xyz4
[PTL] RootEncoder: Preset: 1280x720@60fps, 3Mbps
[PTL] RootEncoder: MediaProjection granted, initializing encoders...
```

### **Phase 2: Encoder Setup**
```
[PTL] RootEncoder: Initializing video encoder...
[PTL] RootEncoder: ✓ Video encoder started
[PTL] RootEncoder: Initializing audio encoder...
[PTL] RootEncoder: ✓ Audio encoder started (microphone)
[PTL] RootEncoder: ✓ FLV muxer initialized
```

### **Phase 3: RTMPS Connection**
```
[PTL] RtmpsClient: Connecting to rtmps://a.rtmp.youtube.com/live2...
[PTL] RtmpsClient: Host: a.rtmp.youtube.com, Port: 443, Secure: true
[PTL] RtmpsClient: ✓ SNI set: a.rtmp.youtube.com
[PTL] RtmpsClient: ✓ Socket connected
[PTL] RtmpsClient: Starting RTMP handshake...
[PTL] RtmpsClient: ✓ Handshake complete
```

### **Phase 4: RTMP Commands**
```
[PTL] RtmpsClient: Sending connect command...
[PTL] RtmpsClient: App: 'live2', tcUrl: 'rtmps://a.rtmp.youtube.com'
[PTL] RtmpsClient: ✓ Connect sent
[PTL] RtmpsClient: ✓ Connect response: _result received
[PTL] RtmpsClient: Sending createStream...
[PTL] RtmpsClient: ✓ Stream ID: 1
[PTL] RtmpsClient: Sending publish (key: ***xyz4)...
[PTL] RtmpsClient: ✓ Publish sent
[PTL] RtmpsClient: ✓ RTMPS ready to stream
```

### **Phase 5: Streaming**
```
[PTL] RootEncoder: ✓ Sent FLV header + metadata
[PTL] FlvMuxer: ✓ Sent SPS/PPS (AVCDecoderConfigurationRecord)
[PTL] FlvMuxer: ✓ Sent AAC config (AudioSpecificConfig)
[PTL] RootEncoder: ✓ First keyframe @ 0ms
[PTL] RootEncoder: Frame #60, keyframe @ 2000ms
[PTL] RootEncoder: Bytes sent: 2.3 MB, Frames: 120, Keyframes: 2
```

---

## Common Failures & Fixes

### ❌ **Problem 1: "No video on ingest"**

**Symptoms:**
- Logs show "RTMPS ready to stream"
- YouTube/Facebook Live Control Room shows "Not receiving data" or stays "Pending"
- No errors in logcat

**Root Cause:** Missing SPS/PPS sequence header

**Check logs for:**
```bash
# Should see these lines:
[PTL] FlvMuxer: ✓ Sent SPS/PPS (AVCDecoderConfigurationRecord)
[PTL] FlvMuxer: ✓ Sent AAC config (AudioSpecificConfig)
```

**If missing:**
```kotlin
// RootEncoderService.kt line 310
// BEFORE FIX:
if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
    // Skip config frames - WRONG!
}

// AFTER FIX:
if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
    // Send SPS/PPS as AVCDecoderConfigurationRecord
    sendCodecConfig(data)
} else if (bufferInfo.size > 0) {
    // Send regular frames
}
```

**Verify fix:**
```bash
adb logcat | grep "SPS/PPS"
# Should appear within first 2 seconds of streaming
```

---

### ❌ **Problem 2: "Connection closes immediately"**

**Symptoms:**
```
[PTL] RtmpsClient: ✓ Socket connected
[PTL] RtmpsClient: ✓ Handshake complete
[PTL] RtmpsClient: ✗ Connect failed: Connection reset
```

**Root Cause:** Missing SNI (Server Name Indication) for TLS

**Check fix:**
```bash
adb logcat | grep "SNI"
# Should see:
[PTL] RtmpsClient: ✓ SNI set: a.rtmp.youtube.com
```

**If missing, apply fix:**
```kotlin
// MinimalRtmpsClient.kt line 55
val sslSocket = SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket

// PTL FIX: Add SNI
val sslParams = SSLParameters()
sslParams.serverNames = listOf(SNIHostName(host))
sslSocket.sslParameters = sslParams
```

---

### ❌ **Problem 3: "Wrong app path"**

**Symptoms:**
```
[PTL] RtmpsClient: App: 'live', tcUrl: 'rtmps://a.rtmp.youtube.com/live2'
```
**Expected:** `App: 'live2'`

**Root Cause:** URL parsing bug

**Fix:**
```kotlin
// MinimalRtmpsClient.kt line 139
// BEFORE:
val app = url.substringAfter("/").substringBefore("/").ifEmpty { "live" }

// AFTER:
val urlWithoutProto = url.removePrefix("rtmps://").removePrefix("rtmp://")
val pathStart = urlWithoutProto.indexOf('/')
val app = if (pathStart > 0) {
    urlWithoutProto.substring(pathStart + 1).split('/')[0]
} else {
    "live"
}
```

---

### ❌ **Problem 4: "Stream key leaked in logs"**

**Check:**
```bash
adb logcat | grep -i "key"
# Should NEVER see full key
```

**Expected:**
```
[PTL] RootEncoder: Key: ***xyz4
[PTL] RtmpsClient: Sending publish (key: ***xyz4)...
```

**If leaked, verify PtlLogger is used:**
```kotlin
// Use this:
PtlLogger.i(TAG, "Key: $streamKey")  // Auto-masks

// NOT this:
Log.i(TAG, "Key: $streamKey")  // Leaks!
```

---

## Diagnostic Mode

### Enable Diagnostic Mode
```bash
# Build with diagnostic flag
flutter build apk --debug --dart-define=DIAG=1
```

### Run Probes
1. Open app → Navigate to "Diagnostic" screen
2. Enter YouTube RTMPS URL + stream key
3. Run probes in order:

**Probe A: Handshake Only**
- Tests: TLS connect, RTMP handshake, connect/createStream/publish
- No encoders involved
- Success: All commands return `_result`

**Probe B: Synthetic Stream**
- Tests: Color bars 720p60 → FLV → RTMPS
- Skips MediaProjection
- Success: Video appears on ingest for 10s

**Probe C: Full Path**
- Tests: MediaProjection → H.264 + AAC → FLV → RTMPS
- Success: Phone screen visible on ingest for 10s

### Export Diagnostic Logs
```bash
# From app: Diagnostic screen → "Export Logs" button
# Saves to: /sdcard/Download/ptl_logs_<timestamp>.txt

# Pull from device:
adb pull /sdcard/Download/ptl_logs_*.txt
```

---

## Platform-Specific Issues

### YouTube Live

**URL Format:**
```
rtmps://a.rtmp.youtube.com/live2
Stream Key: (from YouTube Studio → Go Live → Stream settings)
```

**Common issues:**
- App must be "live2" (NOT "live")
- Port 443 or 1935 (both work with TLS)
- Latency: Normal = 20-30s, Ultra-low = 3-5s

**Verify stream:**
1. YouTube Studio → Go Live → Stream settings
2. "Stream health" should show "Excellent" or "Good"
3. "Receiving data" indicator lit
4. Preview appears within 30s

---

### Facebook Live

**URL Format:**
```
rtmps://live-api-s.facebook.com:443/rtmp/
Stream Key: (from Creator Studio → Live → Stream settings)
```

**Common issues:**
- Requires `/rtmp/` path (trailing slash matters!)
- Port 443 ONLY
- Key starts with "FB-" or alphanumeric

**Verify stream:**
1. Creator Studio → Live
2. "Stream health" shows "Good connection"
3. Preview appears within 15s

---

## Performance Benchmarks

### Expected Metrics (720p60, 3.8Mbps)

| Metric | Target | Acceptable | Poor |
|--------|--------|------------|------|
| **FPS** | 60 | 55-60 | <50 |
| **Keyframe Interval** | 2s | 1.5-2.5s | >3s |
| **Bitrate** | 3.8 Mbps | 3.5-4.2 Mbps | <3 or >5 |
| **Latency** | 20-30s | 15-40s | >60s |
| **Dropped Frames** | 0% | <1% | >2% |

### Check Metrics
```bash
# Real-time monitoring
adb logcat | grep -E "(fps|keyframe|bitrate|bytes)"

# Example output:
[PTL] RootEncoder: FPS: 59.2, Keyframe: 2.1s ago
[PTL] RootEncoder: Bytes sent: 475 KB/s (3.8 Mbps)
```

---

## Network Requirements

### Minimum Upload Speed
- **720p60 @ 3.8Mbps:** Need 5 Mbps upload (overhead)
- **Test:**
```bash
# From phone browser: fast.com or speedtest.net
# Required: Upload ≥ 5 Mbps
```

### Firewall/Proxy Issues

**If streaming fails on corporate/school Wi-Fi:**
- Port 1935 often blocked
- Use port 443 (HTTPS) instead:
```
rtmps://a.rtmp.youtube.com:443/live2
```

---

## Emergency Commands

### Force Stop App
```bash
adb shell am force-stop com.screenlive.app
```

### Clear App Data
```bash
adb shell pm clear com.screenlive.app
```

### Kill MediaProjection (if stuck)
```bash
adb shell killall -9 system_server  # WARNING: Reboots device
# Or reboot:
adb reboot
```

### Check Encoder Capabilities
```bash
adb shell dumpsys media.player | grep -A 20 "video/avc"
# Shows supported H.264 profiles/levels
```

---

## Checklist Before Reporting Bug

- [ ] Permissions granted (RECORD_AUDIO)
- [ ] Correct RTMPS URL format (rtmps://...)
- [ ] Valid stream key (test with FFmpeg first)
- [ ] Upload speed ≥ 5 Mbps
- [ ] Logs show "RTMPS ready to stream"
- [ ] Logs show "Sent SPS/PPS" and "Sent AAC config"
- [ ] No full stream key in logs
- [ ] Tried Probe A/B/C in Diagnostic Mode
- [ ] Exported PTL logs attached

---

## Reference - Working FFmpeg Command

```bash
# Test same credentials work:
ffmpeg -f lavfi -i testsrc=size=1280x720:rate=60 \
  -f lavfi -i sine=frequency=1000:sample_rate=48000 \
  -c:v libx264 -preset veryfast -b:v 3800k -maxrate 3800k -bufsize 7600k \
  -g 120 -keyint_min 120 -sc_threshold 0 \
  -c:a aac -b:a 128k -ar 48000 -ac 2 \
  -f flv "rtmps://a.rtmp.youtube.com/live2/YOUR_STREAM_KEY"
```

If FFmpeg works but app doesn't → app bug (not credentials).

---

## Support

**Logs needed for bug report:**
1. Full PTL log export (from Diagnostic Mode)
2. `adb logcat` during stream start
3. Screenshot of YouTube/Facebook ingest status
4. Network speed test results
5. Device model + Android version

**Known working devices:**
- Pixel 6/7/8 (Android 13+)
- Samsung Galaxy S21+ (Android 13+)
- OnePlus 9 Pro (Android 13+)

**Known issues:**
- Some Android 11 devices: MediaProjection callback not firing
- Xiaomi MIUI: Aggressive battery optimization kills stream
- Huawei (no GMS): SSLSocketFactory missing TLS 1.3

---

**Last Tested:** 2025-10-28  
**App Version:** 1.0.0+1  
**Build:** flutter build apk --debug
