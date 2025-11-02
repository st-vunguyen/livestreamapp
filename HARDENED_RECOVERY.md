# Hardened Self-Recovery System

## Overview

Ultra-robust crash recovery system with **activity restart** capability to re-request MediaProjection permission. This is the "cứng" (hardened) version that survives:
- Native crashes (NPE, OOM, etc.)
- OEM aggressive kills (Meizu/Xiaomi/Oppo)
- Task removal from Recent Apps
- Device reboot
- Network reconnect failures

## Key Innovation: Activity Restart vs Service Restart

### Problem with Service-Only Restart
When service crashes/dies, it loses MediaProjection permission → cannot restart streaming automatically.

### Solution: Activity Restart
Restart the **MainActivity** which can:
1. Show UI to user
2. Re-request MediaProjection permission
3. Auto-start stream once permission granted

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ Layer 1: Global Crash Handler (App.kt)                       │
│ - Checks: wasStreaming=true + manualStop=false               │
│ - Action: RestartHelper.scheduleRestartActivity(1.5s)        │
└──────────────────────────────────────────────────────────────┘
                             ↓
┌──────────────────────────────────────────────────────────────┐
│ Layer 2: Task Removal Handler (ScreenCaptureService)         │
│ - Checks: wasStreaming=true + manualStop=false               │
│ - Action: RestartHelper.scheduleRestartService(1.0s)         │
└──────────────────────────────────────────────────────────────┘
                             ↓
┌──────────────────────────────────────────────────────────────┐
│ Layer 3: Reconnect Failure (RootEncoderService)              │
│ - After 5 reconnect attempts fail                            │
│ - Action: cleanup() + scheduleRestartActivity(1.5s)          │
└──────────────────────────────────────────────────────────────┘
                             ↓
┌──────────────────────────────────────────────────────────────┐
│ Layer 4: Reboot Recovery (BootCompletedReceiver)             │
│ - Triggered: ACTION_BOOT_COMPLETED                           │
│ - Action: RestartHelper.scheduleRestartActivity(3.0s)        │
└──────────────────────────────────────────────────────────────┘
```

## New Files Created

### 1. Prefs.kt - Persistent State Helper

**Location:** `android/app/src/main/kotlin/com/screenlive/app/Prefs.kt`

**Purpose:** Manage two critical flags that survive process death:

```kotlin
class Prefs(ctx: Context) {
    // True if user clicked STOP button
    var manualStop: Boolean
    
    // True if app was actively streaming before crash/kill
    var wasStreaming: Boolean
}
```

**Why Two Flags?**
- `manualStop`: Prevents restart when user explicitly stops
- `wasStreaming`: Enables restart only if app was actually streaming

**State Matrix:**

| manualStop | wasStreaming | Action on Crash/Kill |
|------------|--------------|----------------------|
| false | false | No restart (not streaming) |
| false | true | **Restart activity** (was streaming) |
| true | false | No restart (user stopped) |
| true | true | No restart (user stopped) |

### 2. RestartHelper.kt - AlarmManager Scheduler

**Location:** `android/app/src/main/kotlin/com/screenlive/app/RestartHelper.kt`

**Purpose:** Schedule app/service restart using AlarmManager (survives process death)

**Methods:**
```kotlin
object RestartHelper {
    // Restart MainActivity to re-request MediaProjection
    fun scheduleRestartActivity(ctx: Context, delayMs: Long)
    
    // Restart ScreenCaptureService (keeps existing permission)
    fun scheduleRestartService(ctx: Context, delayMs: Long)
}
```

**Why AlarmManager?**
- Works even when process is dead
- Works in Doze mode (`setExactAndAllowWhileIdle`)
- Survives app force-stop (until next boot)

### 3. BootCompletedReceiver.kt - Reboot Recovery

**Location:** `android/app/src/main/kotlin/com/screenlive/app/BootCompletedReceiver.kt`

**Purpose:** Restart activity after device reboot if stream was active

**Logic:**
```kotlin
if (wasStreaming && !manualStop) {
    scheduleRestartActivity(3s)  // Give device time to fully boot
}
```

## Modified Files

### App.kt - Enhanced Crash Handler

**Before:**
```kotlin
if (!manualStop) {
    scheduleRestartService(1.5s)  // Problem: loses MediaProjection
}
```

**After:**
```kotlin
if (!manualStop && wasStreaming) {
    scheduleRestartActivity(1.5s)  // Solution: restart UI to re-request permission
}
```

### RootEncoderService.kt - Flag Management

**Added Methods:**
```kotlin
private fun markStartFlags() {
    prefs.manualStop = false
    prefs.wasStreaming = true
}

