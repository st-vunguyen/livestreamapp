package com.screenlive.app.overlay

/**
 * [PTL] Immutable metrics snapshot for overlay rendering
 * Why: Debounced updates (250ms) prevent GC pressure and UI jank
 */
data class StreamHealth(
    val bitrateKbps: Int = 0,
    val fps: Int = 0,
    val droppedFrames: Int = 0,
    val elapsedMs: Long = 0L
) {
    fun formatElapsed(): String {
        val sec = (elapsedMs / 1000).toInt()
        val m = sec / 60
        val s = sec % 60
        return "%02d:%02d".format(m, s)
    }
}
