package com.screenlive.app

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

/**
 * Handler for streaming metrics to Flutter via EventChannel
 * Provides real-time updates of fps, bitrate, queue, temperature
 */
class MetricsHandler : EventChannel.StreamHandler {
    private var eventSink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    fun sendMetrics(metrics: Map<String, Any>) {
        mainHandler.post {
            eventSink?.success(metrics)
        }
    }

    fun cleanup() {
        eventSink = null
    }
}
