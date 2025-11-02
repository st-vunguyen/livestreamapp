#!/bin/bash

# Quick test script for RTMP streaming
echo "🔍 Monitoring RTMP Streaming Logs..."
echo "================================================"
echo ""
echo "Actions to test:"
echo "1. Press 'Start Streaming' in app"
echo "2. Grant MediaProjection permission"
echo "3. Enter YouTube RTMP URL and Stream Key"
echo "4. Press 'Go Live'"
echo ""
echo "Watching for:"
echo "  ✓ MediaProjection created"
echo "  ✓ VideoEncoder started"
echo "  ✓ RTMP connection"
echo "  ✓ Video/Audio frames sent"
echo ""
echo "================================================"
echo ""

adb logcat -c
adb logcat | grep -E "(CaptureHandler|PublishHandler|VideoEncoder|AudioEncoder|RtmpClient|FlvMuxer|FATAL)" | \
    grep --line-buffered -v "OpenGLRenderer" | \
    while IFS= read -r line; do
        if [[ $line == *"✓"* ]]; then
            echo -e "\033[0;32m$line\033[0m"  # Green for success
        elif [[ $line == *"FATAL"* ]] || [[ $line == *"ERROR"* ]] || [[ $line == *"⚠️"* ]]; then
            echo -e "\033[0;31m$line\033[0m"  # Red for errors
        elif [[ $line == *"Sent"* ]] || [[ $line == *"frames"* ]]; then
            echo -e "\033[0;36m$line\033[0m"  # Cyan for frame stats
        else
            echo "$line"
        fi
    done
