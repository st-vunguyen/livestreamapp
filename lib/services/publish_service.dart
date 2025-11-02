import 'package:flutter/services.dart';

/// Flutter facade for RTMPS publishing (API-001)
/// Communicates with native Android FLV muxer and RTMPS socket
class PublishService {
  static const MethodChannel _channel = MethodChannel('com.screenlive.app/publish');
  static const EventChannel _metricsChannel = EventChannel('com.screenlive.app/metrics');

  /// Start RTMPS connection and encoding
  /// Never logs the streamKey parameter
  Future<bool> startPublish({
    required String rtmpsUrl,
    required String streamKey, // Never logged
    required int videoBitrateKbps,
    required int audioBitrateKbps,
    required double keyframeIntervalSec,
  }) async {
    try {
      final result = await _channel.invokeMethod<bool>('startPublish', {
        'rtmpsUrl': rtmpsUrl,
        'streamKey': streamKey, // Handled securely in native code
        'videoBitrateKbps': videoBitrateKbps,
        'audioBitrateKbps': audioBitrateKbps,
        'keyframeIntervalSec': keyframeIntervalSec,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      // Error codes from spec: TLS_HANDSHAKE_FAIL, AUTH_INVALID_KEY, INGEST_UNREACHABLE
      print('PublishService.startPublish error: ${e.code}');
      // Note: e.message may contain URL but never streamKey per native implementation
      rethrow;
    }
  }

  /// Stop RTMPS publishing and close connection
  Future<void> stopPublish() async {
    try {
      await _channel.invokeMethod('stopPublish');
    } on PlatformException catch (e) {
      print('PublishService.stopPublish error: ${e.code} - ${e.message}');
    }
  }

  /// Update encoder bitrate dynamically (for adaptation)
  Future<void> updateBitrate(int bitrateKbps) async {
    try {
      await _channel.invokeMethod('updateBitrate', {'bitrateKbps': bitrateKbps});
    } on PlatformException catch (e) {
      print('PublishService.updateBitrate error: ${e.code} - ${e.message}');
    }
  }

  /// Request reconnect with backoff
  Future<bool> reconnect({required int attemptNumber}) async {
    try {
      final result = await _channel.invokeMethod<bool>('reconnect', {
        'attemptNumber': attemptNumber,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      print('PublishService.reconnect error: ${e.code} - ${e.message}');
      return false;
    }
  }

  /// Stream of metrics from native encoder/uplink
  /// Data: {fps, bitrate, uploadQueueSec, temperatureStatus}
  Stream<Map<String, dynamic>> get metricsStream {
    return _metricsChannel.receiveBroadcastStream().map((event) {
      return Map<String, dynamic>.from(event as Map);
    });
  }
}
