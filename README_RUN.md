# ScreenLive - Quick Start & Testing Guide

**Version**: 0.1.0  
**Date**: October 26, 2025  
**Target**: Android 10+ (API 29+) real devices

---

## 📦 Installation

### Method 1: Direct APK Install (Recommended for Testing)
```bash
# Navigate to project
cd "/path/to/ScreenLive"

# Build debug APK
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
flutter build apk --debug

# Install on connected device
adb install -r build/app/outputs/flutter-apk/app-debug.apk
```

### Method 2: Using Install Script
```bash
./install_apk.sh
```

### Grant Permissions (Required)
```bash
# Android 11+: Grant permissions via ADB
adb shell pm grant com.screenlive.app.debug android.permission.RECORD_AUDIO

# Android 13+: Also grant notification permission
adb shell pm grant com.screenlive.app.debug android.permission.POST_NOTIFICATIONS

# Check granted permissions
adb shell dumpsys package com.screenlive.app.debug | grep permission
```

---

## 🧪 Testing Scenarios

### ✅ Test 1: FFmpeg Sanity Check (Baseline - YouTube Server Validation)

**Purpose**: Verify YouTube RTMP server is accessible and accepting streams

```bash
# Generate test stream with FFmpeg
ffmpeg -re \
  -f lavfi -i testsrc2=size=1280x720:rate=30 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 \
  -c:v libx264 -preset veryfast -b:v 3500k -maxrate 3500k -bufsize 7000k \
  -pix_fmt yuv420p -g 60 -c:a aac -b:a 128k -ar 48000 \
  -f flv "rtmp://a.rtmp.youtube.com/live2/YOUR_STREAM_KEY"
```

**Expected Result**:
- FFmpeg shows: `Stream mapping:`, `frame=`, increasing frame numbers
- YouTube Live Control Room: "Receiving data" within 5-10 seconds
- Video preview: Color bars with moving timestamp
- Audio: 440Hz sine wave tone

**If This Fails**: 
- Check stream key is correct (YouTube Studio → Go Live → Stream Key)
- Verify internet connection
- Try RTMPS: `rtmps://a.rtmps.youtube.com/live2/YOUR_KEY`

---

### ✅ Test 2: Local RTMP Server (Network Isolation Test)

**Purpose**: Test app RTMP client without YouTube dependency

#### Setup Local Server:
```bash
# Option A: Docker (Recommended)
docker run --rm -p 1935:1935 -p 8080:80 --name rtmp-server alfg/nginx-rtmp

# Option B: Using docker-compose
cd ScreenLive
docker-compose up -d

# Verify server running
curl http://localhost:8080/stat
```

