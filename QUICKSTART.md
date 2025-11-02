# ScreenLive - Quick Start Guide

## 🚀 Get Started in 5 Minutes

### Prerequisites Check
```bash
# Verify Flutter installation
flutter --version

# Verify Android toolchain
flutter doctor
```

### 1. Build the APK
```bash
cd ScreenLive
chmod +x build.sh
./build.sh
```

This will:
- Clean previous builds
- Install dependencies
- Run tests
- Build debug and release APKs

**Output**: `build/app/outputs/flutter-apk/app-debug.apk`

---

### 2. Install on Device

```bash
# Connect your Android device via USB (enable USB debugging)
# Or start an Android emulator

# Install APK
adb install build/app/outputs/flutter-apk/app-debug.apk
```

---

### 3. Get RTMPS Credentials

#### For YouTube:
1. Go to [YouTube Studio](https://studio.youtube.com)
2. Click "Create" → "Go Live"
3. Choose "Stream" (not webcam)
4. Copy **Stream URL** (e.g., `rtmps://a.rtmp.youtube.com/live2`)
5. Copy **Stream Key** (keep this secret!)

#### For Facebook:
1. Go to [Facebook Live Producer](https://www.facebook.com/live/producer)
2. Click "Go Live"
3. Copy **Server URL** (e.g., `rtmps://live-api-s.facebook.com:443/rtmp/`)
4. Copy **Stream Key**

---

### 4. Start Streaming

1. Open ScreenLive app on your device
2. **Setup Screen**:
   - Paste RTMPS URL
   - Paste Stream Key
   - Select Preset (Balanced recommended for first test)
   - Choose Audio: Microphone
   - Tap "Start Streaming"
3. **Grant Permissions**:
   - Allow screen capture
   - Allow microphone access
4. **Live Control Screen**:
   - View HUD metrics (FPS, bitrate, etc.)
   - Tap HUD to collapse/expand
   - Drag HUD to reposition
5. **Stop Streaming**:
   - Tap "Stop" button
   - Confirm in dialog

---

## 🧪 Testing the App

### Verify Basic Flow
- [ ] App launches without crash
- [ ] Setup screen validates URL format (must start with `rtmps://`)
- [ ] Start button disabled until form is valid
- [ ] Live Control screen shows HUD
- [ ] Stop button shows confirmation dialog
- [ ] Back button shows confirmation dialog

### Check Simulated Metrics (Current MVP)
The app currently uses simulated metrics from the native layer:
- FPS: 60
- Bitrate: ~3.5 Mbps (based on selected preset)
- Upload Queue: 0.3s (healthy)
- Temperature: Normal

**Note**: These will become real metrics once the encoder pipeline is implemented.

---

## ⚠️ Current Limitations

This is an MVP with **scaffolded native code**. The following are NOT yet functional:

- ❌ **No actual streaming**: RTMPS socket not implemented
- ❌ **No encoder**: MediaCodec pipeline not wired up
- ❌ **No screen capture**: VirtualDisplay not created
- ✅ **UI works**: All screens, forms, navigation, HUD display
- ✅ **Logic works**: Adaptation policy, state management, metrics aggregation

### What Works Now:
- Complete UI/UX flow
- Form validation
- HUD display with simulated data
- State transitions (idle → streaming → stopped)
- Adaptation calculations
- Reconnect logic (simulated)

### What Needs Implementation:
See `IMPLEMENTATION_SUMMARY.md` → "Next Steps for Production" section.

---

## 🔍 Debugging

### Enable Flutter DevTools
```bash
# Run app in debug mode
flutter run

# In another terminal, open DevTools
flutter pub global activate devtools
flutter pub global run devtools
```

### View Android Logs
```bash
# Filter by app package
adb logcat | grep screenlive
```

### Check Permissions
```bash
# List granted permissions
adb shell dumpsys package com.screenlive.app | grep permission
```

---

## 📁 Project Structure Quick Reference

```
ScreenLive/
├── lib/
│   ├── main.dart                    # App entry
│   ├── features/
│   │   ├── setup/                   # UI-001
│   │   └── live/                    # UI-002
│   ├── logic/
│   │   ├── session_controller.dart  # State machine
│   │   ├── health_monitor.dart      # Metrics aggregation
│   │   └── adaptation_policy.dart   # Bitrate adjustment
│   └── services/
│       ├── capture_service.dart     # → Native capture
│       └── publish_service.dart     # → Native RTMPS
├── android/
│   └── app/src/main/kotlin/         # Native handlers
├── test/                            # Unit & widget tests
├── README.md                        # Full documentation
├── IMPLEMENTATION_SUMMARY.md        # Status report
└── build.sh                         # Build script
```

---

## 🆘 Troubleshooting

### Build Errors

**Problem**: `flutter.sdk not set in local.properties`
```bash
# Solution: Run flutter commands once to auto-configure
cd android
flutter config --android-sdk <path-to-android-sdk>
cd ..
flutter pub get
```

**Problem**: `SDK location not found`
```bash
# Solution: Create local.properties
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
```

### Runtime Errors

**Problem**: App crashes on start
- Check `flutter doctor` output
- Verify minSdk 26+ in `android/app/build.gradle`
- Clear build: `flutter clean && flutter pub get`

**Problem**: Permissions dialog doesn't appear
- Check `AndroidManifest.xml` has all required permissions
- Reinstall app: `flutter install`

---

## 🎯 Next Steps

1. ✅ **You are here**: Built and installed the MVP
2. ⏭️ **Implement native encoder** (see TODO markers in Kotlin files)
3. ⏭️ **Test with real device** on Wi-Fi and mobile networks
4. ⏭️ **Deploy to Play Store** (requires signing configuration)

---

## 📞 Need Help?

- **Spec Reference**: See `docs/spec.md` for quick spec lookup
- **Full Docs**: See `README.md` for comprehensive guide
- **Implementation Status**: See `IMPLEMENTATION_SUMMARY.md`
- **Code Comments**: All files have detailed inline documentation with spec IDs

---

**Happy Streaming! 🎥**
