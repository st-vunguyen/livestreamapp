import 'package:flutter/material.dart';

/// Design tokens from Mobile_Flutter_Design.md Section 10
class AppTokens {
  // Colors - Light theme
  static const Color primaryLight = Color(0xFF1976D2);
  static const Color secondaryLight = Color(0xFF424242);
  static const Color surfaceLight = Color(0xFFFFFFFF);
  static const Color backgroundLight = Color(0xFFFAFAFA);
  static const Color errorLight = Color(0xFFD32F2F);
  static const Color warningLight = Color(0xFFF57C00);
  static const Color successLight = Color(0xFF388E3C);
  static const Color onPrimaryLight = Color(0xFFFFFFFF);
  static const Color onSurfaceLight = Color(0xDE000000); // 87% opacity
  static const Color onSurfaceVariantLight = Color(0x99000000); // 60% opacity

  // Colors - Dark theme
  static const Color primaryDark = Color(0xFF42A5F5);
  static const Color secondaryDark = Color(0xFFE0E0E0);
  static const Color surfaceDark = Color(0xFF121212);
  static const Color backgroundDark = Color(0xFF000000);
  static const Color errorDark = Color(0xFFEF5350);
  static const Color warningDark = Color(0xFFFFA726);
  static const Color successDark = Color(0xFF66BB6A);
  static const Color onPrimaryDark = Color(0xFF000000);
  static const Color onSurfaceDark = Color(0xDEFFFFFF); // 87% opacity
  static const Color onSurfaceVariantDark = Color(0x99FFFFFF); // 60% opacity

  // Radii
  static const double radiusSmall = 4.0;
  static const double radiusMedium = 8.0;
  static const double radiusLarge = 16.0;
  static const double radiusFull = 999.0;

  // Spacing
  static const double spacing4 = 4.0;
  static const double spacing8 = 8.0;
  static const double spacing12 = 12.0;
  static const double spacing16 = 16.0;
  static const double spacing24 = 24.0;
  static const double spacing32 = 32.0;

  // Icon sizes
  static const double iconSmall = 16.0;
  static const double iconMedium = 24.0;
  static const double iconLarge = 48.0;

  // Elevation
  static const double elevationLow = 2.0;
  static const double elevationMedium = 8.0;
  static const double elevationHigh = 24.0;

  // Minimum tap target (accessibility)
  static const double minTapTarget = 48.0;
}
