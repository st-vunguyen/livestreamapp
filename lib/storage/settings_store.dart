import 'dart:convert';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../core/models/local_settings.dart';

/// Storage layer for LocalSettings (ENT-001)
/// Uses flutter_secure_storage for credentials, shared_preferences for non-sensitive data
class SettingsStore {
  static const _keySettings = 'local_settings';
  static const _keySecureUrl = 'secure_rtmps_url';
  static const _keySecureStreamKey = 'secure_stream_key';

  final FlutterSecureStorage _secureStorage;
  final SharedPreferences _prefs;

  SettingsStore({
    FlutterSecureStorage? secureStorage,
    required SharedPreferences prefs,
  })  : _secureStorage = secureStorage ?? const FlutterSecureStorage(),
        _prefs = prefs;

  /// Load settings from storage
  Future<LocalSettings> loadSettings() async {
    try {
      // Load non-sensitive data from SharedPreferences
      final settingsJson = _prefs.getString(_keySettings);
      LocalSettings settings = settingsJson != null
          ? LocalSettings.fromJson(jsonDecode(settingsJson))
          : LocalSettings.empty();

      // Override with secure credentials if available
      final secureUrl = await _secureStorage.read(key: _keySecureUrl);
      final secureKey = await _secureStorage.read(key: _keySecureStreamKey);

      if (secureUrl != null || secureKey != null) {
        settings = settings.copyWith(
          rtmpsUrl: secureUrl ?? settings.rtmpsUrl,
          streamKey: secureKey ?? settings.streamKey,
        );
      }

      return settings;
    } catch (e) {
      // TODO: Log error to monitoring service
      return LocalSettings.empty();
    }
  }

  /// Save settings to storage
  Future<void> saveSettings(LocalSettings settings, {bool saveCredentials = false}) async {
    try {
      // Save non-sensitive data (preset, audio source)
      final settingsWithoutCredentials = settings.copyWith(
        rtmpsUrl: '',
        streamKey: '',
      );
      await _prefs.setString(_keySettings, jsonEncode(settingsWithoutCredentials.toJson()));

      // Optionally save credentials to secure storage
      if (saveCredentials) {
        await _secureStorage.write(key: _keySecureUrl, value: settings.rtmpsUrl);
        await _secureStorage.write(key: _keySecureStreamKey, value: settings.streamKey);
      } else {
        // Clear secure credentials if user unchecks "Save locally"
        await _secureStorage.delete(key: _keySecureUrl);
        await _secureStorage.delete(key: _keySecureStreamKey);
      }
    } catch (e) {
      // TODO: Log error to monitoring service
      rethrow;
    }
  }

  /// Clear all stored settings
  Future<void> clearSettings() async {
    await _prefs.remove(_keySettings);
    await _secureStorage.delete(key: _keySecureUrl);
    await _secureStorage.delete(key: _keySecureStreamKey);
  }

  /// Check if credentials are saved
  Future<bool> hasStoredCredentials() async {
    final url = await _secureStorage.read(key: _keySecureUrl);
    final key = await _secureStorage.read(key: _keySecureStreamKey);
    return url != null && key != null;
  }
}

/// Provider for SettingsStore
final settingsStoreProvider = FutureProvider<SettingsStore>((ref) async {
  final prefs = await SharedPreferences.getInstance();
  return SettingsStore(prefs: prefs);
});

final localSettingsProvider = FutureProvider<LocalSettings>((ref) async {
  final store = await ref.watch(settingsStoreProvider.future);
  return store.loadSettings();
});
