import 'package:flutter/material.dart';
import '../../core/theme/tokens.dart';

/// Custom button component with variants from spec Section 4.1
enum CFButtonVariant {
  primary,
  secondary,
  ghost,
  destructive,
}

class CFButton extends StatelessWidget {
  final String label;
  final VoidCallback? onPressed;
  final CFButtonVariant variant;
  final bool isLoading;
  final IconData? icon;

  const CFButton({
    super.key,
    required this.label,
    this.onPressed,
    this.variant = CFButtonVariant.primary,
    this.isLoading = false,
    this.icon,
  });

  const CFButton.primary({
    super.key,
    required this.label,
    this.onPressed,
    this.isLoading = false,
    this.icon,
  }) : variant = CFButtonVariant.primary;

  const CFButton.secondary({
    super.key,
    required this.label,
    this.onPressed,
    this.isLoading = false,
    this.icon,
  }) : variant = CFButtonVariant.secondary;

  const CFButton.ghost({
    super.key,
    required this.label,
    this.onPressed,
    this.isLoading = false,
    this.icon,
  }) : variant = CFButtonVariant.ghost;

  const CFButton.destructive({
    super.key,
    required this.label,
    this.onPressed,
    this.isLoading = false,
    this.icon,
  }) : variant = CFButtonVariant.destructive;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final bool isDark = theme.brightness == Brightness.dark;

    if (isLoading) {
      return _buildLoadingButton(theme, isDark);
    }

    switch (variant) {
      case CFButtonVariant.primary:
        return ElevatedButton(
          onPressed: onPressed,
          child: _buildContent(),
        );
      case CFButtonVariant.secondary:
        return OutlinedButton(
          onPressed: onPressed,
          child: _buildContent(),
        );
      case CFButtonVariant.ghost:
        return TextButton(
          onPressed: onPressed,
          child: _buildContent(),
        );
      case CFButtonVariant.destructive:
        return ElevatedButton(
          onPressed: onPressed,
          style: ElevatedButton.styleFrom(
            backgroundColor: isDark ? AppTokens.errorDark : AppTokens.errorLight,
            foregroundColor: Colors.white,
          ),
          child: _buildContent(),
        );
    }
  }

  Widget _buildContent() {
    if (icon != null) {
      return Row(
        mainAxisSize: MainAxisSize.min,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, size: AppTokens.iconMedium),
          const SizedBox(width: AppTokens.spacing8),
          Text(label),
        ],
      );
    }
    return Text(label);
  }

  Widget _buildLoadingButton(ThemeData theme, bool isDark) {
    final color = variant == CFButtonVariant.primary || variant == CFButtonVariant.destructive
        ? (isDark ? AppTokens.primaryDark : AppTokens.primaryLight)
        : (isDark ? AppTokens.secondaryDark : AppTokens.secondaryLight);

    return ElevatedButton(
      onPressed: null,
      style: ElevatedButton.styleFrom(
        backgroundColor: color.withOpacity(0.6),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          SizedBox(
            width: AppTokens.iconMedium,
            height: AppTokens.iconMedium,
            child: CircularProgressIndicator(
              strokeWidth: 2,
              valueColor: AlwaysStoppedAnimation<Color>(
                isDark ? AppTokens.onPrimaryDark : AppTokens.onPrimaryLight,
              ),
            ),
          ),
          const SizedBox(width: AppTokens.spacing8),
          Text(label),
        ],
      ),
    );
  }
}
