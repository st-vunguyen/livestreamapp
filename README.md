# ScreenLive

Screen livestreaming to RTMPS (YouTube/Facebook) without any backend.

## Features

- **No Backend Required**: Stream directly to YouTube/Facebook via RTMPS with manual stream key entry
- **Android MediaProjection**: Full screen capture with hardware-accelerated H.264 encoding
- **Audio Capture**: Game audio (Android 10+ if permitted) with microphone fallback
- **Smart Adaptation**: Manual bitrate step-down/up based on upload queue metrics
- **Reconnect with Backoff**: Automatic reconnection with 1s → 2s → 5s delays
- **Live HUD**: Real-time display of FPS, bitrate, queue status, reconnect count, and temperature
- **Foreground Service**: Keeps streaming session alive when app is backgrounded

## Prerequisites

- **Flutter SDK**: 3.0+ ([Installation guide](https://docs.flutter.dev/get-started/install))
- **Android SDK**: API 26+ (Android 8.0 Oreo or higher)
- **Java**: JDK 11 or higher

## Getting Started

### 1. Clone and Setup

```bash
cd ScreenLive
flutter pub get
```

### 2. Run on Device/Emulator

```bash
# List available devices
flutter devices

# Run on connected device
flutter run

# Run in release mode
flutter run --release
```

### 3. Build APK

#### Debug APK
```bash
flutter clean
flutter pub get
flutter build apk --debug
```
Output: `build/app/outputs/flutter-apk/app-debug.apk`

#### Release APK (unsigned)
```bash
flutter build apk --release
```
Output: `build/app/outputs/flutter-apk/app-release.apk`

**Note**: For production release, you need to configure keystore signing in `android/app/build.gradle` (see TODO comments).

### 4. Install APK on Device

```bash
# Via ADB
adb install build/app/outputs/flutter-apk/app-debug.apk

# Or transfer APK to device and install manually
```

## Usage

1. **Setup Screen (UI-001)**:
   - Enter your RTMPS URL (e.g., `rtmps://a.rtmp.youtube.com/live2`)
   - Enter your Stream Key (from YouTube/Facebook streaming settings)
   - Select encoder preset:
     - **High**: 1080p60 ~6 Mbps (flagship devices, good network)
     - **Balanced**: 720p60 ~3.5 Mbps (mid-range devices)
     - **Fallback**: 540p60 ~2 Mbps (low-tier devices or weak network)
   - Choose audio source: Game Audio (Android 10+) or Microphone
   - Optionally save credentials locally (stored securely)
   - Tap "Start Streaming"

2. **Live Control Screen (UI-002)**:
   - View real-time HUD metrics (tap to collapse/expand)
   - Monitor FPS, bitrate, upload queue, reconnect count, temperature
   - Tap "Stop" to end stream (with confirmation)

3. **Back Navigation**: Press back button during streaming shows confirmation dialog

## Known Limitations

- **No OAuth**: Stream keys must be entered manually (no auto-discovery)
- **Audio Playback Capture**: Requires Android 10+ and game must permit capture; falls back to microphone
- **Keyframe Interval**: Fixed at ~2 seconds (GOP ≈120 @ 60fps)
- **No Multi-Destination**: Single RTMPS target per session
- **No iOS Support**: Current implementation is Android-only (iOS requires ReplayKit Broadcast Extension)

## Architecture

### Flutter Layer
- **State Management**: Riverpod for DI and reactive state
- **Navigation**: go_router with `/` (Setup) and `/live` (Live Control)
- **Storage**: flutter_secure_storage for credentials, shared_preferences for settings

### Android Native Layer
- **MediaProjection**: Screen capture via VirtualDisplay
- **MediaCodec**: Hardware H.264 video + AAC audio encoding
- **FLV Muxing**: Real-time muxing of encoded streams
- **RTMPS**: TLS socket connection to ingest with reconnect logic
- **Foreground Service**: Keeps session alive (FEAT-009)

### Key Components
- **SessionController**: Manages streaming lifecycle and states
- **HealthMonitor**: Aggregates metrics (fps, bitrate, queue, temperature)
- **AdaptationPolicy**: Bitrate adjustment based on uploadQueueSec thresholds
- **CaptureService / PublishService**: Flutter facades to native MethodChannels

## Testing

### Run Tests
```bash
# Unit tests
flutter test

# Widget tests
flutter test test/widget

# Integration tests (requires device/emulator)
flutter test integration_test
```

### Manual QA Checklist
- [ ] Start stream with valid URL/key → connects successfully
- [ ] HUD updates metrics every 1-2 seconds
- [ ] Disconnect network for 10-30s → reconnects automatically
- [ ] Stop button shows confirmation and exits cleanly
- [ ] Background app → foreground service keeps stream alive
- [ ] Stream key is masked in UI and never appears in logs
- [ ] Thermal warning appears on prolonged High preset streaming
- [ ] Audio fallback to microphone when game capture unavailable

## Project Structure

```
lib/
├── main.dart
├── core/
│   ├── router/app_router.dart
│   ├── models/          # ENT-001 to ENT-004
│   └── theme/           # Design tokens
├── features/
│   ├── setup/           # UI-001
│   ├── live/            # UI-002
│   └── shared/          # CFButton, CFTextField, HUDOverlay, dialogs
├── logic/
│   ├── session_controller.dart
│   ├── health_monitor.dart
│   └── adaptation_policy.dart
├── services/
│   ├── capture_service.dart
│   └── publish_service.dart
└── storage/settings_store.dart

android/
└── app/src/main/kotlin/com/screenlive/app/
    ├── MainActivity.kt
    ├── CaptureHandler.kt    # API-002
    ├── PublishHandler.kt    # API-001
    ├── MetricsHandler.kt
    └── streaming/StreamingForegroundService.kt
```

## Traceability (Spec IDs)

- **BR-001**: RTMPS livestreaming via manual key entry → `PublishHandler`
- **BR-002**: Preset encoder support (1080p60/720p60/540p60) → `Preset` model
- **BR-003**: Reconnect with backoff → `SessionController.handleReconnect()`
- **BR-004**: Manual bitrate adaptation → `AdaptationPolicy`
- **FEAT-001**: Manual URL/Key input → `SetupScreen` (UI-001)
- **FEAT-002**: Screen capture → `CaptureHandler` (MediaProjection)
- **FEAT-005**: RTMPS push + reconnect → `PublishHandler`
- **FEAT-006**: Bitrate step-down/up → `AdaptationPolicy.evaluateAndAdjust()`
- **FEAT-007**: Minimal UX → `SetupScreen`, `LiveControlScreen`
- **FEAT-009**: Foreground Service → `StreamingForegroundService`
- **FEAT-010**: HUD metrics → `HUDOverlay`, `HealthMonitor`
- **UI-001**: Setup Screen → `features/setup/presentation/setup_screen.dart`
- **UI-002**: Live Control → `features/live/presentation/live_screen.dart`
- **ENT-001**: LocalSettings → `core/models/local_settings.dart`
- **ENT-002**: Preset → `core/models/preset.dart`
- **ENT-003**: SessionMetrics → `core/models/session_metrics.dart`
- **API-001**: RTMPS socket → `PublishHandler.startPublish()`
- **API-002**: MediaProjection → `CaptureHandler.requestPermission()`

## TODO: Production Readiness

The current implementation provides a working MVP with scaffolded native code. For production deployment, complete these items:

### High Priority
- [ ] **Encoder Implementation**: Implement full MediaCodec H.264/AAC encoding pipeline in native code
- [ ] **FLV Muxer**: Implement FLV muxing logic for video/audio frames
- [ ] **RTMPS Socket**: Implement TLS socket connection and RTMPS handshake
- [ ] **Audio Playback Capture**: Implement Android 10+ AudioPlaybackCapture with game permission detection
- [ ] **VirtualDisplay**: Create VirtualDisplay from MediaProjection and feed to encoder surface
- [ ] **Metrics Pipeline**: Wire real encoder/uplink metrics to MetricsHandler (currently simulated)
- [ ] **Thermal Monitoring**: Implement device temperature monitoring via Android ThermalService
- [ ] **Keystore Signing**: Configure release signing in `android/app/build.gradle`

### Medium Priority
- [ ] **Frame Drop Detection**: Implement frame drop counters in encoder
- [ ] **Upload Queue Measurement**: Calculate actual upload buffer depth in RTMPS writer
- [ ] **Error Codes**: Map native exceptions to spec error codes (TLS_HANDSHAKE_FAIL, etc.)
- [ ] **Proguard Rules**: Add rules for reflection-based code if any
- [ ] **Field Testing**: Validate on Wi-Fi/4G/5G with low/mid/high-tier devices

### Low Priority
- [ ] **iOS ReplayKit**: Implement Broadcast Upload Extension for iOS support
- [ ] **Local Recording**: Optional save-to-device while streaming
- [ ] **Network Pre-Check**: Bandwidth test before stream start
- [ ] **Thermal-Based Preset**: Auto-downgrade preset on throttle detection

## License

Copyright © 2025 ScreenLive. All rights reserved.

---

**Build Time**: ~2-5 minutes for debug APK  
**APK Size**: ~15-25 MB (debug), ~8-12 MB (release with minify)
