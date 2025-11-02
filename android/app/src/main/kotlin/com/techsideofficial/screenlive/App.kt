package com.screenlive.app

import android.app.Application
import android.util.Log

/**
 * Global Application class with hardened crash recovery mechanism.
 * Intercepts uncaught exceptions and restarts activity to re-request MediaProjection.
 */
class App : Application() {
    companion object {
        private const val TAG = "SLive:App"
    }

    override fun onCreate() {
        super.onCreate()
        
        // Install global exception handler for crash recovery
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "[CRASH] Uncaught exception in thread ${thread.name}", throwable)
            
            val prefs = Prefs(this)
            val manualStop = prefs.manualStop
            val wasStreaming = prefs.wasStreaming
            
            // If not manual stop and was streaming → restart activity to re-request MediaProjection
            if (!manualStop && wasStreaming) {
                Log.i(TAG, "[RECOVERY] Stream was active - restarting activity in 1.5s")
                RestartHelper.scheduleRestartActivity(this, 1500L)
            } else {
                Log.i(TAG, "[RECOVERY] No restart needed (manualStop=$manualStop, wasStreaming=$wasStreaming)")
            }
            
            // Kill process to allow clean restart
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        
        Log.i(TAG, "✅ Hardened crash recovery initialized")
    }
}
