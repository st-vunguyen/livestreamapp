import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../core/models/local_settings.dart';
import '../../../core/models/preset.dart';
import '../../../core/theme/tokens.dart';
import '../../../storage/settings_store.dart';
import '../../../logic/session_controller.dart';
import '../../../services/permissions_service.dart';
import '../../shared/cf_button.dart';
import '../../shared/cf_text_field.dart';
import '../../shared/dialogs.dart';

/// UI-001: Setup Screen for configuring RTMPS connection
class SetupScreen extends ConsumerStatefulWidget {
  const SetupScreen({super.key});

  @override
  ConsumerState<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends ConsumerState<SetupScreen> {
  String _rtmpsUrl = '';
  String _streamKey = '';
  PresetType _preset = PresetType.balanced;
  AudioSourceType _audioSource = AudioSourceType.microphone;
  bool _saveCredentials = false;
  bool _isStarting = false;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final settingsAsync = ref.read(localSettingsProvider);
    settingsAsync.when(
      data: (settings) {
        if (mounted) {
          setState(() {
            _rtmpsUrl = settings.rtmpsUrl;
            _streamKey = settings.streamKey;
            _preset = settings.preset;
            _audioSource = settings.audioSource;
          });
        }
      },
      loading: () {},
      error: (_, __) {},
    );
  }

  bool get _isFormValid {
    return (_rtmpsUrl.startsWith('rtmps://') || _rtmpsUrl.startsWith('rtmp://')) && _streamKey.isNotEmpty;
  }

  String? get _urlError {
    if (_rtmpsUrl.isEmpty) return null;
    if (!_rtmpsUrl.startsWith('rtmps://') && !_rtmpsUrl.startsWith('rtmp://')) {
      return 'URL must start with rtmp:// or rtmps://';
    }
    return null;
  }

