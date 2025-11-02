import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../core/models/local_settings.dart';
import '../core/models/preset.dart';
import '../core/models/session_metrics.dart';
import '../services/capture_service.dart';
import '../services/publish_service.dart';
import 'health_monitor.dart';
import 'adaptation_policy.dart';

/// Session states matching spec Section 5.1
enum SessionState {
  idle,
  configuring,
  streaming,
  reconnecting,
  stopped,
}

/// Session controller managing the livestream lifecycle
/// Implements FL-001 (Start Stream), FL-002 (Stop), FL-003 (Reconnect)
class SessionController extends StateNotifier<SessionState> {
  final CaptureService _captureService;
  final PublishService _publishService;
  final HealthMonitor _healthMonitor;
  
  AdaptationPolicy? _adaptationPolicy;
  StreamSubscription? _metricsSubscription;
  int _reconnectAttempt = 0;
  static const int _maxReconnectAttempts = 3;

  SessionController({
    CaptureService? captureService,
    PublishService? publishService,
    HealthMonitor? healthMonitor,
  })  : _captureService = captureService ?? CaptureService(),
        _publishService = publishService ?? PublishService(),
        _healthMonitor = healthMonitor ?? HealthMonitor(),
        super(SessionState.idle);

  Stream<SessionMetrics> get metricsStream => _healthMonitor.metricsStream;
  SessionMetrics get currentMetrics => _healthMonitor.currentMetrics;

  /// Start streaming session (FL-001)
  Future<void> startSession({
    required LocalSettings settings,
  }) async {
    if (state != SessionState.idle) return;

    state = SessionState.configuring;

    try {
      final preset = Preset.fromType(settings.preset);

      // Step 1: Request MediaProjection permission and start capture
      final captureStarted = await _captureService.requestPermissionAndStart(
        width: preset.width,
        height: preset.height,
        fps: preset.fps,
      );

      if (!captureStarted) {
        throw Exception('MediaProjection permission denied');
      }

      // Step 2: Start audio capture (game or mic fallback)
      final audioSource = await _captureService.startAudioCapture(
        preferredSource: settings.audioSource.name,
      );
      // TODO: Notify user if fallback to mic occurred

      // Step 3: Initialize RTMPS connection and encoder
      final publishStarted = await _publishService.startPublish(
        rtmpsUrl: settings.rtmpsUrl,
        streamKey: settings.streamKey, // Never logged
        videoBitrateKbps: preset.videoBitrateKbps,
        audioBitrateKbps: preset.audioBitrateKbps,
        keyframeIntervalSec: preset.keyframeIntervalSec,
      );

      if (!publishStarted) {
        throw Exception('RTMPS connection failed');
      }

      // Step 4: Initialize adaptation policy
      _adaptationPolicy = AdaptationPolicy(
        initialBitrateKbps: preset.videoBitrateKbps,
        presetMaxBitrateKbps: preset.maxBitrateKbps,
      );

      // Step 5: Start monitoring metrics
      _healthMonitor.startMonitoring();
      _listenToMetrics(targetFps: preset.fps);

      state = SessionState.streaming;
      _reconnectAttempt = 0;
    } catch (e) {
      // Cleanup on failure
      await _cleanup();
      state = SessionState.idle;
      rethrow;
    }
  }

  /// Stop streaming session (FL-002)
  Future<void> stopSession() async {
    if (state == SessionState.idle || state == SessionState.stopped) return;

    state = SessionState.stopped;
    await _cleanup();
    state = SessionState.idle;
  }

  /// Handle reconnection with backoff (FL-003)
  Future<void> handleReconnect() async {
    if (state != SessionState.streaming && state != SessionState.reconnecting) return;

    state = SessionState.reconnecting;
    _reconnectAttempt++;
    _healthMonitor.incrementReconnectCount();

    if (_reconnectAttempt > _maxReconnectAttempts) {
      // Max retries exceeded
      await stopSession();
      return;
    }

    // Backoff delays: 1s → 2s → 5s
    final delaySeconds = _reconnectAttempt == 1 ? 1 : (_reconnectAttempt == 2 ? 2 : 5);
    await Future.delayed(Duration(seconds: delaySeconds));

    try {
      final success = await _publishService.reconnect(attemptNumber: _reconnectAttempt);
      
      if (success) {
        state = SessionState.streaming;
        _reconnectAttempt = 0;
      } else {
        // Retry
        await handleReconnect();
      }
    } catch (e) {
      // Retry on error
      await handleReconnect();
    }
  }

  void _listenToMetrics({required int targetFps}) {
    _metricsSubscription = _publishService.metricsStream.listen((data) {
      _healthMonitor.updateFromPlatform(data);

      // Apply adaptation policy (FL-004)
      if (_adaptationPolicy != null && state == SessionState.streaming) {
        final metrics = _healthMonitor.currentMetrics;
        final newBitrate = _adaptationPolicy!.evaluateAndAdjust(
          uploadQueueSec: metrics.uploadQueueSec,
          currentFps: metrics.fpsCurrent,
          targetFps: targetFps,
        );

        if (newBitrate != null) {
          _publishService.updateBitrate(newBitrate);
        }
      }

      // Detect disconnection and trigger reconnect
      final disconnected = data['disconnected'] as bool? ?? false;
      if (disconnected && state == SessionState.streaming) {
        handleReconnect();
      }
    });
  }

  Future<void> _cleanup() async {
    await _metricsSubscription?.cancel();
    _metricsSubscription = null;
    _healthMonitor.stopMonitoring();
    await _publishService.stopPublish();
    await _captureService.stopCapture();
    _adaptationPolicy = null;
    _reconnectAttempt = 0;
  }

  @override
  void dispose() {
    _cleanup();
    _healthMonitor.dispose();
    super.dispose();
  }
}

/// Providers
final sessionControllerProvider = StateNotifierProvider<SessionController, SessionState>((ref) {
  return SessionController();
});

final sessionMetricsProvider = StreamProvider<SessionMetrics>((ref) {
  final controller = ref.watch(sessionControllerProvider.notifier);
  return controller.metricsStream;
});
