/// Adaptation policy for manual bitrate step-down/up (FEAT-006, BR-004)
/// Based on uploadQueueSec thresholds from spec Section 6.4
class AdaptationPolicy {
  // Thresholds from spec
  static const double queueThresholdHigh = 2.0; // seconds
  static const double queueThresholdLow = 0.5; // seconds
  
  // Step percentages from spec
  static const double stepDownPercent = 0.25; // 20-30% → use 25%
  static const double stepUpPercent = 0.125; // 10-15% → use 12.5%
  
  // Minimum bitrate to prevent excessive reduction
  static const int minBitrateKbps = 500; // TODO: Calibrate based on field testing
  
  // Stability window before allowing step-up
  static const Duration stabilityDuration = Duration(seconds: 10);

  final int presetMaxBitrateKbps;
  int _currentBitrateKbps;
  DateTime? _lastStableTime;

  AdaptationPolicy({
    required int initialBitrateKbps,
    required this.presetMaxBitrateKbps,
  }) : _currentBitrateKbps = initialBitrateKbps;

  int get currentBitrate => _currentBitrateKbps;

  /// Evaluate metrics and return adjusted bitrate if needed
  int? evaluateAndAdjust({
    required double uploadQueueSec,
    required double currentFps,
    required int targetFps,
  }) {
    // Step-down: queue congested (>2s) or frame drops detected
    if (uploadQueueSec > queueThresholdHigh || (currentFps < targetFps * 0.9)) {
      return _stepDown();
    }

    // Step-up: queue healthy (<0.5s) and stable for 10s
    if (uploadQueueSec < queueThresholdLow && currentFps >= targetFps * 0.95) {
      final now = DateTime.now();
      _lastStableTime ??= now;
      
      if (now.difference(_lastStableTime!) >= stabilityDuration) {
        _lastStableTime = null;
        return _stepUp();
      }
    } else {
      // Reset stability timer if conditions not met
      _lastStableTime = null;
    }

    return null; // No adjustment needed
  }

  int _stepDown() {
    final newBitrate = (_currentBitrateKbps * (1.0 - stepDownPercent)).round();
    _currentBitrateKbps = newBitrate.clamp(minBitrateKbps, presetMaxBitrateKbps);
    _lastStableTime = null; // Reset stability timer on step-down
    return _currentBitrateKbps;
  }

  int _stepUp() {
    final newBitrate = (_currentBitrateKbps * (1.0 + stepUpPercent)).round();
    _currentBitrateKbps = newBitrate.clamp(minBitrateKbps, presetMaxBitrateKbps);
    return _currentBitrateKbps;
  }

  void reset(int bitrateKbps) {
    _currentBitrateKbps = bitrateKbps;
    _lastStableTime = null;
  }
}
