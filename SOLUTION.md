# 🔧 GIẢI PHÁP: Sử Dụng Thư Viện RTMP Chuyên Nghiệp

## ❌ Vấn Đề Hiện Tại

Custom RTMP implementation có nhiều vấn đề:
- ✅ Handshake OK
- ✅ Connect command được gửi
- ❌ YouTube server đóng kết nối ngay (Read -1)
- **Nguyên nhân**: AMF encoding/chunking còn lỗi tinh vi

**FFmpeg test**: ✅ Stream key vẫn hoạt động bình thường
**Custom RTMP**: ❌ Bị YouTube reject

---

## ✅ Giải Pháp: Pedro SG94's RootEncoder

### 1️⃣ Thêm Dependency

**File**: `android/app/build.gradle`

```gradle
dependencies {
    implementation 'com.github.pedroSG94.RootEncoder:library:2.4.8'
    // ... existing dependencies
}
```

**File**: `android/settings.gradle` (hoặc `settings.gradle.kts`)

```gradle
dependencyResolutionManagement {
    repositories {
        maven { url 'https://jitpack.io' }
        // ... existing repos
    }
}
```

---

### 2️⃣ Refactor `PublishHandler.kt`

Thay thế custom `RtmpClient` bằng `RtmpDisplay`:

```kotlin
package com.screenlive.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.pedro.encoder.input.gl.SpriteGestureController
import com.pedro.library.rtmp.RtmpDisplay
import com.pedro.library.util.sources.audio.AudioSource
import com.pedro.library.util.sources.audio.MicrophoneSource
import kotlinx.coroutines.*
import net.ossrs.rtmp.ConnectCheckerRtmp

class PublishHandler(
    private val context: Context,
    private val videoEncoder: VideoEncoder,
    private val audioEncoder: AudioEncoder
) : ConnectCheckerRtmp {

    private var rtmpDisplay: RtmpDisplay? = null
    private var isPublishing = false
    
    companion object {
        private const val TAG = "PublishHandler"
    }

    // ConnectCheckerRtmp callbacks
    override fun onConnectionStartedRtmp(rtmpUrl: String) {
        Log.i(TAG, "RTMP connection started: $rtmpUrl")
    }

    override fun onConnectionSuccessRtmp() {
        Log.i(TAG, "✓ RTMP connected successfully!")
        isPublishing = true
    }

    override fun onConnectionFailedRtmp(reason: String) {
        Log.e(TAG, "RTMP connection failed: $reason")
        isPublishing = false
    }

    override fun onNewBitrateRtmp(bitrate: Long) {
        Log.d(TAG, "Bitrate: ${bitrate / 1000} kbps")
    }

    override fun onDisconnectRtmp() {
        Log.i(TAG, "RTMP disconnected")
        isPublishing = false
    }

    override fun onAuthErrorRtmp() {
        Log.e(TAG, "RTMP auth error - check stream key")
    }

    override fun onAuthSuccessRtmp() {
        Log.i(TAG, "✓ RTMP auth success")
    }

    suspend fun startPublish(
        mediaProjectionIntent: Intent,
        rtmpUrl: String,
        streamKey: String
    ) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "=== STARTING RTMP STREAM (RootEncoder) ===")
            Log.i(TAG, "RTMP URL: $rtmpUrl")
            Log.i(TAG, "Stream Key: ***${streamKey.takeLast(4)}")
            
            // Initialize RtmpDisplay
            rtmpDisplay = RtmpDisplay(context, this@PublishHandler).apply {
                // Configure video
                prepareVideo(
                    width = 1920,
                    height = 1080,
                    fps = 60,
                    bitrate = 6_000_000, // 6 Mbps
                    iFrameInterval = 2,  // Keyframe every 2s
                    rotation = 0
                )
                
                // Configure audio
                prepareAudio(
                    sampleRate = 48000,
                    isStereo = true,
                    echoCanceler = true,
                    noiseSuppressor = true,
                    bitrate = 128_000 // 128 kbps
                )
                
                // Set audio source (try internal audio, fallback to mic)
                setAudioSource(AudioSource.INTERNAL)
            }
            
            // Full RTMP URL
            val fullUrl = "$rtmpUrl/$streamKey"
            
            // Start streaming
            rtmpDisplay?.startStream(mediaProjectionIntent, fullUrl)
            
            Log.i(TAG, "✓ Stream started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting publish", e)
            throw Exception("Failed to start RTMP stream: ${e.message}")
        }
    }

    fun stopPublish() {
        Log.d(TAG, "Stopping publish")
        rtmpDisplay?.stopStream()
        isPublishing = false
        Log.i(TAG, "✓ Publish stopped")
    }

    fun isStreaming() = isPublishing && rtmpDisplay?.isStreaming == true
}
```

---

### 3️⃣ Update `ScreenCaptureService.kt`

```kotlin
private fun startStreaming(rtmpUrl: String, streamKey: String) {
    val intent = mediaProjection?.let {
        // Create intent from MediaProjection
        Intent()
    } ?: run {
        Log.e(TAG, "MediaProjection is null")
        return
    }
    
    lifecycleScope.launch {
        try {
            publishHandler.startPublish(intent, rtmpUrl, streamKey)
            // Notify Flutter
            sendEventToFlutter("STREAM_STARTED", null)
        } catch (e: Exception) {
            Log.e(TAG, "Start streaming failed", e)
            sendEventToFlutter("PUBLISH_ERROR", e.message)
        }
    }
}
```

---

### 4️⃣ Xóa Custom RTMP Files

Sau khi test thành công với RootEncoder, xóa:
- `RtmpClient.kt` (custom implementation)
- `FlvMuxer.kt` (không cần nữa)

---

### 5️⃣ Test Lại

```bash
# Build
cd ScreenLive
flutter build apk --debug

# Install
adb install -r build/app/outputs/flutter-apk/app-debug.apk

# Monitor logs
adb logcat | grep -E "(PublishHandler|RTMP|VideoEncoder)"
```

---

## 🎯 Kết Quả Mong Đợi

```
I PublishHandler: === STARTING RTMP STREAM (RootEncoder) ===
I PublishHandler: RTMP URL: rtmp://a.rtmp.youtube.com/live2
I PublishHandler: Stream Key: ***8kp8
I PublishHandler: RTMP connection started
I PublishHandler: ✓ RTMP auth success
I PublishHandler: ✓ RTMP connected successfully!
I PublishHandler: Bitrate: 5200 kbps
```

YouTube Live Control Room: **"Receiving data"** ✅

---

## 📝 Tại Sao RootEncoder?

| Feature | Custom RTMP | RootEncoder |
|---------|-------------|-------------|
| RTMP Protocol | ❌ Có lỗi | ✅ Production-ready |
| YouTube Support | ❌ Bị reject | ✅ Tested |
| Maintenance | ❌ Phải tự fix | ✅ Community support |
| Audio Capture | ⚠️ Phải tự code | ✅ Built-in |
| Error Handling | ❌ Generic | ✅ Detailed callbacks |

---

## 🚀 Bước Tiếp Theo

Nếu bạn muốn tôi implement ngay:
1. Thêm dependency vào `build.gradle`
2. Refactor `PublishHandler.kt`
3. Test và verify

**Ước tính thời gian**: 15-20 phút

Bạn muốn tôi thực hiện ngay không? 🎯
