# Keep annotation classes for Flutter
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.**  { *; }
-keep class io.flutter.util.**  { *; }
-keep class io.flutter.view.**  { *; }
-keep class io.flutter.**  { *; }
-keep class io.flutter.plugins.**  { *; }
-dontwarn io.flutter.embedding.**

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Android MediaProjection related classes
-keep class android.media.projection.** { *; }
-keep class android.media.MediaCodec** { *; }

# Google Play Core (for split install - not used but required by Flutter)
-dontwarn com.google.android.play.core.**
-keep class com.google.android.play.core.** { *; }

# ScreenLive app classes
-keep class com.screenlive.app.** { *; }

# TODO: Add specific rules for RTMPS library once integrated
# TODO: Add rules for FLV muxer implementation
