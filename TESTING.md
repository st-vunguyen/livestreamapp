# 🔧 Debug & Testing Guide

## 📱 Cài APK lên điện thoại

### Cách 1: Dùng script tự động (Khuyến nghị)
```bash
cd "/Users/vu.nguyen/Documents/Copilot/Learning/Gaming Streamer/ScreenLive"
./install_apk.sh
```

### Cách 2: Thủ công
```bash
adb install -r "/Users/vu.nguyen/Documents/Copilot/Learning/Gaming Streamer/ScreenLive/build/app/outputs/flutter-apk/app-debug.apk"
```

### Cách 3: Copy file APK
File APK tại: `build/app/outputs/flutter-apk/app-debug.apk` (143MB)

---

## 🔍 Debug Stream (Kiểm tra logs)

### Chạy monitor script:
```bash
cd "/Users/vu.nguyen/Documents/Copilot/Learning/Gaming Streamer/ScreenLive"
./debug_stream.sh
```

Script này sẽ hiển thị real-time logs khi bạn:
1. Mở app trên điện thoại
2. Nhập RTMP URL và Stream Key
3. Nhấn "Start Streaming"

### Logs bạn sẽ thấy:
```
=== STREAM DEBUG INFO ===
RTMP URL: rtmp://a.rtmp.youtube.com/live2
Stream Key: ***abc123
Video Bitrate: 3500kbps
⚠️ VIDEO ENCODING NOT IMPLEMENTED
⚠️ RTMP CONNECTION NOT IMPLEMENTED
✓ Prototype mode started
```

---

## ⚠️ QUAN TRỌNG: Hiểu kết quả

### ✅ Những gì ĐÃ HOẠT ĐỘNG:
- UI/UX flow hoàn chỉnh
- Permissions handling
- MediaProjection setup
- Foreground Service
- Fake metrics (FPS, bitrate)

### ❌ Những gì CHƯA HOẠT ĐỘNG:
- **Video encoding** → Màn hình đen
- **RTMP connection** → Không gửi data lên YouTube
- **YouTube stream** → Status vẫn "Offline"

### 📺 YouTube Studio sẽ hiển thị:
```
Status: ❌ Offline
Error: "Waiting for stream data..."
```

**ĐÂY LÀ ĐÚNG!** Vì app chưa implement streaming backend.

---

## 📊 File Locations

| File | Path | Mục đích |
|------|------|----------|
| Debug APK | `build/app/outputs/flutter-apk/app-debug.apk` | Cài lên điện thoại để test |
| Release APK | `build/app/outputs/flutter-apk/app-release-aligned.apk` | Production (46MB, signed) |
| Install Script | `install_apk.sh` | Cài APK tự động |
| Debug Script | `debug_stream.sh` | Monitor logs real-time |
| Debug Guide | `DEBUG_GUIDE.md` | Hướng dẫn chi tiết |

---

## 🎯 Test Checklist

### Trên điện thoại:
- [ ] Cài APK thành công
- [ ] Mở app không crash
- [ ] Grant permissions (Mic, Notifications, Screen Capture)
- [ ] Nhập RTMP URL và Stream Key
- [ ] Nhấn "Start Streaming"
- [ ] Màn hình chuyển sang Live (đen)
- [ ] Notification "Screen Live" hiện trên status bar
- [ ] Metrics hiển thị (60 FPS, 3.6 Mbps - fake)

### Trên logs (terminal):
- [ ] "STREAM DEBUG INFO" xuất hiện
- [ ] RTMP URL đúng
- [ ] Stream Key (4 ký tự cuối)
- [ ] Warning messages đầy đủ
- [ ] "Prototype mode started"

### Trên YouTube Studio:
- [ ] Dashboard mở được
- [ ] Stream key đã tạo
- [ ] Status: "Offline" (đúng vì không có data)

---

## 🚀 Next Steps

Để stream THẬT lên YouTube, cần implement:

1. **Video Encoder** (MediaCodec H.264)
2. **Audio Encoder** (AAC)  
3. **RTMP Client** (Socket connection + handshake)
4. **FLV Muxer** (Wrap video/audio thành FLV format)

Xem chi tiết trong `DEBUG_GUIDE.md`

---

## 💡 Quick Commands

```bash
# Build debug APK
flutter build apk --debug

# Install to phone
./install_apk.sh

# Monitor logs
./debug_stream.sh

# Check connected devices
adb devices

# Get YouTube stream info from phone
adb logcat -d | grep "STREAM DEBUG"
```

---

## 📞 Troubleshooting

**Q: "No device found"**
- Bật USB Debugging trên điện thoại
- Kết nối lại USB cable
- Chạy `adb devices` để verify

**Q: YouTube không hiện stream**
- Đây là ĐÚNG! App chưa gửi data thật
- Chỉ là prototype để test UI

**Q: Màn hình đen khi streaming**
- Đây là ĐÚNG! Chưa implement video encoder
- App hiển thị fake metrics để test UI flow

**Q: Làm sao biết app đang chạy?**
- Xem notification "Screen Live"
- Xem metrics trên màn hình
- Xem logs trong terminal (./debug_stream.sh)