private fun markStopFlags(manual: Boolean) {
    prefs.manualStop = manual
    if (manual) {
        prefs.wasStreaming = false  // User stopped → don't restart
    }
}
```

**Updated Flow:**

1. **start()** → `markStartFlags()` → enables auto-restart
2. **stop()** → `markStopFlags(manual=true)` → disables auto-restart
3. **cleanup()** → checks `prefs.manualStop`:
   - If manual: `wasStreaming = false`
   - If crash: `wasStreaming` kept true
4. **handleRtmpsDisconnect()** → on reconnect failure:
   ```kotlin
   if (!wasManual && prefs.wasStreaming) {
       scheduleRestartActivity(1.5s)
   }
   ```

### ScreenCaptureService.kt - Persistent Check

**Updated onTaskRemoved():**
```kotlin
val prefs = Prefs(this)
if (!prefs.manualStop && prefs.wasStreaming) {
    RestartHelper.scheduleRestartService(1s)
}
```

### AndroidManifest.xml - Permissions & Receiver

**Added:**
```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<receiver
    android:name=".BootCompletedReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

## Recovery Scenarios

### Scenario 1: App Crashes During Stream

**Trigger:** `NullPointerException`, `OutOfMemoryError`, etc.

**Recovery Flow:**
1. App.kt catches exception
2. Reads prefs: `wasStreaming=true`, `manualStop=false`
3. Schedules activity restart in 1.5s
4. Kills process
5. AlarmManager triggers MainActivity
6. **User sees app → can re-grant MediaProjection → stream resumes**

**Expected Logs:**
```
E/SLive:App: [CRASH] Uncaught exception in thread main
I/SLive:App: [RECOVERY] Stream was active - restarting activity in 1.5s
...
(After 1.5s):
I/MainActivity: onCreate() called
I/MainActivity: Checking battery optimization...
```

### Scenario 2: Reconnect Fails After 5 Attempts

**Trigger:** Network unavailable for extended period (WiFi off, no LTE)

**Recovery Flow:**
1. RootEncoderService tries 5 reconnects (0.5s → 10s backoff)
2. All fail → `handleRtmpsDisconnect()` calls `cleanup("reconnect failed")`
3. Checks: `wasManual=false`, `wasStreaming=true`
4. Schedules activity restart in 1.5s
5. MainActivity opens → user can start new stream

**Expected Logs:**
```
E/RootEncoder: [PTL] RTMPS lost: Socket closed
I/RootEncoder: [PTL] Reconnect attempt 1/5 (backoff=500ms)
...
E/RootEncoder: [PTL] ❌ Reconnect failed after 5 attempts
I/RootEncoder: [RECOVERY] Scheduling activity restart in 1.5s
```

### Scenario 3: User Swipes App from Recent Apps

**Trigger:** User swipes ScreenLive card from Recent Apps list

**Recovery Flow:**
1. `ScreenCaptureService.onTaskRemoved()` triggered
2. Reads prefs: `wasStreaming=true`, `manualStop=false`
3. Schedules service restart in 1.0s (keeps permission)
4. Service recreates → stream continues

**Expected Logs:**
```
I/ScreenCaptureService: [FIX] Task removed from Recent Apps
I/ScreenCaptureService: [RECOVERY] Auto-restarting service
...
I/ScreenCaptureService: ✅ FGS active with correct type
```

### Scenario 4: Device Reboots

**Trigger:** User reboots device (battery drain, OS update, etc.)

**Recovery Flow:**
1. Device boots → `ACTION_BOOT_COMPLETED` broadcast
2. BootCompletedReceiver checks prefs: `wasStreaming=true`, `manualStop=false`
3. Schedules activity restart in 3.0s (allow boot to complete)
4. MainActivity opens → user can re-grant MediaProjection

