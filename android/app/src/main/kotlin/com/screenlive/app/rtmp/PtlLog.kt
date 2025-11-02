package com.screenlive.app.rtmp

import android.util.Log

object PtlLog {
    private const val T = "[PTL]"
    
    // [PTL DEBUG] Runtime toggle - không cần rebuild
    @Volatile private var debugEnabled = false
    
    fun enableDebug(enabled: Boolean) { 
        debugEnabled = enabled
        Log.i(T, "Debug logging ${if (enabled) "ENABLED" else "DISABLED"}")
    }
    
    fun i(msg: String) = Log.i(T, msg)
    fun w(msg: String, tr: Throwable? = null) = Log.w(T, msg, tr)
    fun e(msg: String, tr: Throwable? = null) = Log.e(T, msg, tr)
    fun d(msg: String) { if (debugEnabled) Log.d(T, msg) }
    
    fun isDebugEnabled(): Boolean = debugEnabled
}