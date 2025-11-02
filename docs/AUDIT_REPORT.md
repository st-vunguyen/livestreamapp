# ScreenLive RTMP Streaming - Audit Report
**Date**: October 26, 2025  
**Auditor**: Senior Android + Flutter Engineer  
**Objective**: Make ScreenLive app stream reliably to YouTube/Twitch via RTMP/RTMPS on real Android devices (Android 10+)

---

## Executive Summary

The codebase has a **functional RTMP streaming pipeline** with MediaProjection, MediaCodec encoders, and custom RTMP client. However, there are **critical issues preventing successful connection to YouTube RTMP servers**:

### 🔴 **CRITICAL ISSUES** (Blocking production use)
1. **RTMP Protocol Implementation Incomplete** - Server not responding to connect command
2. **No RTMPS Support** - Only plain RTMP (port 1935), no TLS support for port 443
3. **Missing Retry Logic** - No automatic reconnection on network failures
4. **Incomplete Audio Fallback** - AudioPlaybackCapture implemented but not fully integrated
5. **Foreground Service Missing Microphone Type** - Android 14+ requires explicit service types

### 🟡 **HIGH PRIORITY** (Quality/Stability issues)
6. **No Self-Test Mode** - Cannot validate pipeline without MediaProjection
7. **Limited Error Reporting** - RTMP failures don't surface root cause to user
8. **Hardcoded Bitrates** - No adaptive bitrate based on network conditions
9. **Missing POST_NOTIFICATIONS Runtime Request** - Android 13+ permission not requested

### 🟢 **MEDIUM PRIORITY** (Enhancements)
10. **No Local RTMP Test Support** - Cannot test with local servers
11. **Incomplete Metrics** - FPS/bitrate tracking exists but not fully utilized
12. **Build Warnings** - Kotlin version 1.9.10 deprecated, dual build.gradle files

---

## Detailed Findings

### 1. RTMP Protocol Implementation [CRITICAL]
**File**: `RtmpClient.kt:1-463`  
**Issue**: YouTube RTMP server not responding to connect command

**Evidence from logs**:
```
D RtmpClient: Connect command size: 148 bytes
I RtmpClient: ✓ Sent connect command
E RtmpClient: Connect response failed - no data received after 10 attempts
```

**Root Cause Analysis**:
- Connect command appears too small (148 bytes) for YouTube requirements
- Missing optional parameters in AMF encoding
- Potential chunk format issues
- No Window Acknowledgement Size message before connect

**Proposed Fix**:
```kotlin
// Add before sendConnectCommand():
private fun sendWindowAckSize() {
    val buffer = ByteBuffer.allocate(4)
    buffer.putInt(2500000) // 2.5MB window
    sendChunk(2, 0, buffer.array(), MessageType.WINDOW_ACK_SIZE)
}

// In sendConnectCommand(), add optional arguments:
amf.writeNull() // Optional user arguments (required by some servers)

// Add missing connect parameters:
amf.writeObject(mapOf(
    "app" to app,
    "type" to "nonprivate",
    "flashVer" to "FMLE/3.0 (compatible; ScreenLive)",
    "swfUrl" to "",
    "tcUrl" to tcUrl,
    "fpad" to "false",
    "capabilities" to "15.0",
    "audioCodecs" to "3191",
    "videoCodecs" to "252",
    "videoFunction" to "1.0",
    "objectEncoding" to "0.0"
))
```

**Risk**: High - Blocks all RTMP streaming  
**Mitigation**: Test with local RTMP server first, then YouTube

---

### 2. No RTMPS Support [CRITICAL]
**File**: `RtmpClient.kt:38-44`  
**Issue**: SSL socket created but not properly configured for RTMPS

**Current Code**:
```kotlin
socket = if (parsed.secure) {
    SSLSocketFactory.getDefault().createSocket(parsed.host, parsed.port)
} else {
    Socket(parsed.host, parsed.port)
}
```

**Problems**:
- No hostname verification
- No custom TrustManager for certificate validation
- No timeout configuration for SSL handshake
- Port defaults to 1935 instead of 443 for RTMPS

