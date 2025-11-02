import 'package:flutter/material.dart';
import '../../core/theme/tokens.dart';

/// Dialog utilities for UI-004: Permission/Error Dialogs
class AppDialogs {
  /// Show confirmation dialog
  static Future<bool> showConfirmation({
    required BuildContext context,
    required String title,
    required String message,
    String confirmText = 'OK',
    String cancelText = 'Cancel',
  }) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(cancelText),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(confirmText),
          ),
        ],
      ),
    );
    return result ?? false;
  }

  /// Show error dialog
  static Future<void> showError({
    required BuildContext context,
    required String title,
    required String message,
    String buttonText = 'OK',
  }) async {
    await showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Row(
          children: [
            Icon(
              Icons.error_outline,
              color: Theme.of(context).colorScheme.error,
              size: AppTokens.iconMedium,
            ),
            const SizedBox(width: AppTokens.spacing8),
            Text(title),
          ],
        ),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(buttonText),
          ),
        ],
      ),
    );
  }

  /// Show permission denied dialog (UI-004)
  static Future<bool> showPermissionDenied({
    required BuildContext context,
    required String permissionName,
  }) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Row(
          children: [
            Icon(
              Icons.warning_amber_rounded,
              color: Theme.of(context).brightness == Brightness.dark
                  ? AppTokens.warningDark
                  : AppTokens.warningLight,
              size: AppTokens.iconMedium,
            ),
            const SizedBox(width: AppTokens.spacing8),
            const Text('Permission Required'),
          ],
        ),
        content: Text(
          '$permissionName permission is required to start streaming. '
          'Please grant access in Settings.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
    return result ?? false;
  }

  /// Show ingest error dialog (UI-004)
  static Future<bool> showIngestError({
    required BuildContext context,
    required String errorType,
  }) async {
    String message;
    switch (errorType) {
      case 'invalid_key':
        message = 'Invalid RTMPS URL or Stream Key. Please check and try again.';
        break;
      case 'unreachable':
        message = 'Cannot reach ingest server. Check your network connection and URL.';
        break;
      case 'tls_handshake':
        message = 'Secure connection failed. Verify your RTMPS URL and network settings.';
        break;
      default:
        message = 'Connection error: $errorType';
    }

    final result = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Connection Failed'),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Retry'),
          ),
        ],
      ),
    );
    return result ?? false;
  }

  /// Show reconnecting info
  static void showReconnecting({
    required BuildContext context,
    required int attemptCount,
  }) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Reconnecting... Attempt $attemptCount/3'),
        duration: const Duration(seconds: 2),
        backgroundColor: Theme.of(context).brightness == Brightness.dark
            ? AppTokens.warningDark
            : AppTokens.warningLight,
      ),
    );
  }

  /// Show thermal warning
  static void showThermalWarning(BuildContext context) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Text('Device is hot. Consider lowering preset.'),
        duration: const Duration(seconds: 4),
        backgroundColor: Theme.of(context).colorScheme.error,
        action: SnackBarAction(
          label: 'Dismiss',
          textColor: Colors.white,
          onPressed: () {
            ScaffoldMessenger.of(context).hideCurrentSnackBar();
          },
        ),
      ),
    );
  }

  /// Show native implementation not ready error
  static Future<void> showNativeNotImplemented({
    required BuildContext context,
  }) async {
    await showError(
      context: context,
      title: 'Prototype Version',
      message: 'This is a prototype with UI and business logic complete. '
          'The native Android implementation (MediaCodec H.264/AAC encoder, '
          'FLV muxer, and RTMP socket) is scaffolded but not yet implemented.\n\n'
          'See IMPLEMENTATION_SUMMARY.md for details on completing the native code.',
      buttonText: 'Understood',
    );
  }
}
