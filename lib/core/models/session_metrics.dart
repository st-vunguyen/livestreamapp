// ENT-003: SessionMetrics entity for live stream health monitoring
class SessionMetrics {
  final double fpsCurrent;
  final double bitrateAvg10s; // in kbps
  final double uploadQueueSec;
  final int reconnectCount;
  final TemperatureStatus temperatureStatus;
  final Duration duration;

  const SessionMetrics({
    this.fpsCurrent = 0.0,
    this.bitrateAvg10s = 0.0,
    this.uploadQueueSec = 0.0,
    this.reconnectCount = 0,
    this.temperatureStatus = TemperatureStatus.normal,
    this.duration = Duration.zero,
  });

  SessionMetrics copyWith({
    double? fpsCurrent,
    double? bitrateAvg10s,
    double? uploadQueueSec,
    int? reconnectCount,
    TemperatureStatus? temperatureStatus,
    Duration? duration,
  }) {
    return SessionMetrics(
      fpsCurrent: fpsCurrent ?? this.fpsCurrent,
      bitrateAvg10s: bitrateAvg10s ?? this.bitrateAvg10s,
      uploadQueueSec: uploadQueueSec ?? this.uploadQueueSec,
      reconnectCount: reconnectCount ?? this.reconnectCount,
      temperatureStatus: temperatureStatus ?? this.temperatureStatus,
      duration: duration ?? this.duration,
    );
  }

  QueueStatus get queueStatus {
    if (uploadQueueSec < 0.5) return QueueStatus.healthy;
    if (uploadQueueSec < 2.0) return QueueStatus.stable;
    return QueueStatus.congested;
  }

  String get formattedDuration {
    final minutes = duration.inMinutes.toString().padLeft(2, '0');
    final seconds = (duration.inSeconds % 60).toString().padLeft(2, '0');
    return '$minutes:$seconds';
  }

  String get formattedBitrate {
    return '${(bitrateAvg10s / 1000).toStringAsFixed(1)} Mbps';
  }
}

enum TemperatureStatus {
  normal,
  warning,
  throttle,
}

enum QueueStatus {
  healthy,  // < 0.5s (green)
  stable,   // 0.5-2s (yellow)
  congested, // > 2s (red)
}