#### Test from App:
1. Open ScreenLive app on device/emulator
2. Enter RTMP URL: `rtmp://10.0.2.2/live/test`  
   *(Note: `10.0.2.2` = host machine from Android emulator)*  
   *(For real device on same WiFi: use your Mac's IP, e.g., `rtmp://192.168.1.100/live/test`)*
3. Stream Key: `test` (or leave empty)
4. Start streaming

#### Verify Stream:
```bash
# Check server stats
open http://localhost:8080/stat

# Should show:
# <live>
#   <stream>
#     <name>test</name>
#     <nclients>1</nclients>
#     <publishing/>
#     <active/>
#   </stream>
# </live>

# Play stream with VLC
vlc rtmp://localhost/live/test

# Or with FFplay
ffplay rtmp://localhost/live/test
```

**Expected Result**:
- Server `/stat` page shows active publisher
- VLC/FFplay displays your device screen
- Audio from device microphone

**If This Fails**:
- Emulator: Ensure `10.0.2.2` is used (not `localhost`)
- Real Device: Ensure Mac firewall allows port 1935
- Check logs: `adb logcat | grep RtmpClient`

---

### ✅ Test 3: Screen Capture on Real Device (Full Pipeline Test)

**Purpose**: Validate end-to-end streaming with MediaProjection

#### Prerequisites:
- ✅ Real Android device (Android 10+)
- ✅ USB debugging enabled
- ✅ Connected to same WiFi as Mac (for local server test)
- ✅ Permissions granted (see Installation section)

#### Steps:
1. **Start Local RTMP Server** (see Test 2)

2. **Connect Device**:
   ```bash
   adb devices
   # Should show your device: <serial>  device
   ```

3. **Start Logging**:
   ```bash
   adb logcat -c  # Clear old logs
   adb logcat | grep -E "(Projection|VideoEncoder|AudioEncoder|RTMP|PublishHandler)" > stream.log
   ```

4. **In ScreenLive App**:
   - Tap "Settings" → Grant all permissions
   - Enter RTMP URL:
     - **Local**: `rtmp://192.168.1.XXX/live/mystream` (your Mac's IP)
     - **YouTube**: `rtmp://a.rtmp.youtube.com/live2` (or `rtmps://`)
   - Stream Key: 
     - **Local**: `mystream`
     - **YouTube**: Your actual stream key
   - Tap "Start Streaming"
   - **Grant MediaProjection permission** (popup dialog)
   - Tap "Go Live"

5. **Verify Streaming**:
   - Check notification: "Screen capture is active"
   - **Local**: Open `http://localhost:8080/stat` → Should show publisher
   - **YouTube**: Check Live Control Room → "Receiving data"

6. **Test Duration**: Stream for at least 60 seconds

7. **Stop**: Tap "Stop Streaming" in app

#### Check Logs:
```bash
# View captured logs
cat stream.log

# Look for success indicators:
grep "✓" stream.log

# Expected log sequence:
# MediaProjection created successfully
# ✓ Video encoder started successfully
# ✓ AudioRecord started
# ✓ AAC encoder started
# ✓ RTMP handshake completed
# ✓ Sent connect command
# ✓ Received _result response
# ✓ Connected to RTMP
# ✓ Sent AVC sequence header
# ✓ Sent AAC audio config
# ✓ RTMP connected and publishing
```

---

### ✅ Test 4: RTMPS (TLS/Port 443) - Corporate Network Test

**Purpose**: Test encrypted RTMP when port 1935 is blocked

#### When to Use:
- Corporate/university networks
- VPN connections
- Firewall blocking port 1935

#### Steps:
1. In app, use RTMPS URL:
   ```
   rtmps://a.rtmps.youtube.com/live2
   ```
   (Note the **s** after rtmp)

2. Stream key: Your YouTube key

3. Start streaming

4. Check logs for SSL handshake:
   ```bash
   adb logcat | grep -i ssl
   # Should see: "✓ RTMPS SSL handshake completed"
   ```

**Expected**: Connection succeeds even when RTMP (1935) fails

---

## 🐛 Troubleshooting

### Common Issues Table

| **Symptom** | **Cause** | **Solution** |
|------------|----------|------------|
| "Connection Failed" on RTMP | Port 1935 blocked | Try RTMPS (port 443) |
| "Permission Denied" | Runtime permissions not granted | Run `adb shell pm grant ...` commands |
| "MediaProjection stopped" | Android 14+ service type missing | Update to latest APK |
| Black screen in stream | VirtualDisplay not created | Check logs for encoder errors |
| No audio | Microphone permission missing | Grant RECORD_AUDIO |
| App crashes on start | FOREGROUND_SERVICE permission | Reinstall APK with updated manifest |
| "Handshake failed" | Network timeout | Check internet, try local server |
| "Connect response failed" | RTMP protocol issue | Test with FFmpeg first (Test 1) |

### Get Device IP Address:
```bash
# On Mac (for real device testing):
ifconfig | grep "inet " | grep -v 127.0.0.1

# Example output:
# inet 192.168.1.100 netmask 0xffffff00 broadcast 192.168.1.255
# Use: 192.168.1.100 in app
```

### Check Emulator Network:
```bash
# Emulator uses special IP:
# - Host machine: 10.0.2.2
# - Emulator itself: 10.0.2.15
# - DNS server: 10.0.2.3

# Test connectivity from emulator:
adb shell ping -c 3 10.0.2.2
```

### View Full Logs:
```bash
# All app logs:
adb logcat | grep com.screenlive.app

# RTMP specific:
adb logcat | grep RtmpClient

# Encoder errors:
adb logcat | grep -E "(VideoEncoder|AudioEncoder)"

# System crashes:
adb logcat | grep "FATAL"
```

### Reset Stream:
```bash
# Force stop app:
adb shell am force-stop com.screenlive.app.debug

# Clear app data:
adb shell pm clear com.screenlive.app.debug

# Reinstall:
adb install -r build/app/outputs/flutter-apk/app-debug.apk
```

---

## 📊 Performance Benchmarks

### Expected Metrics (1920x1080@60fps):
- **Video Bitrate**: 6-8 Mbps
- **Audio Bitrate**: 128-160 kbps
- **Keyframe Interval**: 2 seconds (120 frames)
- **CPU Usage**: 15-25% (high-end device)
- **Network Upload**: ~6.5 Mbps sustained

### Check Stream Health:
```bash
# YouTube Live Control Room:
# - Health: "Good" or "Excellent"
# - Resolution: 1920x1080
# - Framerate: 60 fps
# - Bitrate: ~6000-8000 kbps

# Local RTMP Server Stats:
curl http://localhost:8080/stat | grep -E "(bw_in|bytes_in)"
```

---

## 🚀 Quick Commands Reference

### Build & Install:
```bash
# Clean build
flutter clean && flutter build apk --debug

# Install
adb install -r build/app/outputs/flutter-apk/app-debug.apk

# Grant all permissions at once
adb shell pm grant com.screenlive.app.debug android.permission.RECORD_AUDIO && \
adb shell pm grant com.screenlive.app.debug android.permission.POST_NOTIFICATIONS
```

### Start Local RTMP Server:
```bash
docker run --rm -p 1935:1935 -p 8080:80 alfg/nginx-rtmp
```

### Monitor Logs:
```bash
adb logcat -c && adb logcat | grep -E "(RTMP|Encoder|Projection)" --color=always
```

### Test YouTube Stream:
```bash
# Replace YOUR_KEY with actual stream key
ffmpeg -re -f lavfi -i testsrc2=size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=440:sample_rate=48000 \
       -c:v libx264 -preset veryfast -b:v 3500k \
       -c:a aac -b:a 128k \
       -f flv "rtmp://a.rtmp.youtube.com/live2/YOUR_KEY"
```

---

## 🔐 Security Notes

### ⚠️ NEVER Commit Stream Keys!
```bash
# Check for accidentally committed keys:
git log --all --full-history --source --pickaxe-all -S"YOUR_KEY"

# If found, use git-filter-repo to remove:
pip install git-filter-repo
git-filter-repo --replace-text <(echo "YOUR_STREAM_KEY==>***REMOVED***")
```

### Safe Testing:
- Use local RTMP server for development
- Create throwaway YouTube streams for testing
- Rotate stream keys after public demos
- Never log full URLs containing keys

---

## 📝 Reporting Issues

When reporting bugs, include:

1. **Device Info**:
   ```bash
   adb shell getprop ro.build.version.release  # Android version
   adb shell getprop ro.product.model          # Device model
   ```

2. **Logs**:
   ```bash
   adb logcat -d > full_log.txt
   ```

3. **Steps to Reproduce**

4. **Expected vs Actual Behavior**

5. **Test Results**: Which of the 4 tests passed/failed?

---

## 🎯 Success Criteria Checklist

- [ ] FFmpeg → YouTube: Stream received
- [ ] App → Local RTMP: Server shows publisher
- [ ] App → YouTube (RTMP): Live Control Room shows "Receiving data"
- [ ] App → YouTube (RTMPS): Successful when RTMP fails
- [ ] Real Device: Screen captured for 60+ seconds
- [ ] Audio: Microphone audio present in stream
- [ ] Notification: Persistent "Screen capture is active" shown
- [ ] No crashes during 5-minute stream
- [ ] CPU < 30%, no thermal throttling warnings

---

## 📚 Additional Resources

- **RTMP Spec**: https://rtmp.veriskope.com/docs/spec/
- **YouTube Live Streaming**: https://support.google.com/youtube/answer/2853702
- **MediaProjection API**: https://developer.android.com/guide/topics/large-screens/media-projection
- **MediaCodec Guide**: https://developer.android.com/reference/android/media/MediaCodec

---

## 🔄 Next Steps After Successful Testing

1. **If all tests pass**: App is ready for alpha testing
2. **If local server works but YouTube fails**: Check AUDIT_REPORT.md for RTMP protocol fixes
3. **If emulator works but device fails**: Check device-specific encoder settings
4. **If audio missing**: Verify AudioPlaybackCapture support or mic fallback

---

**Last Updated**: October 26, 2025  
**Maintainer**: ScreenLive Development Team
