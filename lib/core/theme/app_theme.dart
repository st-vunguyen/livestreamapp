import 'package:flutter/material.dart';
import 'tokens.dart';

/// PTL: Simplified theme for Flutter 3.24.5 compatibility
class AppTheme {
  static ThemeData get lightTheme {
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      colorScheme: ColorScheme.light(
        primary: AppTokens.primaryLight,
        secondary: AppTokens.secondaryLight,
        surface: AppTokens.surfaceLight,
        background: AppTokens.backgroundLight,
        error: AppTokens.errorLight,
        onPrimary: AppTokens.onPrimaryLight,
        onSurface: AppTokens.onSurfaceLight,
        onSurfaceVariant: AppTokens.onSurfaceVariantLight,
      ),
      scaffoldBackgroundColor: AppTokens.backgroundLight,
    );
  }

  static ThemeData get darkTheme {
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: ColorScheme.dark(
        primary: AppTokens.primaryDark,
        secondary: AppTokens.secondaryDark,
        surface: AppTokens.surfaceDark,
        background: AppTokens.backgroundDark,
        error: AppTokens.errorDark,
        onPrimary: AppTokens.onPrimaryDark,
        onSurface: AppTokens.onSurfaceDark,
        onSurfaceVariant: AppTokens.onSurfaceVariantDark,
      ),
      scaffoldBackgroundColor: AppTokens.backgroundDark,
    );
  }
}
