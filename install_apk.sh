#!/bin/zsh

# Quick Install Script for ScreenLive APK
# Installs latest debug APK to connected Android device

echo "📱 ScreenLive Quick Install"
echo "=========================="

export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"

# Check for device
DEVICE=$(adb devices | grep -v "List" | grep "device" | awk '{print $1}' | head -1)

if [ -z "$DEVICE" ]; then
    echo "❌ No device found!"
    echo ""
    echo "Please:"
    echo "  1. Connect your phone via USB"
    echo "  2. Enable USB Debugging (Settings > Developer Options)"
    echo "  3. Trust this computer when prompted"
    echo ""
    echo "Then run this script again."
    exit 1
fi

echo "✅ Device found: $DEVICE"
echo ""

APK_PATH="/Users/vu.nguyen/Documents/Copilot/Learning/Gaming Streamer/ScreenLive/build/app/outputs/flutter-apk/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK not found!"
    echo "Please build first:"
    echo "  cd /Users/vu.nguyen/Documents/Copilot/Learning/Gaming\ Streamer/ScreenLive"
    echo "  flutter build apk --debug"
    exit 1
fi

echo "📦 APK: app-debug.apk"
echo "📊 Size: $(du -h "$APK_PATH" | awk '{print $1}')"
echo ""
echo "🔄 Installing..."

adb -s $DEVICE install -r "$APK_PATH"

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Installation successful!"
    echo ""
    echo "🎯 Next steps:"
    echo "  1. Open ScreenLive app on your phone"
    echo "  2. Grant all permissions"
    echo "  3. Enter RTMP URL and Stream Key from YouTube Studio"
    echo "  4. Press 'Start Streaming'"
    echo ""
    echo "📋 To monitor logs, run:"
    echo "  ./debug_stream.sh"
else
    echo ""
    echo "❌ Installation failed!"
fi