  String? get _keyError {
    if (_streamKey.isEmpty) return 'Stream key is required';
    return null;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Setup'),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(AppTokens.spacing24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _buildConfigurationSection(),
              const SizedBox(height: AppTokens.spacing32),
              _buildPresetSection(),
              const SizedBox(height: AppTokens.spacing32),
              _buildAudioSection(),
              const SizedBox(height: AppTokens.spacing32),
              _buildStartButton(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildConfigurationSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'RTMPS Configuration',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: AppTokens.spacing16),
        CFTextField(
          label: 'RTMP/RTMPS URL',
          hint: 'rtmp://a.rtmp.youtube.com/live2',
          value: _rtmpsUrl,
          onChanged: (value) => setState(() => _rtmpsUrl = value),
          errorText: _urlError,
          keyboardType: TextInputType.url,
        ),
        const SizedBox(height: AppTokens.spacing16),
        CFTextField.secure(
          label: 'Stream Key',
          hint: 'Enter your stream key',
          value: _streamKey,
          onChanged: (value) => setState(() => _streamKey = value),
          errorText: _keyError,
        ),
        const SizedBox(height: AppTokens.spacing12),
        CheckboxListTile(
          value: _saveCredentials,
          onChanged: (value) => setState(() => _saveCredentials = value ?? false),
          title: const Text('Save credentials locally'),
          subtitle: const Text('Stored securely on device'),
          contentPadding: EdgeInsets.zero,
          controlAffinity: ListTileControlAffinity.leading,
        ),
      ],
    );
  }

  Widget _buildPresetSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Encoder Preset',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: AppTokens.spacing16),
        _buildPresetCard(PresetType.high, Preset.high),
        const SizedBox(height: AppTokens.spacing12),
        _buildPresetCard(PresetType.balanced, Preset.balanced),
        const SizedBox(height: AppTokens.spacing12),
        _buildPresetCard(PresetType.fallback, Preset.fallback),
      ],
    );
  }

  Widget _buildPresetCard(PresetType type, Preset preset) {
    final isSelected = _preset == type;
    return InkWell(
      onTap: () => setState(() => _preset = type),
      borderRadius: BorderRadius.circular(AppTokens.radiusMedium),
      child: Container(
        padding: const EdgeInsets.all(AppTokens.spacing16),
        decoration: BoxDecoration(
          border: Border.all(
            color: isSelected
                ? Theme.of(context).colorScheme.primary
                : Theme.of(context).dividerColor,
            width: isSelected ? 2 : 1,
          ),
          borderRadius: BorderRadius.circular(AppTokens.radiusMedium),
        ),
        child: Row(
          children: [
            Radio<PresetType>(
              value: type,
              groupValue: _preset,
              onChanged: (value) => setState(() => _preset = value!),
            ),
            const SizedBox(width: AppTokens.spacing12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    preset.name,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    preset.displayInfo,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAudioSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Audio Source',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: AppTokens.spacing8),
        Text(
          'Game audio requires Android 10+ and game permission',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: AppTokens.spacing16),
        RadioListTile<AudioSourceType>(
          value: AudioSourceType.game,
          groupValue: _audioSource,
          onChanged: (value) => setState(() => _audioSource = value!),
          title: const Text('Game Audio'),
          contentPadding: EdgeInsets.zero,
        ),
        RadioListTile<AudioSourceType>(
          value: AudioSourceType.microphone,
          groupValue: _audioSource,
          onChanged: (value) => setState(() => _audioSource = value!),
          title: const Text('Microphone'),
          contentPadding: EdgeInsets.zero,
        ),
      ],
    );
  }

  Widget _buildStartButton() {
    return CFButton.primary(
      label: 'Start Streaming',
      icon: Icons.play_arrow,
      isLoading: _isStarting,
      onPressed: _isFormValid && !_isStarting ? _handleStart : null,
    );
  }

  Future<void> _handleStart() async {
    setState(() => _isStarting = true);

    try {
      // Step 1: Check and request all required permissions first
      final permissionsService = PermissionsService();
      final hasPermissions = await permissionsService.hasAllPermissions();
      
      if (!hasPermissions) {
        // Request permissions - this will show system dialogs
        await permissionsService.requestAllPermissions();
        
        // Wait a bit for user to respond
        await Future.delayed(const Duration(milliseconds: 500));
        
        // Check again
        final hasPermissionsNow = await permissionsService.hasAllPermissions();
        if (!hasPermissionsNow) {
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(
                content: Text('Required permissions not granted. Please allow microphone and notification permissions.'),
                duration: Duration(seconds: 4),
              ),
            );
          }
          return;
        }
      }
      
      final settings = LocalSettings(
        rtmpsUrl: _rtmpsUrl,
        streamKey: _streamKey,
        preset: _preset,
        audioSource: _audioSource,
      );

      // Validate settings before proceeding
      if (!settings.isValid) {
        throw Exception('Invalid settings');
      }

      // Save settings
      final storeAsync = await ref.read(settingsStoreProvider.future);
      await storeAsync.saveSettings(settings, saveCredentials: _saveCredentials);

      // Start session
      await ref.read(sessionControllerProvider.notifier).startSession(settings: settings);

      // Only navigate if session started successfully
      if (mounted) {
        final currentState = ref.read(sessionControllerProvider);
        if (currentState == SessionState.streaming || currentState == SessionState.configuring) {
          context.go('/live');
        }
      }
    } catch (e) {
      if (mounted) {
        // Check if native implementation is not available
        if (e.toString().contains('Native screen capture not implemented')) {
          await AppDialogs.showNativeNotImplemented(context: context);
        } else if (e.toString().contains('PERMISSION_DENIED')) {
          // User denied MediaProjection permission in system dialog
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Screen capture permission denied. Please allow permission to start streaming.'),
              duration: Duration(seconds: 4),
            ),
          );
        } else {
          final errorType = _parseError(e.toString());
          await AppDialogs.showIngestError(
            context: context,
            errorType: errorType,
          );
        }
      }
    } finally {
      if (mounted) {
        setState(() => _isStarting = false);
      }
    }
  }

  String _parseError(String error) {
    if (error.contains('permission')) return 'permission';
    if (error.contains('TLS') || error.contains('handshake')) return 'tls_handshake';
    if (error.contains('unreachable') || error.contains('timeout')) return 'unreachable';
    if (error.contains('key') || error.contains('auth')) return 'invalid_key';
    return 'unknown';
  }
}
