import 'package:flutter/services.dart';
import '../core/models/permission_state.dart';

/// Flutter facade for Android MediaProjection screen capture (API-002)
/// Communicates with native code via MethodChannel
class CaptureService {
  static const MethodChannel _channel = MethodChannel('com.screenlive.app/capture');

  /// Request MediaProjection permission and start capture
  /// Returns true if permission granted and capture started
  Future<bool> requestPermissionAndStart({
    required int width,
    required int height,
    required int fps,
  }) async {
    try {
      final result = await _channel.invokeMethod<bool>('requestPermission', {
        'width': width,
        'height': height,
        'fps': fps,
      });
      return result ?? false;
    } on MissingPluginException catch (e) {
      print('CaptureService: Native implementation not available - ${e.message}');
      throw Exception('Native screen capture not implemented yet. This is a prototype with scaffolded native code.');
    } on PlatformException catch (e) {
      // TODO: Log error details (never log sensitive data)
      print('CaptureService.requestPermission error: ${e.code} - ${e.message}');
      return false;
    }
  }

  /// Start audio capture (game audio if available, else microphone)
  /// Returns audio source type: 'game' or 'microphone'
  Future<String> startAudioCapture({required String preferredSource}) async {
    try {
      final result = await _channel.invokeMethod<String>('startAudio', {
        'preferredSource': preferredSource,
      });
      return result ?? 'microphone';
    } on PlatformException catch (e) {
      print('CaptureService.startAudio error: ${e.code} - ${e.message}');
      return 'microphone'; // Fallback
    }
  }

  /// Stop capture and release resources
  Future<void> stopCapture() async {
    try {
      await _channel.invokeMethod('stopCapture');
    } on PlatformException catch (e) {
      print('CaptureService.stopCapture error: ${e.code} - ${e.message}');
    }
  }

  /// Check current permission state
  Future<PermissionState> checkPermissions() async {
    try {
      final result = await _channel.invokeMethod<Map>('checkPermissions');
      if (result == null) return const PermissionState();
      
      return PermissionState(
        mediaProjection: result['mediaProjection'] as bool? ?? false,
        audioPlaybackCapture: result['audioPlaybackCapture'] as bool? ?? false,
        microphone: result['microphone'] as bool? ?? false,
      );
    } on PlatformException catch (e) {
      print('CaptureService.checkPermissions error: ${e.code} - ${e.message}');
      return const PermissionState();
    }
  }
}
