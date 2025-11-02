# 🔍 Hướng dẫn Debug Livestream YouTube

## ⚠️ QUAN TRỌNG: App hiện tại chỉ là PROTOTYPE

**App KHÔNG gửi video thật lên YouTube!** Các chức năng sau CHƯA được implement:
- ❌ Video encoding (MediaCodec H.264)
- ❌ Audio encoding (AAC)
- ❌ RTMP connection
- ❌ FLV muxing
- ❌ Data transmission

App chỉ hiển thị **FAKE METRICS** để test UI/UX flow.

---

## 📱 Cách Debug Trên Điện Thoại Thật

### Bước 1: Kết nối điện thoại
```bash
# 1. Bật USB Debugging trên điện thoại:
#    Settings > About Phone > Tap Build Number 7 times
#    Settings > Developer Options > USB Debugging > ON

# 2. Kết nối USB với máy Mac

# 3. Kiểm tra kết nối:
adb devices
```

### Bước 2: Cài APK debug
```bash
adb install -r "/Users/vu.nguyen/Documents/Copilot/Learning/Gaming Streamer/ScreenLive/build/app/outputs/flutter-apk/app-debug.apk"
```

### Bước 3: Chạy script monitor logs
```bash
cd "/Users/vu.nguyen/Documents/Copilot/Learning/Gaming Streamer/ScreenLive"
./debug_stream.sh
```

### Bước 4: Test trên điện thoại
1. Mở app ScreenLive
2. Nhập RTMP URL: `rtmp://a.rtmp.youtube.com/live2`
3. Nhập Stream Key (từ YouTube Studio)
4. Nhấn "Start Streaming"
5. **Xem logs trên terminal** để verify

---

## 📊 Hiểu Logs Output

### ✅ Logs khi stream START (prototype):
```
=== STREAM DEBUG INFO ===
RTMP URL: rtmp://a.rtmp.youtube.com/live2
Stream Key: ***abc123
Video Bitrate: 3500kbps
Audio Bitrate: 128kbps
Keyframe Interval: 2.0s
⚠️ VIDEO ENCODING NOT IMPLEMENTED
⚠️ RTMP CONNECTION NOT IMPLEMENTED
========================
✓ Prototype mode started - showing fake metrics
```

### ❌ Điều bạn SẼ KHÔNG thấy (vì chưa implement):
- "Connecting to RTMP server..."
- "Handshake successful"
- "Sending video frames"
- "Upload speed: XXX kbps"

### 📺 Trên YouTube Studio:
- **Stream Status:** ❌ Offline (vì không có data thật)
- **Error:** "Not receiving data from encoder"

---

## 🎯 Tại sao YouTube không nhận được stream?

### 1. **Chưa có RTMP Client**
App cần implement:
```kotlin
// TODO: Open socket to rtmp://a.rtmp.youtube.com/live2
val socket = Socket(rtmpServer, 1935)

// TODO: RTMP handshake
sendC0C1()
receiveS0S1S2()
sendC2()

// TODO: RTMP connect command
connectApp(streamKey)
```

### 2. **Chưa có Video Encoder**
App cần implement:
```kotlin
// TODO: Create H.264 encoder
val encoder = MediaCodec.createEncoderByType("video/avc")
encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

// TODO: Feed frames from VirtualDisplay
virtualDisplay.surface = encoder.createInputSurface()
```

### 3. **Chưa có FLV Muxer**
App cần wrap video/audio thành FLV format trước khi gửi qua RTMP.

---

## 🔧 Debug Checklist

### Kiểm tra trên điện thoại:
- [ ] App đã cài thành công
- [ ] Permissions đã granted (Mic, Notifications, Screen Capture)
- [ ] Màn hình chuyển sang Live screen (đen)
- [ ] Notification "Screen Live" hiện ở status bar
- [ ] Metrics hiển thị (60 FPS, 3.6 Mbps - fake)

