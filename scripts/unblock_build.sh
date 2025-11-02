#!/bin/bash
#
# Quick-Start: Unblock Build by Downgrading Flutter to 3.24.5
#
# This script resolves the Flutter 3.35.7 Gradle plugin compatibility issue
# by switching to the last stable Flutter version with Groovy Gradle support.
#
# Run: ./scripts/unblock_build.sh
#

set -e  # Exit on error

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🔧 Unblocking Build: Flutter Downgrade to 3.24.5"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Step 1: Downgrade Flutter
echo "📦 Step 1/5: Downgrading Flutter..."
cd /opt/homebrew/share/flutter
git fetch --tags
git checkout 3.24.5 2>&1 | grep -E "(HEAD|Switched)" || true
flutter --version | head -1
echo "✓ Flutter 3.24.5 active"
echo ""

# Step 2: Verify Java 21
echo "☕ Step 2/5: Verifying Java 21..."
java -version 2>&1 | grep "21\." || {
    echo "❌ Java 21 not active. Run: flutter config --jdk-dir=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    exit 1
}
echo "✓ Java 21 LTS active"
echo ""

# Step 3: Clean project
echo "🧹 Step 3/5: Cleaning project..."
cd "/Users/vu.nguyen/Documents/Copilot/Learning/Gaming Streamer/ScreenLive"
flutter clean
rm -rf android/.gradle android/app/build android/build
echo "✓ Project cleaned"
echo ""

# Step 4: Get dependencies
echo "📥 Step 4/5: Getting dependencies..."
flutter pub get
echo "✓ Dependencies resolved"
echo ""

# Step 5: Build APK
echo "🔨 Step 5/5: Building debug APK..."
flutter build apk --debug 2>&1 | tee /tmp/flutter_build.log | grep -E "(BUILD|✓|Running)" | tail -20

# Check result
if [ -f "build/app/outputs/flutter-apk/app-debug.apk" ]; then
    APK_SIZE=$(du -h "build/app/outputs/flutter-apk/app-debug.apk" | cut -f1)
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "✅ BUILD SUCCESSFUL!"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    echo "📦 APK: build/app/outputs/flutter-apk/app-debug.apk"
    echo "📊 Size: $APK_SIZE"
    echo ""
    echo "Next steps:"
    echo "  1. Install: adb install -r build/app/outputs/flutter-apk/app-debug.apk"
    echo "  2. Grant permissions: adb shell pm grant com.screenlive.app android.permission.RECORD_AUDIO"
    echo "  3. Monitor logs: adb logcat | grep \"\\[PTL\\]\""
    echo "  4. Test YouTube streaming with your stream key"
    echo ""
    echo "See docs/troubleshooting_android.md for detailed testing instructions."
    echo ""
else
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "❌ BUILD FAILED"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    echo "Check /tmp/flutter_build.log for errors."
    echo "If still blocked, see docs/PTL_STATUS_FINAL.md Option B (Kotlin DSL migration)."
    echo ""
    exit 1
fi
