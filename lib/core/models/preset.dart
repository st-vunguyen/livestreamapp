import 'local_settings.dart';
export 'local_settings.dart' show PresetType;

// ENT-002: Preset entity defining encoder configurations
class Preset {
  final String name;
  final String resolution; // e.g., "1920x1080"
  final int fps;
  final int videoBitrateKbps;
  final int maxBitrateKbps; // Upper bound for adaptation
  final double keyframeIntervalSec;
  final int audioBitrateKbps;

  const Preset({
    required this.name,
    required this.resolution,
    required this.fps,
    required this.videoBitrateKbps,
    required this.maxBitrateKbps,
    required this.keyframeIntervalSec,
    required this.audioBitrateKbps,
  });

  int get width => int.parse(resolution.split('x')[0]);
  int get height => int.parse(resolution.split('x')[1]);

  static const Preset high = Preset(
    name: 'High',
    resolution: '1920x1080',
    fps: 60,
    videoBitrateKbps: 6000,
    maxBitrateKbps: 8000,
    keyframeIntervalSec: 2.0,
    audioBitrateKbps: 160,
  );

  static const Preset balanced = Preset(
    name: 'Balanced',
    resolution: '1280x720',
    fps: 60,
    videoBitrateKbps: 3500,
    maxBitrateKbps: 4000,
    keyframeIntervalSec: 2.0,
    audioBitrateKbps: 128,
  );

  static const Preset fallback = Preset(
    name: 'Fallback',
    resolution: '960x540',
    fps: 60,
    videoBitrateKbps: 2000,
    maxBitrateKbps: 2500,
    keyframeIntervalSec: 2.0,
    audioBitrateKbps: 128,
  );

  static Preset fromType(PresetType type) {
    switch (type) {
      case PresetType.high:
        return high;
      case PresetType.balanced:
        return balanced;
      case PresetType.fallback:
        return fallback;
    }
  }

  String get displayInfo => '${resolution.split('x')[1]}p$fps ~${videoBitrateKbps ~/ 1000}Mbps (max ${maxBitrateKbps ~/ 1000})';
}
