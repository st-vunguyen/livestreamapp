import 'dart:async';
import '../core/models/session_metrics.dart';

/// Health monitor that aggregates metrics from platform layer
/// Updates SessionMetrics (ENT-003) for UI display
class HealthMonitor {
  final StreamController<SessionMetrics> _metricsController = StreamController.broadcast();
  Stream<SessionMetrics> get metricsStream => _metricsController.stream;

  SessionMetrics _currentMetrics = const SessionMetrics();
  Timer? _durationTimer;
  final List<double> _bitrateBuffer = [];
  static const int _bitrateWindowSize = 10; // 10-second average

  SessionMetrics get currentMetrics => _currentMetrics;

  void startMonitoring() {
    _currentMetrics = const SessionMetrics();
    _bitrateBuffer.clear();
    
    // Start duration timer
    _durationTimer?.cancel();
    _durationTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      _updateMetrics(duration: Duration(seconds: timer.tick));
    });
  }

  void stopMonitoring() {
    _durationTimer?.cancel();
    _durationTimer = null;
    _bitrateBuffer.clear();
  }

  /// Update metrics from platform channel data
  void updateFromPlatform(Map<String, dynamic> data) {
    final fps = (data['fps'] as num?)?.toDouble() ?? _currentMetrics.fpsCurrent;
    final bitrateKbps = (data['bitrate'] as num?)?.toDouble() ?? 0.0;
    final queueSec = (data['uploadQueueSec'] as num?)?.toDouble() ?? _currentMetrics.uploadQueueSec;
    final tempStatus = _parseTemperatureStatus(data['temperatureStatus']);

    // Update bitrate buffer for 10s average
    _bitrateBuffer.add(bitrateKbps);
    if (_bitrateBuffer.length > _bitrateWindowSize) {
      _bitrateBuffer.removeAt(0);
    }
    final bitrateAvg = _bitrateBuffer.isEmpty 
        ? 0.0 
        : _bitrateBuffer.reduce((a, b) => a + b) / _bitrateBuffer.length;

    _updateMetrics(
      fpsCurrent: fps,
      bitrateAvg10s: bitrateAvg,
      uploadQueueSec: queueSec,
      temperatureStatus: tempStatus,
    );
  }

  void incrementReconnectCount() {
    _updateMetrics(reconnectCount: _currentMetrics.reconnectCount + 1);
  }

  void resetReconnectCount() {
    _updateMetrics(reconnectCount: 0);
  }

  void _updateMetrics({
    double? fpsCurrent,
    double? bitrateAvg10s,
    double? uploadQueueSec,
    int? reconnectCount,
    TemperatureStatus? temperatureStatus,
    Duration? duration,
  }) {
    _currentMetrics = _currentMetrics.copyWith(
      fpsCurrent: fpsCurrent,
      bitrateAvg10s: bitrateAvg10s,
      uploadQueueSec: uploadQueueSec,
      reconnectCount: reconnectCount,
      temperatureStatus: temperatureStatus,
      duration: duration,
    );
    _metricsController.add(_currentMetrics);
  }

  TemperatureStatus _parseTemperatureStatus(dynamic value) {
    if (value == null) return TemperatureStatus.normal;
    final str = value.toString().toLowerCase();
    if (str.contains('throttle')) return TemperatureStatus.throttle;
    if (str.contains('warning') || str.contains('warm')) return TemperatureStatus.warning;
    return TemperatureStatus.normal;
  }

  void dispose() {
    _durationTimer?.cancel();
    _metricsController.close();
  }
}