### Kiểm tra logs (terminal):
- [ ] "STREAM DEBUG INFO" xuất hiện
- [ ] RTMP URL đúng
- [ ] Stream Key hiển thị (4 ký tự cuối)
- [ ] Warning "VIDEO ENCODING NOT IMPLEMENTED" xuất hiện
- [ ] "Prototype mode started" xuất hiện

### Kiểm tra YouTube Studio:
- [ ] Live Dashboard mở: https://studio.youtube.com/
- [ ] Stream key đã được tạo
- [ ] Status vẫn là "Offline" (ĐÚNG! Vì không có data thật)

---

## 💡 Next Steps để Live Stream THẬT

Để stream thật lên YouTube, cần implement 3 components chính:

### 1. Video Encoder (MediaCodec)
```kotlin
// File: VideoEncoder.kt
class VideoEncoder(width: Int, height: Int, fps: Int, bitrate: Int) {
    private val encoder = MediaCodec.createEncoderByType("video/avc")
    
    fun start(surface: Surface) {
        // Configure H.264 with baseline profile
        // Start encoding loop
        // Output to FLV muxer
    }
}
```

### 2. RTMP Client
```kotlin
// File: RtmpClient.kt
class RtmpClient(url: String, streamKey: String) {
    private var socket: Socket? = null
    
    suspend fun connect() {
        // Open socket
        // RTMP handshake
        // Send connect command
        // Send createStream command
        // Send publish command
    }
    
    suspend fun sendFlvTag(tag: FlvTag) {
        // Send FLV tag via RTMP
    }
}
```

### 3. FLV Muxer
```kotlin
// File: FlvMuxer.kt
class FlvMuxer {
    fun wrapVideoFrame(h264Data: ByteArray, timestamp: Long): FlvTag {
        // Wrap H.264 NAL units into FLV video tag
    }
    
    fun wrapAudioFrame(aacData: ByteArray, timestamp: Long): FlvTag {
        // Wrap AAC into FLV audio tag
    }
}
```

---

## 📖 Tài liệu tham khảo

- **RTMP Specification:** https://rtmp.veriskope.com/docs/spec/
- **MediaCodec Guide:** https://developer.android.com/reference/android/media/MediaCodec
- **FLV Format:** https://rtmp.veriskope.com/pdf/video_file_format_spec_v10.pdf
- **YouTube RTMP Requirements:** https://support.google.com/youtube/answer/2853702

---

## 🆘 Troubleshooting

### Q: Tại sao YouTube Dashboard không hiện gì?
**A:** Vì app chưa gửi data thật. Đây là prototype để test UI flow.

### Q: Làm sao biết app có kết nối được không?
**A:** Xem logs trong terminal. Nếu thấy "CONNECTION NOT IMPLEMENTED" là đúng.

### Q: Bao giờ có thể stream thật?
**A:** Cần implement 3 components trên (VideoEncoder, RtmpClient, FlvMuxer). Ước tính 2-3 tuần development.

### Q: App có crash không?
**A:** Không, app chạy ổn định. Chỉ là chưa có chức năng streaming thật.

---

## ✅ Tóm tắt

| Chức năng | Status | Ghi chú |
|-----------|--------|---------|
| UI/UX | ✅ Hoạt động | Setup screen, Live screen |
| Permissions | ✅ Hoạt động | MediaProjection, Mic, Notifications |
| Foreground Service | ✅ Hoạt động | Notification hiển thị |
| Fake Metrics | ✅ Hoạt động | FPS, bitrate, latency |
| Video Encoding | ❌ Chưa có | Màn hình đen |
| RTMP Connection | ❌ Chưa có | Không gửi data |
| YouTube Stream | ❌ Chưa có | Offline status |

**Kết luận:** App đã sẵn sàng cho development tiếp theo. UI/UX flow hoàn chỉnh, chỉ cần implement streaming backend.