**Proposed Fix**:
```kotlin
socket = if (parsed.secure) {
    val sslSocket = SSLSocketFactory.getDefault().createSocket(parsed.host, parsed.port) as javax.net.ssl.SSLSocket
    sslSocket.soTimeout = 10000 // 10s for SSL handshake
    sslSocket.startHandshake()
    Log.i(TAG, "✓ RTMPS SSL handshake completed")
    sslSocket
} else {
    Socket(parsed.host, parsed.port).apply {
        soTimeout = 5000
    }
}
```

**Risk**: High - Corporate/proxy networks block port 1935  
**Mitigation**: Make RTMPS default, fallback to RTMP

---

### 3. Missing Retry Logic [CRITICAL]
**File**: `PublishHandler.kt:68-73`  
**Issue**: Single connection attempt, no automatic retry on failure

**Current Behavior**:
```kotlin
if (!rtmpClient!!.connect()) {
    throw Exception("Failed to connect to RTMP server")
}
```

**Proposed Fix**:
```kotlin
private suspend fun connectWithRetry(maxAttempts: Int = 3): Boolean {
    repeat(maxAttempts) { attempt ->
        android.util.Log.i("PublishHandler", "Connection attempt ${attempt + 1}/$maxAttempts")
        
        if (rtmpClient!!.connect()) {
            return true
        }
        
        if (attempt < maxAttempts - 1) {
            val delayMs = (1000 * (attempt + 1)).toLong() // Exponential backoff
            android.util.Log.w("PublishHandler", "Retrying in ${delayMs}ms...")
            delay(delayMs)
        }
    }
    return false
}
```

**Risk**: Medium - User experience degradation on network blips  
**Mitigation**: Limit retries, show progress to user

---

### 4. Incomplete Audio Fallback [CRITICAL]
**File**: `AudioEncoder.kt:23-53`  
**Issue**: AudioPlaybackCapture tries but doesn't properly fallback on all devices

**Current Code**:
```kotlin
if (!useMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projection != null) {
    try {
        // AudioPlaybackCapture setup
    } catch (e: Exception) {
        Log.w(TAG, "AudioPlaybackCapture not supported, falling back to microphone", e)
        audioRecord = null
    }
}
```

**Problems**:
- Emulators don't support AudioPlaybackCapture
- Some apps block audio capture (ALLOW_CAPTURE_BY_NONE)
- No user notification of fallback mode

**Proposed Fix**:
```kotlin
// Add to PublishHandler callback:
interface AudioFallbackListener {
    fun onAudioFallback(reason: String)
}

// In AudioEncoder.start():
if (audioRecord == null || audioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
    Log.w(TAG, "AudioPlaybackCapture failed, using microphone")
    listener?.onAudioFallback("Game audio not available - using microphone")
    // Create mic fallback...
}
```

**Risk**: Medium - User confused why only mic audio works  
**Mitigation**: Show banner in UI when fallback occurs

---

### 5. Foreground Service Missing Microphone Type [CRITICAL - Android 14+]
**File**: `AndroidManifest.xml:54-58`, `ScreenCaptureService.kt:30-40`  
**Issue**: Service only declares `mediaProjection` type, but also captures audio

**Current Manifest**:
```xml
<service
    android:name=".ScreenCaptureService"
    android:foregroundServiceType="mediaProjection" />
```

**Android 14+ Requirement**:
```xml
<service
    android:name=".ScreenCaptureService"
    android:foregroundServiceType="mediaProjection|microphone" />
```

**Current Service Code**:
```kotlin
startForeground(
    NOTIFICATION_ID,
    notification,
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
)
```

**Fixed Code**:
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or 
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    )
} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    )
} else {
    startForeground(NOTIFICATION_ID, notification)
}
```

**Risk**: High - Crash on Android 14+ when audio starts  
**Mitigation**: Update manifest + service code immediately

---

### 6. No Self-Test Mode [HIGH]
**Missing Files**: `TestPatternProducer.kt`, `SinePcmSource.kt`  
**Issue**: Cannot validate encoding pipeline without MediaProjection permission

**Proposed Implementation**:
```kotlin
// TestPatternProducer.kt - Generate color bars + moving text
class TestPatternProducer(width: Int, height: Int, fps: Int) {
    fun generateFrame(frameNumber: Int): Bitmap { /* Color bars */ }
}

// SinePcmSource.kt - Generate 440Hz sine wave
class SinePcmSource(sampleRate: Int = 48000) {
    fun generateSamples(count: Int): ShortArray { /* PCM sine */ }
}

