# Testing Guide - Screen Live App v1.0
**Date**: November 2, 2025  
**APK**: `app-release.apk (44MB)`  
**Device**: 431QHFGP224P4  
**Status**: ✅ Installed & Ready to Test

---

## 🎯 Test Scenario: Liên Quân Mobile Livestream

### **Preparation**

1. **Ensure overlay permission**:
   ```bash
   # Check if permission granted
   adb shell appops get com.screenlive.app SYSTEM_ALERT_WINDOW
   # Should return: "allow"
   ```

2. **Start logcat monitoring** (Terminal 1):
   ```bash
   adb logcat -c && adb logcat | grep PTL
   ```

3. **Have YouTube RTMPS credentials ready**:
   - RTMP URL: `rtmps://a.rtmps.youtube.com/live2`
   - Stream Key: Your YouTube stream key

---

## 📋 Test Steps

### **Phase 1: Start Stream (2 minutes)**

1. Open **Screen Live** app on device
2. Enter RTMP URL: `rtmps://a.rtmps.youtube.com/live2`
3. Enter Stream Key
4. Tap **START STREAM** button

**Expected:**
- ✅ SnackBar hiện: "Stream started! Overlay visible. Tap overlay to stop."
- ✅ Stay on setup screen (không navigate đi đâu)
- ✅ Overlay dot đỏ (32x32dp) hiện góc trái màn hình
- ✅ Logcat: `[PTL] Overlay started`
- ✅ Sau 3 giây: Overlay tự động fade xuống 30% opacity

### **Phase 2: Minimize & Launch Game (1 minute)**

1. Press **HOME** button (overlay vẫn hiện)
2. Open **Liên Quân Mobile** app
3. Wait for game to load completely

**Expected:**
- ✅ Game load bình thường (không crash)
- ✅ Overlay hiện ở góc màn hình, mờ 30%
- ✅ Touch vào game UI hoạt động bình thường (overlay không chặn)
- ✅ Logcat: NO "csid mismatch" errors

### **Phase 3: Play Match (15 minutes)**

1. Start a match (any mode)
2. Play normally for 15 minutes
3. Occasionally check overlay

**Expected:**
- ✅ Overlay tự động fade 30% khi không tương tác
- ✅ Có thể kéo overlay sang cạnh khác (drag works)
- ✅ Stream continues stably
- ✅ Frame drops < 5%
- ✅ NO "Socket is closed" errors in logcat
- ✅ NO reconnect loops

**Monitor trong logcat:**
```bash
# Should see periodic metrics updates
[PTL] OverlayService: Started (overlay visible)
# NO errors like:
# ❌ "Chunk continuation csid mismatch"
# ❌ "Socket is closed"
# ❌ "ensureAliveOrThrow: writer dead"
```

### **Phase 4: Stop Stream (1 minute)**

1. Touch overlay once (single tap)

**Expected:**
- ✅ Stream stops immediately
- ✅ Overlay disappears
- ✅ App closes (activity.finish() called)
- ✅ Logcat: `[PTL] Stream stopped successfully from overlay`
- ✅ Reopen app → back to setup screen (clean state)

---

## 🔍 Success Criteria

| Criterion | Expected | Status |
|-----------|----------|--------|
| Start stream | Overlay hiện, stay on setup screen | ⬜ |
| Game launch | No crash, overlay không che UI | ⬜ |
| 15-min gameplay | No csid mismatch, stable stream | ⬜ |
| Overlay fade | Auto 30% after 3s | ⬜ |
| Drag overlay | Smooth movement, no lag | ⬜ |
| Stop stream | Tap → stop → overlay tắt → app đóng | ⬜ |
| Reopen app | Clean state, back to setup screen | ⬜ |

---

## 🐛 Known Limitations

1. **No confirmation dialog** - Tap overlay = immediate stop (simplified UX)
2. **App closes after stop** - Must reopen app to start new stream
3. **Compact/Expanded variants** - Not implemented yet (only MINI dot)
4. **Frame drop tracking** - TODO: Will show "0 dropped" currently

---

## 📊 Collect Data

After 15-min test, collect:

1. **Logcat output**:
   ```bash
   adb logcat -d > screenlive_test_$(date +%Y%m%d_%H%M%S).log
   ```

2. **App metrics** (if implemented):
   - Total bitrate: ~6000 kbps
   - FPS: 60 (or actual game FPS)
   - Reconnect count: 0
   - Dropped frames: < 5%

3. **Issues found**:
   - Any crashes?
   - Touch blocking?
   - Overlay positioning issues?
   - Stream quality problems?

---

## 🚀 Next Steps After Test

If test **PASSES** ✅:
- Apply production-grade refactor (FloatingOverlayView 3 variants, edge snapping, debounced metrics)
- Add telemetry collection
- Implement bitrate adaptation

If test **FAILS** ❌:
- Share logcat output
- Describe exact failure scenario
- Check P0 patches applied correctly

---

## 📱 Quick Commands

```bash
# Install APK
adb install -r build/app/outputs/flutter-apk/app-release.apk

# Monitor logs
adb logcat | grep PTL

# Check overlay permission
adb shell appops get com.screenlive.app SYSTEM_ALERT_WINDOW

# Grant overlay permission (if needed)
adb shell appops set com.screenlive.app SYSTEM_ALERT_WINDOW allow

# Clear app data (reset to clean state)
adb shell pm clear com.screenlive.app

# Force stop app
adb shell am force-stop com.screenlive.app
```

---

**Ready to test!** 🎮🚀
