package com.screenlive.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver triggered after device reboot.
 * If app was streaming before reboot (and user didn't stop), restart activity.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "[RECOVERY] Device rebooted - checking if stream was active")

            val prefs = Prefs(context)
            val wasStreaming = prefs.wasStreaming
            val manualStop = prefs.manualStop

            if (wasStreaming && !manualStop) {
                Log.i(TAG, "[RECOVERY] Stream was active before reboot - restarting activity in 3s")
                // Restart activity to re-request MediaProjection permission
                RestartHelper.scheduleRestartActivity(context, 3000L)
            } else {
                Log.i(TAG, "[RECOVERY] Stream was not active (wasStreaming=$wasStreaming, manualStop=$manualStop)")
            }
        }
    }
}
