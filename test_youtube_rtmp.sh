#!/bin/bash

# Test RTMP connection to YouTube with FFmpeg
# This verifies the server and credentials are working

echo "🧪 Testing YouTube RTMP Server Connection"
echo "=========================================="
echo ""
echo "Enter your YouTube Stream Key:"
read -s STREAM_KEY
echo ""

RTMP_URL="rtmp://a.rtmp.youtube.com/live2/$STREAM_KEY"

echo "📡 Testing with 10-second test pattern..."
echo "If this works, the problem is in the app's RTMP implementation"
echo ""

ffmpeg -re -f lavfi -i testsrc=size=1920x1080:rate=30 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 \
  -c:v libx264 -preset veryfast -profile:v high -level 4.2 \
  -pix_fmt yuv420p -b:v 6M -maxrate 6M -bufsize 12M \
  -g 60 -keyint_min 60 -sc_threshold 0 \
  -c:a aac -b:a 128k -ar 48000 \
  -f flv -flvflags no_duration_filesize \
  "$RTMP_URL" \
  -t 10

echo ""
echo "✅ If YouTube Studio shows 'Live', the server works!"
echo "❌ If it failed, check your stream key or YouTube settings"
