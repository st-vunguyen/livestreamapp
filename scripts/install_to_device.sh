#!/bin/bash
#
# Auto-install APK to connected Android device
# Waits for device connection, installs APK, grants permissions, starts logcat
#

set -e

ADB="/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
APK="build/app/outputs/flutter-apk/app-debug.apk"
PACKAGE="com.screenlive.app"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📱 Auto-Install: ScreenLive Debug APK"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Check if APK exists
if [ ! -f "$APK" ]; then
    echo "❌ APK not found: $APK"
    echo "   Run: flutter build apk --debug"
    exit 1
fi

APK_SIZE=$(du -h "$APK" | cut -f1)
echo "📦 APK ready: $APK ($APK_SIZE)"
echo ""

# Wait for device
echo "⏳ Waiting for Android device..."
echo "   (Make sure USB debugging is enabled)"
echo ""

while true; do
    DEVICES=$($ADB devices | grep -v "List of devices" | grep "device$" | wc -l)
    if [ "$DEVICES" -gt 0 ]; then
        DEVICE_ID=$($ADB devices | grep "device$" | head -1 | awk '{print $1}')
        echo "✓ Device connected: $DEVICE_ID"
        break
    fi
    sleep 1
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📲 Installing APK..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
$ADB install -r "$APK"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🔐 Granting permissions..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
$ADB shell pm grant $PACKAGE android.permission.RECORD_AUDIO 2>/dev/null || echo "⚠️  RECORD_AUDIO permission may need manual grant"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ Installation Complete!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "📱 App installed on device: $DEVICE_ID"
echo "📦 Package: $PACKAGE"
echo ""
echo "Next steps:"
echo "  1. Open app on device"
echo "  2. Enter RTMPS URL: rtmps://a.rtmps.youtube.com:443/live2"
echo "  3. Enter your YouTube stream key"
echo "  4. Tap 'Start Streaming'"
echo "  5. Allow screen capture permission when prompted"
echo ""
echo "Monitor logs:"
echo "  $ADB logcat | grep \"\\[PTL\\]\""
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📊 Starting logcat (press Ctrl+C to stop)..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Clear old logs and start monitoring
$ADB logcat -c
$ADB logcat | grep --line-buffered "\[PTL\]"
