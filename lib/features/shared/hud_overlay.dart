import 'package:flutter/material.dart';
import '../../core/models/session_metrics.dart';
import '../../core/theme/tokens.dart';

/// HUD overlay component for UI-002 Live Control
class HUDOverlay extends StatefulWidget {
  final SessionMetrics metrics;
  final bool isCollapsed;
  final VoidCallback onToggleCollapse;

  const HUDOverlay({
    super.key,
    required this.metrics,
    this.isCollapsed = false,
    required this.onToggleCollapse,
  });

  @override
  State<HUDOverlay> createState() => _HUDOverlayState();
}

class _HUDOverlayState extends State<HUDOverlay> {
  Offset _position = const Offset(16, 100);

  @override
  Widget build(BuildContext context) {
    return Positioned(
      left: _position.dx,
      top: _position.dy,
      child: GestureDetector(
        onPanUpdate: (details) {
          setState(() {
            _position += details.delta;
          });
        },
        child: Material(
          elevation: AppTokens.elevationMedium,
          borderRadius: BorderRadius.circular(AppTokens.radiusMedium),
          color: Colors.black.withOpacity(0.75),
          child: Container(
            padding: const EdgeInsets.all(AppTokens.spacing12),
            child: widget.isCollapsed ? _buildCollapsed() : _buildExpanded(),
          ),
        ),
      ),
    );
  }

  Widget _buildCollapsed() {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _buildMetricIcon(Icons.videocam, '${widget.metrics.fpsCurrent.toStringAsFixed(0)} FPS'),
        const SizedBox(width: AppTokens.spacing12),
        _buildMetricIcon(Icons.show_chart, widget.metrics.formattedBitrate),
        const SizedBox(width: AppTokens.spacing8),
        IconButton(
          icon: const Icon(Icons.unfold_more, color: Colors.white, size: 16),
          onPressed: widget.onToggleCollapse,
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints(),
        ),
      ],
    );
  }

  Widget _buildExpanded() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'Stream Health',
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                fontSize: 12,
              ),
            ),
            const SizedBox(width: AppTokens.spacing8),
            IconButton(
              icon: const Icon(Icons.unfold_less, color: Colors.white, size: 16),
              onPressed: widget.onToggleCollapse,
              padding: EdgeInsets.zero,
              constraints: const BoxConstraints(),
            ),
          ],
        ),
        const SizedBox(height: AppTokens.spacing8),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildMetricRow(Icons.videocam, '${widget.metrics.fpsCurrent.toStringAsFixed(0)} FPS'),
                const SizedBox(height: AppTokens.spacing4),
                _buildMetricRow(Icons.show_chart, widget.metrics.formattedBitrate),
                const SizedBox(height: AppTokens.spacing4),
                _buildMetricRow(Icons.timer, widget.metrics.formattedDuration),
              ],
            ),
            const SizedBox(width: AppTokens.spacing16),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildQueueIndicator(),
                const SizedBox(height: AppTokens.spacing4),
                _buildMetricRow(Icons.refresh, '${widget.metrics.reconnectCount}'),
                const SizedBox(height: AppTokens.spacing4),
                _buildTemperatureIndicator(),
              ],
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildMetricIcon(IconData icon, String value) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: Colors.white, size: 14),
        const SizedBox(width: 4),
        Text(
          value,
          style: const TextStyle(color: Colors.white, fontSize: 11),
        ),
      ],
    );
  }

  Widget _buildMetricRow(IconData icon, String value) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: Colors.white70, size: 14),
        const SizedBox(width: 6),
        Text(
          value,
          style: const TextStyle(color: Colors.white, fontSize: 11),
        ),
      ],
    );
  }

  Widget _buildQueueIndicator() {
    final queueStatus = widget.metrics.queueStatus;
    Color color;
    switch (queueStatus) {
      case QueueStatus.healthy:
        color = AppTokens.successLight;
        break;
      case QueueStatus.stable:
        color = AppTokens.warningLight;
        break;
      case QueueStatus.congested:
        color = AppTokens.errorLight;
        break;
    }

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(Icons.cloud_upload, color: color, size: 14),
        const SizedBox(width: 6),
        Text(
          '${widget.metrics.uploadQueueSec.toStringAsFixed(1)}s',
          style: TextStyle(color: color, fontSize: 11, fontWeight: FontWeight.bold),
        ),
      ],
    );
  }

  Widget _buildTemperatureIndicator() {
    IconData icon;
    Color color;
    String label;

    switch (widget.metrics.temperatureStatus) {
      case TemperatureStatus.normal:
        icon = Icons.thermostat;
        color = Colors.white70;
        label = 'Normal';
        break;
      case TemperatureStatus.warning:
        icon = Icons.thermostat;
        color = AppTokens.warningLight;
        label = 'Warm';
        break;
      case TemperatureStatus.throttle:
        icon = Icons.whatshot;
        color = AppTokens.errorLight;
        label = 'Hot';
        break;
    }

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: color, size: 14),
        const SizedBox(width: 6),
        Text(
          label,
          style: TextStyle(color: color, fontSize: 11),
        ),
      ],
    );
  }
}
