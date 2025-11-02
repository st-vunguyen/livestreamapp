#!/bin/zsh

# ScreenLive Stream Debug Monitor
# Monitors logs to verify if stream is actually connecting to YouTube

echo "🔍 ScreenLive Stream Debug Monitor"
echo "=================================="
echo ""
echo "📱 Waiting for device..."

export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"

# Check device
DEVICE=$(adb devices | grep -v "List" | grep "device" | awk '{print $1}' | head -1)

if [ -z "$DEVICE" ]; then
    echo "❌ No device found! Please:"
    echo "  1. Connect your phone via USB"
    echo "  2. Enable USB Debugging"
    echo "  3. Trust this computer"
    exit 1
fi

echo "✅ Device found: $DEVICE"
echo ""
echo "📋 Instructions:"
echo "  1. Open ScreenLive app on your phone"
echo "  2. Enter your RTMP URL and Stream Key"
echo "  3. Press 'Start Streaming'"
echo "  4. Watch logs below"
echo ""
echo "🔎 Monitoring logs... (Ctrl+C to stop)"
echo "=================================="
echo ""

# Clear logcat and start monitoring
adb -s $DEVICE logcat -c
adb -s $DEVICE logcat -v time -s \
    "PublishHandler:*" \
    "CaptureHandler:*" \
    "MainActivity:*" \
    "ScreenCaptureService:*" \
    | grep --line-buffered -E "(STREAM DEBUG|Starting publish|Stopping publish|MediaProjection|RTMP|Stream Key|Bitrate|VIDEO ENCODING|RTMP CONNECTION|✓|⚠️|❌)"