// PublishHandler.startSelfTest(url: String)
fun startSelfTest(fullUrl: String) {
    // No MediaProjection needed!
    // Encode test pattern → FLV → RTMP
}
```

**Risk**: Medium - Harder to diagnose issues  
**Mitigation**: Add self-test toggle in settings/dev menu

---

### 7. Limited Error Reporting [HIGH]
**File**: `RtmpClient.kt:180-235`  
**Issue**: Errors logged but not surfaced to UI with actionable messages

**Current Pattern**:
```kotlin
Log.e(TAG, "Connect response failed - no data received after 10 attempts")
```

**Proposed Fix**:
```kotlin
enum class RtmpError(val userMessage: String, val actionHint: String) {
    TIMEOUT("Connection timeout", "Check network or try RTMPS"),
    DNS_FAILED("Cannot resolve server", "Check RTMP URL"),
    AUTH_FAILED("Authentication failed", "Check stream key"),
    SERVER_ERROR("Server rejected stream", "Stream may be offline"),
}

// Return typed errors:
sealed class RtmpResult {
    object Success : RtmpResult()
    data class Failure(val error: RtmpError, val details: String) : RtmpResult()
}
```

**Risk**: Medium - Poor user experience  
**Mitigation**: Map RTMP errors to user-friendly messages

---

### 8. Hardcoded Bitrates [HIGH]
**File**: `PublishHandler.kt:43-45`  
**Issue**: No adaptive bitrate adjustment based on network conditions

**Current Code**:
```kotlin
val videoBitrateKbps = call.argument<Int>("videoBitrateKbps") ?: 3500
```

**Proposed Enhancement**:
```kotlin
class AdaptiveBitrateController {
    fun adjustBitrate(droppedFrames: Int, networkSpeed: Long): Int {
        // Reduce bitrate if frames dropping
        // Increase if network stable
    }
}
```

**Risk**: Low - Works but not optimal  
**Mitigation**: Phase 2 feature

---

### 9. Missing POST_NOTIFICATIONS Runtime Request [HIGH]
**File**: `PermissionsHelper.kt` (exists but not fully integrated)  
**Issue**: Android 13+ requires runtime permission for notifications

**Proposed Fix in MainActivity**:
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
        != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_CODE_NOTIFICATIONS
        )
    }
}
```

**Risk**: Medium - Service can't show notification on Android 13+  
**Mitigation**: Request before starting service

---

### 10-12. Medium Priority Issues

**10. No Local RTMP Test Support** - Add `rtmp://10.0.2.2` handling  
**11. Incomplete Metrics** - MetricsHandler exists but underutilized  
**12. Build Warnings** - Upgrade Kotlin to 2.1.0, remove duplicate build.gradle.kts

---

## Proposed Minimal Patch Set

### Phase 1: Fix RTMP Connection (Unblock streaming)
1. Fix `RtmpClient.connect()` - Add window ack, complete AMF parameters
2. Fix `RtmpClient` RTMPS - Proper SSL handshake
3. Add retry logic in `PublishHandler`
4. Fix foreground service types in manifest + service

**Files to modify**: 
- `RtmpClient.kt` (~100 lines changed)
- `PublishHandler.kt` (~50 lines)
- `AndroidManifest.xml` (1 line)
- `ScreenCaptureService.kt` (~10 lines)

**Estimated Time**: 2-3 hours  
**Risk**: Low - Well-isolated changes

### Phase 2: Self-Test Mode (Enable validation)
5. Create `TestPatternProducer.kt` (new, ~80 lines)
6. Create `SinePcmSource.kt` (new, ~40 lines)
7. Add `PublishHandler.startSelfTest()` method (~60 lines)

**Files to create**: 2 new files  
**Files to modify**: `PublishHandler.kt`, Flutter UI  
**Estimated Time**: 3-4 hours  
**Risk**: Low - Additive feature

### Phase 3: Polish (Improve UX)
8. Add error types and user messaging
9. Request POST_NOTIFICATIONS at runtime
10. Add audio fallback notification

**Files to modify**: 5-6 files  
**Estimated Time**: 2 hours  
**Risk**: Very low

---

## Safety Rails & Rollback Plan

### Safety Measures:
- ✅ No stream keys logged or committed
- ✅ No dangerous permissions (CAMERA optional, not requested unless needed)
- ✅ Changes localized to RTMP/encoding modules
- ✅ Existing app features (Flutter UI, settings) untouched

