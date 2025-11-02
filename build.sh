#!/bin/bash

# ScreenLive Build and Verification Script
# This script builds the APK and runs basic verification

set -e  # Exit on error

echo "================================"
echo "ScreenLive APK Build Script"
echo "================================"
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Step 1: Clean previous build
echo "${YELLOW}[1/6]${NC} Cleaning previous build..."
flutter clean
echo "${GREEN}✓${NC} Clean complete"
echo ""

# Step 2: Get dependencies
echo "${YELLOW}[2/6]${NC} Getting dependencies..."
flutter pub get
echo "${GREEN}✓${NC} Dependencies installed"
echo ""

# Step 3: Run tests
echo "${YELLOW}[3/6]${NC} Running tests..."
flutter test
echo "${GREEN}✓${NC} All tests passed"
echo ""

# Step 4: Analyze code
echo "${YELLOW}[4/6]${NC} Analyzing code..."
flutter analyze
echo "${GREEN}✓${NC} Analysis complete"
echo ""

# Step 5: Build debug APK
echo "${YELLOW}[5/6]${NC} Building debug APK..."
flutter build apk --debug
DEBUG_APK="build/app/outputs/flutter-apk/app-debug.apk"

if [ -f "$DEBUG_APK" ]; then
    echo "${GREEN}✓${NC} Debug APK built successfully!"
    echo "   Location: $DEBUG_APK"
    ls -lh "$DEBUG_APK"
else
    echo "${RED}✗${NC} Debug APK not found!"
    exit 1
fi
echo ""

# Step 6: Build release APK (unsigned)
echo "${YELLOW}[6/6]${NC} Building release APK..."
flutter build apk --release
RELEASE_APK="build/app/outputs/flutter-apk/app-release.apk"

if [ -f "$RELEASE_APK" ]; then
    echo "${GREEN}✓${NC} Release APK built successfully!"
    echo "   Location: $RELEASE_APK"
    ls -lh "$RELEASE_APK"
else
    echo "${RED}✗${NC} Release APK not found!"
    exit 1
fi
echo ""

# Summary
echo "================================"
echo "${GREEN}Build Complete!${NC}"
echo "================================"
echo ""
echo "Output APKs:"
echo "  Debug:   $DEBUG_APK"
echo "  Release: $RELEASE_APK"
echo ""
echo "Next steps:"
echo "  1. Install on device: adb install $DEBUG_APK"
echo "  2. Test with real RTMPS credentials"
echo "  3. Verify HUD metrics and reconnect behavior"
echo "  4. For production: Configure signing in android/app/build.gradle"
echo ""
