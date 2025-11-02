import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
// Original complex screens (disabled for MVP)
// import '../../features/setup/presentation/setup_screen.dart';
// import '../../features/live/presentation/live_screen.dart';
// MVP screens (RootEncoder-based)
import '../../features/setup/setup_screen.dart';
import '../../features/live/live_screen.dart';

/// App router configuration with go_router
/// Routes: /setup (MVP Setup), /live (MVP Live)
final appRouterProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    initialLocation: '/setup',
    routes: [
      GoRoute(
        path: '/setup',
        name: 'setup',
        pageBuilder: (context, state) => const MaterialPage(
          child: SetupScreen(),
        ),
      ),
      GoRoute(
        path: '/live',
        name: 'live',
        pageBuilder: (context, state) => const MaterialPage(
          child: LiveScreen(),
        ),
      ),
    ],
    errorBuilder: (context, state) => Scaffold(
      body: Center(
        child: Text('Page not found: ${state.uri.path}'),
      ),
    ),
  );
});
