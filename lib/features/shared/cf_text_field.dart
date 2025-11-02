import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/theme/tokens.dart';

/// Custom text field component from spec Section 4.1
class CFTextField extends StatefulWidget {
  final String label;
  final String? hint;
  final String? value;
  final ValueChanged<String>? onChanged;
  final String? errorText;
  final bool isSecure;
  final bool enabled;
  final TextInputType? keyboardType;
  final List<TextInputFormatter>? inputFormatters;
  final int? maxLines;

  const CFTextField({
    super.key,
    required this.label,
    this.hint,
    this.value,
    this.onChanged,
    this.errorText,
    this.isSecure = false,
    this.enabled = true,
    this.keyboardType,
    this.inputFormatters,
    this.maxLines = 1,
  });

  const CFTextField.secure({
    super.key,
    required this.label,
    this.hint,
    this.value,
    this.onChanged,
    this.errorText,
    this.enabled = true,
    this.keyboardType,
    this.inputFormatters,
  })  : isSecure = true,
        maxLines = 1;

  @override
  State<CFTextField> createState() => _CFTextFieldState();
}

class _CFTextFieldState extends State<CFTextField> {
  late TextEditingController _controller;
  bool _obscureText = true;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.value);
  }

  @override
  void didUpdateWidget(CFTextField oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.value != oldWidget.value && widget.value != _controller.text) {
      _controller.text = widget.value ?? '';
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: _controller,
      enabled: widget.enabled,
      obscureText: widget.isSecure && _obscureText,
      keyboardType: widget.keyboardType,
      inputFormatters: widget.inputFormatters,
      maxLines: widget.maxLines,
      onChanged: widget.onChanged,
      decoration: InputDecoration(
        labelText: widget.label,
        hintText: widget.hint,
        errorText: widget.errorText,
        suffixIcon: widget.isSecure ? _buildVisibilityToggle() : null,
        errorMaxLines: 2,
      ),
    );
  }

  Widget _buildVisibilityToggle() {
    return IconButton(
      icon: Icon(
        _obscureText ? Icons.visibility : Icons.visibility_off,
        size: AppTokens.iconMedium,
      ),
      onPressed: () {
        setState(() {
          _obscureText = !_obscureText;
        });
      },
      tooltip: _obscureText ? 'Show' : 'Hide',
    );
  }
}
