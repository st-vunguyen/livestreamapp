import 'package:flutter_test/flutter_test.dart';
import 'package:screen_live/core/models/local_settings.dart';

void main() {
  group('LocalSettings', () {
    test('isValid returns true for valid RTMPS URL and non-empty key', () {
      final settings = LocalSettings(
        rtmpsUrl: 'rtmps://a.rtmp.youtube.com/live2',
        streamKey: 'test-key-123',
        preset: PresetType.balanced,
        audioSource: AudioSourceType.microphone,
      );

      expect(settings.isValid, true);
    });

    test('isValid returns false for invalid URL', () {
      final settings = LocalSettings(
        rtmpsUrl: 'http://invalid.com',
        streamKey: 'test-key-123',
        preset: PresetType.balanced,
        audioSource: AudioSourceType.microphone,
      );

      expect(settings.isValid, false);
    });

    test('isValid returns false for empty stream key', () {
      final settings = LocalSettings(
        rtmpsUrl: 'rtmps://a.rtmp.youtube.com/live2',
        streamKey: '',
        preset: PresetType.balanced,
        audioSource: AudioSourceType.microphone,
      );

      expect(settings.isValid, false);
    });

    test('maskedStreamKey hides middle characters', () {
      final settings = LocalSettings(
        rtmpsUrl: 'rtmps://test.com',
        streamKey: 'abcdef123456',
        preset: PresetType.balanced,
        audioSource: AudioSourceType.microphone,
      );

      expect(settings.maskedStreamKey, 'ab••••••56');
    });
  });
}
