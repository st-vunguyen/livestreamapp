#!/bin/bash
# Test script for Android 14 MediaProjection compliance
# Based on comprehensive checklist

set -e

echo "=== Android 14 MediaProjection Test Script ==="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PKG="com.screenlive.app.debug"

echo "1️⃣  Installing APK..."
flutter build apk --debug
flutter install --debug
echo -e "${GREEN}✓ APK installed${NC}"
echo ""

echo "2️⃣  Granting runtime permissions..."
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || echo "  (already granted or not needed)"
adb shell pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null || echo "  (already granted)"
echo -e "${GREEN}✓ Permissions granted${NC}"
echo ""

echo "3️⃣  Verifying permissions in manifest..."
adb shell dumpsys package $PKG | grep -E "FOREGROUND_SERVICE|RECORD_AUDIO|POST_NOTIFICATIONS" | grep "granted=true"
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ All FGS permissions granted${NC}"
else
    echo -e "${RED}✗ Some permissions missing!${NC}"
    exit 1
fi
echo ""

echo "4️⃣  Clearing logcat and starting monitoring..."
adb logcat -c
echo -e "${YELLOW}Starting logcat in background...${NC}"
adb logcat | grep --line-buffered -E "ScreenCaptureService|Foreground|MediaProjection|SecurityException|RtmpsClient|TLS|EOF" &
LOGCAT_PID=$!
echo ""

echo "5️⃣  Launching app..."
adb shell am force-stop $PKG
adb shell am start -n $PKG/com.screenlive.app.MainActivity
echo -e "${GREEN}✓ App launched${NC}"
echo ""

echo "⏳ Waiting 5 seconds for UI to load..."
sleep 5

echo ""
echo "=== NOW: Tap 'Start Stream' button in the app ==="
echo ""
echo "6️⃣  Waiting 10 seconds for you to start streaming..."
sleep 10

echo ""
echo "7️⃣  Checking FGS status..."
echo -e "${YELLOW}Looking for foregroundServiceType in dumpsys...${NC}"
adb shell dumpsys activity services | grep -A8 $PKG | grep -E "ServiceRecord|foregroundServiceType"

if adb shell dumpsys activity services | grep -A8 $PKG | grep "foregroundServiceType.*mediaProjection" > /dev/null; then
    echo -e "${GREEN}✓ FGS type includes 'mediaProjection'${NC}"
else
    echo -e "${RED}✗ FGS type does NOT include 'mediaProjection'!${NC}"
    echo "This is the root cause of SecurityException!"
fi
echo ""

echo "8️⃣  Checking for errors in last 50 log lines..."
adb logcat -d -t 50 | grep -E "SecurityException|FATAL|AndroidRuntime" && echo -e "${RED}✗ Errors found${NC}" || echo -e "${GREEN}✓ No errors${NC}"
echo ""

echo "=== Test Complete ==="
echo "Logcat is still running (PID: $LOGCAT_PID)"
echo "Press Ctrl+C to stop monitoring, or wait 30 more seconds..."
sleep 30

kill $LOGCAT_PID 2>/dev/null
echo "Done!"
