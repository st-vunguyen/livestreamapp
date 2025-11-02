package com.screenlive.app

import android.content.Context

/**
 * SharedPreferences helper for crash recovery state management.
 * 
 * Tracks:
 * - manualStop: User explicitly stopped (vs crash/network issue)
 * - wasStreaming: App was actively streaming before crash/kill
 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("slive", Context.MODE_PRIVATE)

    /**
     * True if user clicked STOP button.
     * Prevents auto-restart on crash/task-removal.
     */
    var manualStop: Boolean
        get() = sp.getBoolean("manualStop", false)
        set(v) { sp.edit().putBoolean("manualStop", v).apply() }

    /**
     * True if app was streaming before crash/kill.
     * Used to decide if we should restart after crash/reboot.
     */
    var wasStreaming: Boolean
        get() = sp.getBoolean("wasStreaming", false)
        set(v) { sp.edit().putBoolean("wasStreaming", v).apply() }
}
