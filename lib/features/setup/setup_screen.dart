import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import '../../services/root_encoder_service.dart';

/// Setup screen for entering RTMPS URL and Stream Key
/// 
/// MVP implementation with validation:
/// - rtmpsUrl must start with "rtmps://"
/// - streamKey cannot be empty
class SetupScreen extends StatefulWidget {
  const SetupScreen({super.key});

  @override
  State<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<SetupScreen> {
  final _formKey = GlobalKey<FormState>();
  final _urlController = TextEditingController(
    text: 'rtmp://a.rtmp.youtube.com/live2',  // YouTube RTMP ingest (plain, not TLS)
  );
  final _keyController = TextEditingController();
  
  bool _isLoading = false;
  String? _error;
  
  @override
  void dispose() {
    _urlController.dispose();
    _keyController.dispose();
    super.dispose();
  }
  
  Future<void> _startStream() async {
    if (!_formKey.currentState!.validate()) {
      return;
    }
    
    setState(() {
      _isLoading = true;
      _error = null;
    });
    
      try {
      await RootEncoderService.instance.start(
        rtmpsUrl: _urlController.text.trim(),
        streamKey: _keyController.text.trim(),
      );
      
      if (!mounted) return;
      
      // [FIX] Don't navigate to live screen - just minimize app
      // Native overlay will show instead of Flutter UI
      // User can return to app to see stats later if needed
          // Navigate to Live screen after successful start
          if (context.mounted) {
            context.go('/live');
          }      // Optional: Minimize app to home screen after 2 seconds
      Future.delayed(const Duration(seconds: 2), () {
        if (mounted) {
          // Send app to background (native Android will handle)
          // Flutter doesn't have built-in minimize, but overlay stays visible
        }
      });
      
    } catch (e) {
      setState(() {
        _error = e.toString();
        _isLoading = false;
      });
    }
  }
  
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('ScreenLive Setup'),
        backgroundColor: Colors.red.shade700,
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const SizedBox(height: 24),
                
                // Icon
                Icon(
                  Icons.live_tv,
                  size: 80,
                  color: Colors.red.shade700,
                ),
                
                const SizedBox(height: 16),
                
                // Title
                Text(
                  'Start Livestream',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                  textAlign: TextAlign.center,
                ),
                
                const SizedBox(height: 8),
                
                Text(
                  '720p60 @ ~3.8Mbps',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: Colors.grey.shade600,
                  ),
                  textAlign: TextAlign.center,
                ),
                
                const SizedBox(height: 32),
                
                // RTMP URL field
                TextFormField(
                  controller: _urlController,
                  decoration: InputDecoration(
                    labelText: 'RTMP Server URL',
                    hintText: 'rtmp://a.rtmp.youtube.com/live2',
                    prefixIcon: const Icon(Icons.cloud),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'URL is required';
                    }
                    if (!value.startsWith('rtmps://') && !value.startsWith('rtmp://')) {
                      return 'URL must start with rtmps:// or rtmp://';
                    }
                    return null;
                  },
                  keyboardType: TextInputType.url,
                  autocorrect: false,
                ),
                
                const SizedBox(height: 16),
                
                // Stream Key field
                TextFormField(
                  controller: _keyController,
                  decoration: InputDecoration(
                    labelText: 'Stream Key',
                    hintText: 'Your YouTube stream key',
                    prefixIcon: const Icon(Icons.key),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'Stream key is required';
                    }
                    return null;
                  },
                  obscureText: true,
                  autocorrect: false,
                  enableSuggestions: false,
                ),
                
                const SizedBox(height: 24),
                
                // Error message
                if (_error != null)
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: Colors.red.shade50,
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: Colors.red.shade200),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.error_outline, color: Colors.red.shade700),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            _error!,
                            style: TextStyle(color: Colors.red.shade900),
                          ),
                        ),
                      ],
                    ),
                  ),
                
                const SizedBox(height: 24),
                
                // Start button
                ElevatedButton.icon(
                  onPressed: _isLoading ? null : _startStream,
                  icon: _isLoading
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                          ),
                        )
                      : const Icon(Icons.play_arrow),
                  label: Text(_isLoading ? 'Starting...' : 'Start Streaming'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.red.shade700,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    textStyle: const TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                    ),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
                
                const SizedBox(height: 16),
                
                // Info text
                Text(
                  'Make sure you have microphone and screen capture permissions enabled.',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Colors.grey.shade600,
                  ),
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
