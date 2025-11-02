import 'package:flutter/services.dart';

/// RootEncoder Service - Clean Flutter wrapper
/// 
/// MVP MethodChannel API:
/// - start(rtmpsUrl, streamKey) -> throws on error
/// - stop() -> success always
/// - stopStreamCompleted (callback from native) -> notify UI to navigate
class RootEncoderService {
  static final RootEncoderService instance = RootEncoderService._();
  
  RootEncoderService._() {
    // Set up method call handler for callbacks from native
    _channel.setMethodCallHandler(_handleMethodCall);
  }
  
  static const _channel = MethodChannel('com.screenlive.app/rootEncoder');
  
  bool _isStreaming = false;
  
  /// Callback when stream is stopped from overlay
  void Function()? onStreamStoppedFromOverlay;
  
  bool get isStreaming => _isStreaming;
  
  Future<dynamic> _handleMethodCall(MethodCall call) async {
    print('[FLUTTER] Method call from native: ${call.method}');
    
    switch (call.method) {
      case 'stopStreamCompleted':
        print('[FLUTTER] Stream stopped from overlay - updating state');
        _isStreaming = false;
        onStreamStoppedFromOverlay?.call();
        break;
      default:
        print('[FLUTTER] Unknown method: ${call.method}');
    }
  }
  
  /// Start streaming with RTMPS URL and stream key
  /// 
  /// Throws:
  /// - PlatformException on validation or connection errors
  Future<void> start({
    required String rtmpsUrl,
    required String streamKey,
  }) async {
    print('[FLUTTER] RootEncoderService.start() called');
    print('[FLUTTER] URL: $rtmpsUrl');
    print('[FLUTTER] Key: ***${streamKey.substring(streamKey.length - 4)}');
    
    // [PTL FIX] Allow restart if already streaming (don't throw error)
    if (_isStreaming) {
      print('[FLUTTER] Already streaming, stopping first...');
      await stop();
      await Future.delayed(Duration(milliseconds: 2000)); // Wait for full cleanup
    }
    
    try {
      print('[FLUTTER] Calling native method channel...');
      final result = await _channel.invokeMethod('start', {
        'rtmpsUrl': rtmpsUrl,
        'streamKey': streamKey,
      });
      print('[FLUTTER] Native method returned: $result');
      
      if (result is Map && result['ok'] == true) {
        _isStreaming = true;
      } else {
        throw Exception('Start failed: $result');
      }
      
    } on PlatformException catch (e) {
      throw Exception('${e.code}: ${e.message}');
    }
  }
  
  /// Stop streaming
  /// 
  /// Always succeeds (cleanup is best-effort)
  Future<void> stop() async {
    if (!_isStreaming) {
      return;
    }
    
    try {
      await _channel.invokeMethod('stop');
      _isStreaming = false;
    } on PlatformException catch (e) {
      // Log but don't throw - cleanup should always succeed
      print('Stop warning: ${e.code} ${e.message}');
      _isStreaming = false;
    }
  }
}