**Expected Logs:**
```
I/BootCompletedReceiver: [RECOVERY] Device rebooted
I/BootCompletedReceiver: [RECOVERY] Stream was active before reboot
I/RestartHelper: [RECOVERY] Activity restart scheduled in 3000ms
...
I/MainActivity: onCreate() called
```

### Scenario 5: User Clicks STOP Button

**Trigger:** User clicks STOP in overlay or app UI

**Recovery Flow:**
1. `stopFromOverlay()` or `stop()` called
2. `markStopFlags(manual=true)` → `manualStop=true`, `wasStreaming=false`
3. Cleans up stream
4. **NO auto-restart** (all handlers check flags first)

**Expected Logs:**
```
I/RootEncoder: [FIX] User clicked STOP - setting manualStop=true
I/RootEncoder: [RECOVERY] Manual stop: wasStreaming=false
I/RootEncoder: Cleanup triggered: manual stop
...
(If app crashes AFTER stop):
I/SLive:App: [CRASH] Uncaught exception
I/SLive:App: [RECOVERY] No restart needed (manualStop=true, wasStreaming=false)
```

## State Lifecycle

```
┌─────────────┐
│  App Start  │  manualStop=false, wasStreaming=false
└──────┬──────┘
       │
       ↓
┌──────────────────┐
│  User Clicks     │  markStartFlags()
│  START STREAM    │  → manualStop=false, wasStreaming=true
└──────┬───────────┘
       │
       ├──────────────────┬────────────────┬──────────────────┐
       │                  │                │                  │
       ↓                  ↓                ↓                  ↓
  ┌──────────┐     ┌─────────────┐   ┌───────────┐    ┌──────────┐
  │  Crash   │     │  Reconnect  │   │   Swipe   │    │   User   │
  │          │     │    Failed   │   │  Recents  │    │   STOP   │
  └────┬─────┘     └──────┬──────┘   └─────┬─────┘    └────┬─────┘
       │                  │                │                │
       ↓                  ↓                ↓                ↓
  ┌───────────────────────────────────────────────────────────┐
  │  Check wasStreaming & manualStop                          │
  └───────────────────────────────────────────────────────────┘
       │                  │                │                │
       ↓                  ↓                ↓                ↓
  ┌──────────┐     ┌─────────────┐   ┌───────────┐    ┌──────────┐
  │ Restart  │     │   Restart   │   │  Restart  │    │    No    │
  │ Activity │     │   Activity  │   │  Service  │    │  Restart │
  │  (1.5s)  │     │   (1.5s)    │   │  (1.0s)   │    │          │
  └──────────┘     └─────────────┘   └───────────┘    └──────────┘
       │                  │                │                │
       └──────────────────┴────────────────┘                │
                      │                                     │
                      ↓                                     ↓
              ┌──────────────┐                      ┌──────────────┐
              │  wasStreaming│                      │  wasStreaming│
              │  kept true   │                      │  set false   │
              └──────────────┘                      └──────────────┘
```

## Testing Guide

### Test 1: Crash Recovery with Activity Restart

**Steps:**
```bash
# Terminal 1: Monitor logs
adb logcat -c && adb logcat | grep -E "SLive:App|RECOVERY|RestartHelper|MainActivity"

# Terminal 2: Start stream, then force crash
adb shell am crash com.screenlive.app
```

**Expected:**
1. Logs show: `[CRASH] Uncaught exception`
2. Logs show: `[RECOVERY] Stream was active - restarting activity in 1.5s`
3. After 1.5s: MainActivity opens
4. User can re-grant MediaProjection and resume streaming

### Test 2: Reconnect Failure with Activity Restart

**Steps:**
1. Start stream
2. Enable airplane mode
3. Wait 30 seconds (5 reconnect attempts fail)
4. Check logs

**Expected:**
```
E/RootEncoder: [PTL] ❌ Reconnect failed after 5 attempts
I/RootEncoder: [RECOVERY] Scheduling activity restart in 1.5s
I/RestartHelper: [RECOVERY] Activity restart scheduled in 1500ms
...
I/MainActivity: onCreate() called
```

### Test 3: Task Removal with Service Restart

**Steps:**
1. Start stream
2. Press HOME
3. Open Recent Apps
4. Swipe ScreenLive away
5. Check if stream continues

