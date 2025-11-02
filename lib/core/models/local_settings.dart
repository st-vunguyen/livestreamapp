// ENT-001: LocalSettings entity for RTMPS configuration
class LocalSettings {
  final String rtmpsUrl;
  final String streamKey;
  final PresetType preset;
  final AudioSourceType audioSource;

  const LocalSettings({
    required this.rtmpsUrl,
    required this.streamKey,
    required this.preset,
    required this.audioSource,
  });

  factory LocalSettings.empty() {
    return const LocalSettings(
      rtmpsUrl: '',
      streamKey: '',
      preset: PresetType.balanced,
      audioSource: AudioSourceType.microphone,
    );
  }

  LocalSettings copyWith({
    String? rtmpsUrl,
    String? streamKey,
    PresetType? preset,
    AudioSourceType? audioSource,
  }) {
    return LocalSettings(
      rtmpsUrl: rtmpsUrl ?? this.rtmpsUrl,
      streamKey: streamKey ?? this.streamKey,
      preset: preset ?? this.preset,
      audioSource: audioSource ?? this.audioSource,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'rtmpsUrl': rtmpsUrl,
      'streamKey': streamKey,
      'preset': preset.name,
      'audioSource': audioSource.name,
    };
  }

  factory LocalSettings.fromJson(Map<String, dynamic> json) {
    return LocalSettings(
      rtmpsUrl: json['rtmpsUrl'] as String? ?? '',
      streamKey: json['streamKey'] as String? ?? '',
      preset: PresetType.values.firstWhere(
        (e) => e.name == json['preset'],
        orElse: () => PresetType.balanced,
      ),
      audioSource: AudioSourceType.values.firstWhere(
        (e) => e.name == json['audioSource'],
        orElse: () => AudioSourceType.microphone,
      ),
    );
  }

  bool get isValid {
    return (rtmpsUrl.startsWith('rtmps://') || rtmpsUrl.startsWith('rtmp://')) && streamKey.isNotEmpty;
  }

  // Mask stream key for display/logging
  String get maskedStreamKey {
    if (streamKey.length <= 4) return '••••';
    return streamKey.substring(0, 2) + '••••••' + streamKey.substring(streamKey.length - 2);
  }
}

enum PresetType {
  high,
  balanced,
  fallback,
}

enum AudioSourceType {
  game,
  microphone,
}
