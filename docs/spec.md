# Mobile (Flutter) — Product & UI Design Spec

This file contains the authoritative specification for the ScreenLive mobile application.

For the complete specification, refer to:
`/Users/vu.nguyen/Documents/Copilot/Learning/Gaming Streamer/Mobile_Flutter_Design.md`

## Quick Reference

### Scope
- **Platform**: Android only (iOS out of scope for MVP)
- **Transport**: FLV (H.264 + AAC) over RTMPS (TLS)
- **No Backend**: Direct connection to YouTube/Facebook ingest
- **Manual Configuration**: User enters RTMPS URL + Stream Key

### Key Features (from spec)
- **UI-001 Setup**: RTMPS configuration, preset selection, audio source
- **UI-002 Live Control**: HUD with metrics, Stop button with confirmation
- **FEAT-001**: Manual RTMPS URL + Stream Key input
- **FEAT-002**: Android MediaProjection screen capture
- **FEAT-003**: Audio Playback Capture (Android 10+) or microphone fallback
- **FEAT-004**: Encoder presets (High/Balanced/Fallback)
- **FEAT-005**: RTMPS push with reconnect backoff (1s → 2s → 5s)
- **FEAT-006**: Manual bitrate adaptation based on uploadQueueSec
- **FEAT-009**: Foreground Service to keep session alive
- **FEAT-010**: HUD metrics (fps, bitrate, queue, reconnect, temperature)

### Data Models (ENT)
- **ENT-001**: LocalSettings (rtmpsUrl, streamKey, preset, audioSource)
- **ENT-002**: Preset (resolution, fps, bitrate, keyframe interval)
- **ENT-003**: SessionMetrics (fps, bitrate, queue, reconnect count, temperature)
- **ENT-004**: PermissionState (mediaProjection, audioPlaybackCapture, microphone)

### API Contracts
- **API-001**: RTMPS socket (FLV over TLS to ingest)
- **API-002**: Android MediaProjection (screen capture)
- **API-003**: Android Audio Playback Capture (game audio)

### Business Rules (BR)
- **BR-001**: RTMPS livestreaming via manual key entry
- **BR-002**: Support 1080p60/720p60/540p60 presets
- **BR-003**: Reconnect with backoff, no immediate disconnect
- **BR-004**: Reduce bitrate before fps to maintain stability
- **BR-005**: Game audio (Android 10+ if permitted), fallback microphone
- **BR-007**: Minimal UX (Setup + Live Control)

### Design Tokens
- **Colors**: Primary (light: #1976D2, dark: #42A5F5), Error, Warning, Success
- **Spacing**: 4dp base unit (8dp, 12dp, 16dp, 24dp, 32dp)
- **Radii**: Small (4dp), Medium (8dp), Large (16dp)
- **Icons**: Small (16dp), Medium (24dp), Large (48dp)
- **Min Tap Target**: 48dp (Android)

### Adaptation Thresholds (from spec)
- **Queue > 2.0s**: Step-down bitrate by 20-30%
- **Queue < 0.5s**: Step-up bitrate by 10-15% (after 10s stability)
- **Min Bitrate**: 500 kbps (TODO: calibrate)

### Reconnect Backoff
- **Attempt 1**: 1 second delay
- **Attempt 2**: 2 seconds delay
- **Attempt 3+**: 5 seconds delay
- **Max Attempts**: 3

### Presets (from spec)
- **High**: 1080p60, ~6 Mbps (max 8), keyframe ~2s, AAC 160 kbps
- **Balanced**: 720p60, ~3.5-4 Mbps, keyframe ~2s, AAC 128 kbps
- **Fallback**: 540p60, ~2-2.5 Mbps, keyframe ~2s, AAC 128 kbps

### Navigation (go_router)
- `/` → Setup Screen (UI-001)
- `/live` → Live Control Screen (UI-002)
- **Back Navigation**: From /live shows confirmation dialog

### Security
- **Stream Key**: Never logged, masked in UI
- **Storage**: flutter_secure_storage for credentials
- **Validation**: rtmpsUrl must start with "rtmps://"

---

**Note**: This is a reference copy. The full specification is maintained in the repository root as `Mobile_Flutter_Design.md`.
