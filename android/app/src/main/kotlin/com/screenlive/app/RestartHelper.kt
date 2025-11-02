package com.screenlive.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Helper to schedule app/service restart via AlarmManager.
 * Works even in Doze mode using setExactAndAllowWhileIdle.
 */
object RestartHelper {
    private const val TAG = "RestartHelper"

    /**
     * Schedule MainActivity restart (to re-request MediaProjection).
     * Used after crash/kill when wasStreaming=true.
     */
    fun scheduleRestartActivity(ctx: Context, delayMs: Long) {
        try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ?: Intent(ctx, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                )

            val pendingIntent = PendingIntent.getActivity(
                ctx,
                1001,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )

            val alarmManager = ctx.getSystemService(AlarmManager::class.java)
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )

            Log.i(TAG, "[RECOVERY] Activity restart scheduled in ${delayMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "[RECOVERY] Failed to schedule activity restart", e)
        }
    }

    /**
     * Schedule ScreenCaptureService restart.
     * Used when task removed from Recent Apps.
     */
    fun scheduleRestartService(ctx: Context, delayMs: Long) {
        try {
            val intent = Intent(ctx, ScreenCaptureService::class.java)
            val pendingIntent = PendingIntent.getService(
                ctx,
                1002,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )

            val alarmManager = ctx.getSystemService(AlarmManager::class.java)
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )

            Log.i(TAG, "[RECOVERY] Service restart scheduled in ${delayMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "[RECOVERY] Failed to schedule service restart", e)
        }
    }
}