**Expected:**
```
I/ScreenCaptureService: [FIX] Task removed from Recent Apps
I/ScreenCaptureService: [RECOVERY] Auto-restarting service
I/RestartHelper: [RECOVERY] Service restart scheduled in 1000ms
...
I/ScreenCaptureService: ✅ FGS active with correct type
```
Stream should continue without interruption.

### Test 4: Reboot Recovery

**Steps:**
1. Start stream
2. Verify `wasStreaming=true` in logs
3. Reboot device: `adb reboot`
4. After reboot, monitor logs

**Expected:**
```
I/BootCompletedReceiver: [RECOVERY] Device rebooted - checking if stream was active
I/BootCompletedReceiver: [RECOVERY] Stream was active before reboot - restarting activity in 3s
I/RestartHelper: [RECOVERY] Activity restart scheduled in 3000ms
...
I/MainActivity: onCreate() called
```

### Test 5: Manual Stop (No Restart)

**Steps:**
1. Start stream
2. Click STOP in overlay
3. Force crash: `adb shell am crash com.screenlive.app`

**Expected:**
```
I/RootEncoder: [FIX] User clicked STOP - setting manualStop=true
I/RootEncoder: [RECOVERY] Manual stop: wasStreaming=false
...
(After crash):
I/SLive:App: [CRASH] Uncaught exception
I/SLive:App: [RECOVERY] No restart needed (manualStop=true, wasStreaming=false)
```
NO activity restart should occur.

## Monitoring Commands

```bash
# Watch all recovery events
adb logcat -c && adb logcat | grep -E "RECOVERY|RestartHelper|wasStreaming|manualStop"

# Check persistent state
adb shell "run-as com.screenlive.app cat /data/data/com.screenlive.app/shared_prefs/slive.xml"

# Force crash
adb shell am crash com.screenlive.app

# Force stop (simulates aggressive OEM kill)
adb shell am force-stop com.screenlive.app

# Reboot
adb reboot
```

## Success Criteria

✅ **Crash Recovery:**
- App crashes → activity restarts in 1.5s
- User re-grants MediaProjection → streaming resumes

✅ **Reconnect Failure:**
- 5 reconnect attempts fail → activity restarts
- User can manually restart stream

✅ **Task Removal:**
- Swipe from Recents → service restarts in 1.0s
- Stream continues without interruption

✅ **Reboot Recovery:**
- Device reboots → activity restarts in 3.0s (if was streaming)
- User re-grants permission → resume streaming

✅ **No False Restarts:**
- User clicks STOP → no restart on crash/kill
- App never starts when not streaming before

## Key Differences from Basic Recovery

| Feature | Basic Recovery | Hardened Recovery |
|---------|----------------|-------------------|
| **Restart Target** | Service only | Activity (with UI) |
| **Permission Loss** | Cannot recover | User re-grants |
| **State Tracking** | `manualStop` only | `manualStop` + `wasStreaming` |
| **Reboot Handling** | None | BootCompletedReceiver |
| **Reconnect Failure** | Just cleanup | Activity restart |

## Performance Impact

- **Memory:** +~80KB (Prefs, RestartHelper, BootReceiver)
- **CPU:** Negligible (only during crash/restart)
- **Battery:** Minimal (AlarmManager wakes device briefly)
- **Disk I/O:** 2 SharedPreferences writes per stream start/stop

## Limitations

1. **MediaProjection Re-Request:**
   - User must manually grant permission after activity restart
   - Cannot auto-resume stream without user interaction
   - Android security limitation (by design)

2. **Aggressive Force-Stop:**
   - If user force-stops app via Settings → all alarms cancelled
   - Restart fails until user manually opens app
   - AlarmManager limitation

3. **Doze Mode:**
   - Device in deep sleep may delay restart by ~5 minutes
   - Using `setExactAndAllowWhileIdle` mitigates this
   - Still subject to Android power management

## See Also

- [CRASH_RECOVERY.md](./CRASH_RECOVERY.md) - Basic crash recovery (service-only)
- [MANUAL_STOP_FIX.md](./MANUAL_STOP_FIX.md) - manualStop flag implementation
- [SYSTEM_ARCHITECTURE.md](./SYSTEM_ARCHITECTURE.md) - Overall architecture