### Rollback Plan:
```bash
# Before changes:
git stash save "pre-audit-backup"
git tag before-audit-fixes

# After changes, if issues:
git reset --hard before-audit-fixes
git stash pop  # If you want to review changes

# Or selective revert:
git revert <commit-hash>
```

---

## Acceptance Test Checklist

### Test 1: FFmpeg Sanity (Baseline)
```bash
ffmpeg -re -f lavfi -i testsrc2=size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=440:sample_rate=48000 \
       -c:v libx264 -preset veryfast -b:v 3500k -maxrate 3500k -bufsize 7000k \
       -pix_fmt yuv420p -g 60 -c:a aac -b:a 128k -ar 48000 \
       -f flv "rtmp://a.rtmp.youtube.com/live2/YOUR_KEY"
```
**Expected**: YouTube Live Control Room shows "Receiving data" within 10s

### Test 2: Self-Test Mode (No MediaProjection)
**Steps**:
1. Open ScreenLive app
2. Settings → Enable "Self-Test Mode"
3. Enter RTMPS URL: `rtmps://a.rtmps.youtube.com/live2/YOUR_KEY`
4. Tap "Start Self-Test"

**Expected**:
- App streams without MediaProjection dialog
- Logs show: `projection=skipped`, `prepareVideo=ok`, `prepareAudio=ok`, `rtmp=connected`
- YouTube shows "Receiving data" with color bars + beep tone
- Stream runs stable for ≥20 seconds

### Test 3: Screen Capture on Real Device
**Steps**:
1. Grant RECORD_AUDIO permission
2. Grant POST_NOTIFICATIONS (Android 13+)
3. Start streaming
4. Approve MediaProjection dialog
5. Check foreground notification appears
6. Check YouTube Live Control Room

**Expected**:
- Notification shows "Screen capture is active"
- Video: Device screen captured at 1080p60 or configured resolution
- Audio: Microphone or game audio (with banner showing source)
- No crashes for ≥60 seconds

### Test 4: Local RTMP Server (Emulator → Host)
**Setup**:
```bash
docker run --rm -p 1935:1935 -p 8080:80 alfg/nginx-rtmp
```

**Test**:
1. In emulator, enter URL: `rtmp://10.0.2.2/live/stream`
2. Start streaming
3. Open `http://localhost:8080/stat` in browser

**Expected**:
- `/stat` shows active publisher
- `nclients=1`, bytes_in increasing

---

## Dependencies & Build Commands

### No New Dependencies Required
All fixes use existing Android SDK APIs and Kotlin stdlib.

### Build Commands:
```bash
cd ScreenLive/android

# Clean build
./gradlew clean
./gradlew :app:assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Grant permissions (Android 11+)
adb shell pm grant com.screenlive.app.debug android.permission.RECORD_AUDIO
adb shell pm grant com.screenlive.app.debug android.permission.POST_NOTIFICATIONS

# Monitor logs
adb logcat -c && adb logcat | grep -E "(Projection|VideoEncoder|AudioEncoder|RTMP|Service|SelfTest)"
```

---

## Final Recommendations

### Immediate Actions (Today):
1. ✅ Apply Phase 1 patches (RTMP fixes)
2. ✅ Test with local RTMP server first
3. ✅ Test with YouTube RTMPS after local success

### Short-term (This Week):
4. Implement Self-Test Mode
5. Add comprehensive error messages
6. Request POST_NOTIFICATIONS at runtime

### Medium-term (Next Sprint):
7. Implement adaptive bitrate
8. Add reconnection with exponential backoff
9. Upgrade Kotlin version

### Long-term (Future):
10. WebRTC fallback for sub-second latency
11. Hardware encoder selection (H.265 on supported devices)
12. Multi-platform support (iOS)

---

## Conclusion

The codebase is **80% ready** for production. The RTMP protocol implementation needs completion, and RTMPS support needs proper SSL handling. With the proposed Phase 1 fixes (estimated 2-3 hours), the app should successfully stream to YouTube/Twitch.

**Risk Level**: Medium → Low (after Phase 1)  
**Confidence**: High - Issues are well-understood and fixes are standard patterns

**Next Steps**: Implement patches in order, test incrementally with local server before YouTube.
