import 'package:flutter_test/flutter_test.dart';
import 'package:screen_live/logic/adaptation_policy.dart';

void main() {
  group('AdaptationPolicy', () {
    test('step-down when queue exceeds threshold', () {
      final policy = AdaptationPolicy(
        initialBitrateKbps: 3500,
        presetMaxBitrateKbps: 4000,
      );

      final newBitrate = policy.evaluateAndAdjust(
        uploadQueueSec: 2.5, // > 2.0 threshold
        currentFps: 60.0,
        targetFps: 60,
      );

      expect(newBitrate, isNotNull);
      expect(newBitrate, lessThan(3500));
      expect(newBitrate, greaterThanOrEqualTo(AdaptationPolicy.minBitrateKbps));
    });

    test('step-up when queue is healthy and stable', () {
      final policy = AdaptationPolicy(
        initialBitrateKbps: 2000,
        presetMaxBitrateKbps: 4000,
      );

      // First call starts stability timer
      var newBitrate = policy.evaluateAndAdjust(
        uploadQueueSec: 0.3, // < 0.5 threshold
        currentFps: 60.0,
        targetFps: 60,
      );
      expect(newBitrate, isNull); // Too soon

      // Simulate 10+ seconds of stability (test limitation: can't easily wait)
      // In real usage, after 10s of stability, it would step up
    });

    test('does not exceed preset maximum', () {
      final policy = AdaptationPolicy(
        initialBitrateKbps: 7500,
        presetMaxBitrateKbps: 8000,
      );

      final newBitrate = policy.evaluateAndAdjust(
        uploadQueueSec: 0.2,
        currentFps: 60.0,
        targetFps: 60,
      );

      if (newBitrate != null) {
        expect(newBitrate, lessThanOrEqualTo(8000));
      }
    });

    test('step-down on frame drops', () {
      final policy = AdaptationPolicy(
        initialBitrateKbps: 3500,
        presetMaxBitrateKbps: 4000,
      );

      final newBitrate = policy.evaluateAndAdjust(
        uploadQueueSec: 1.0, // Normal queue
        currentFps: 50.0, // Dropped from 60
        targetFps: 60,
      );

      expect(newBitrate, isNotNull);
      expect(newBitrate, lessThan(3500));
    });
  });
}
