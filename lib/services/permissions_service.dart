import 'package:flutter/services.dart';

/// Service to check and request Android permissions
class PermissionsService {
  static const MethodChannel _channel = MethodChannel('com.screenlive.app/permissions');

  /// Request all required permissions (RECORD_AUDIO, POST_NOTIFICATIONS, etc.)
  Future<void> requestAllPermissions() async {
    try {
      await _channel.invokeMethod('requestPermissions');
    } on PlatformException catch (e) {
      print('PermissionsService.requestAllPermissions error: ${e.code} - ${e.message}');
    }
  }

  /// Check if all required permissions are granted
  Future<bool> hasAllPermissions() async {
    try {
      final result = await _channel.invokeMethod<bool>('hasAllPermissions');
      return result ?? false;
    } on PlatformException catch (e) {
      print('PermissionsService.hasAllPermissions error: ${e.code} - ${e.message}');
      return false;
    }
  }

  /// Get detailed status of all permissions
  Future<Map<String, bool>> getPermissionStatus() async {
    try {
      final result = await _channel.invokeMethod<Map>('getPermissionStatus');
      return Map<String, bool>.from(result ?? {});
    } on PlatformException catch (e) {
      print('PermissionsService.getPermissionStatus error: ${e.code} - ${e.message}');
      return {};
    }
  }
}
