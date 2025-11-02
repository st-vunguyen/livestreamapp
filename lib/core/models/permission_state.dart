// ENT-004: PermissionState entity for tracking Android permissions
class PermissionState {
  final bool mediaProjection;
  final bool audioPlaybackCapture;
  final bool microphone;

  const PermissionState({
    this.mediaProjection = false,
    this.audioPlaybackCapture = false,
    this.microphone = false,
  });

  PermissionState copyWith({
    bool? mediaProjection,
    bool? audioPlaybackCapture,
    bool? microphone,
  }) {
    return PermissionState(
      mediaProjection: mediaProjection ?? this.mediaProjection,
      audioPlaybackCapture: audioPlaybackCapture ?? this.audioPlaybackCapture,
      microphone: microphone ?? this.microphone,
    );
  }

  bool get hasRequiredPermissions {
    return mediaProjection && microphone;
  }
}
