# ScreenLive - Quick Test Summary

## ✅ AUDIT COMPLETE

**Status**: Codebase audited, APK ready for real device testing  
**Build**: `app-debug.apk` (October 26, 2025)  
**Location**: `build/app/outputs/flutter-apk/app-debug.apk`

---

## 📱 INSTALL & TEST (3 Minutes)

### 1. Install APK on Real Device
```bash
adb install -r build/app/outputs/flutter-apk/app-debug.apk

# Grant permissions
adb shell pm grant com.screenlive.app.debug android.permission.RECORD_AUDIO
adb shell pm grant com.screenlive.app.debug android.permission.POST_NOTIFICATIONS
```

### 2. Test with YouTube (Recommended)
**In App**:
- RTMP URL: `rtmps://a.rtmps.youtube.com/live2` ← Use RTMPS (port 443)
- Stream Key: `YOUR_KEY_HERE`
- Tap "Start Streaming" → Grant MediaProjection
- Tap "Go Live"

**Expected**: YouTube Live Control Room shows "Receiving data" in 5-10 seconds

### 3. Monitor Logs
```bash
adb logcat | grep -E "(RTMP|VideoEncoder|AudioEncoder)" --color=always
```

**Success Indicators**:
```
✓ Video encoder started successfully
✓ RTMP handshake completed
✓ Received _result response
✓ RTMP connected and publishing
```

---

## 🐛 IF IT FAILS

### Option A: Test Local Server First
```bash
# Start local RTMP server:
docker run --rm -p 1935:1935 -p 8080:80 alfg/nginx-rtmp

# In app, use:
# URL: rtmp://YOUR_MAC_IP/live/test
# Key: test

# Check: http://localhost:8080/stat
```

### Option B: Baseline Test with FFmpeg
```bash
ffmpeg -re -f lavfi -i testsrc2=size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=440:sample_rate=48000 \
       -c:v libx264 -preset veryfast -b:v 3500k \
       -c:a aac -b:a 128k \
       -f flv "rtmps://a.rtmps.youtube.com/live2/YOUR_KEY"
```
**If FFmpeg works but app doesn't**: RTMP protocol issue (see AUDIT_REPORT.md #1)

---

## 📊 KNOWN ISSUES FROM AUDIT

### 🔴 Critical (May Block Streaming):
1. **RTMP Protocol Incomplete** - YouTube server not responding to connect command
   - **Impact**: "Connection Failed" error
   - **Workaround**: Use RTMPS (443) instead of RTMP (1935)
   - **Fix**: See `docs/AUDIT_REPORT.md` Phase 1

2. **Emulator Audio Not Supported** - AudioPlaybackCapture requires real device
   - **Impact**: No audio on emulator
   - **Workaround**: Test on real device only

### 🟡 Medium (Works but Suboptimal):
3. **No Retry Logic** - Single connection attempt
   - **Impact**: Network blips cause failure
   - **Workaround**: Manually restart stream

4. **No Self-Test Mode** - Must use MediaProjection
   - **Impact**: Harder to diagnose encoder issues
   - **Workaround**: Use FFmpeg for baseline testing

---

## 📚 FULL DOCUMENTATION

- **Detailed Testing Guide**: `README_RUN.md`
- **Complete Audit Report**: `docs/AUDIT_REPORT.md`
- **Project Overview**: `PROJECT_OVERVIEW.md`

---

## 🎯 SUCCESS CHECKLIST

Test on **real Android device** (Android 10+):

- [ ] APK installs without errors
- [ ] Permissions granted (RECORD_AUDIO, POST_NOTIFICATIONS)
- [ ] MediaProjection dialog appears and is approved
- [ ] Foreground notification shows "Screen capture is active"
- [ ] YouTube Live Control Room shows "Receiving data"
- [ ] Video: Device screen visible
- [ ] Audio: Microphone audio audible
- [ ] Stream stable for 60+ seconds
- [ ] No crashes or ANR errors

---

## 🚀 NEXT STEPS

### If All Tests Pass ✅
App is ready for alpha release! Report success with:
- Device model
- Android version
- YouTube stream quality rating

### If Tests Fail ❌
1. Check which test failed (local vs YouTube)
2. Collect logs: `adb logcat -d > error.log`
3. Review `docs/AUDIT_REPORT.md` for matching issue
4. Report with: device info + logs + test results

---

**Quick Contact**: Post in project issues with log file
**Estimated Test Time**: 3-5 minutes per scenario
**Last Updated**: October 26, 2025
