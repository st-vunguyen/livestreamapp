package com.screenlive.app.overlay

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.view.isVisible
import kotlin.math.abs

object OverlayController {
    
    private const val TAG = "OverlayController"
    private const val AUTO_FADE_DELAY_MS = 3000L
    private const val FADE_DURATION_MS = 300L
    private const val DRAG_THRESHOLD_PX = 10
    
    enum class Variant { MINI, COMPACT, EXPANDED }

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    @Volatile private var isActive = false
    private var currentVariant = Variant.MINI

    private var miniView: View? = null
    private var compactView: View? = null
    private var expandedView: View? = null

    private var bitrateKbps = 0
    private var fps = 0
    private var droppedFrames = 0
    private var elapsedMs = 0L

    var onStopRequested: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fadeRunnable = Runnable {
        overlayView?.animate()?.alpha(0.3f)?.setDuration(FADE_DURATION_MS)
            ?.setInterpolator(DecelerateInterpolator())?.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun start(context: Context) {
        if (isActive) {
            Log.w(TAG, "[PTL] Overlay already active")
            return
        }
        
        appContext = context.applicationContext
        windowManager = appContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // [FIX] Add FLAG_NOT_FOCUSABLE to allow touch pass-through to game
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16  // 16px from left edge
            y = 80  // 80px from top (below status bar + notification bar)
        }
        
        overlayView = createMiniView().also { miniView = it }
        
        try {
            windowManager?.addView(overlayView, layoutParams)
            isActive = true
            currentVariant = Variant.MINI
            kickAutoFade()
            Log.i(TAG, "[PTL] Overlay started")
        } catch (e: Exception) {
            Log.e(TAG, "[PTL] Failed to add overlay: ${e.message}", e)
            overlayView = null
            layoutParams = null
        }
    }

    fun stop(context: Context? = null) {
        if (!isActive) return
        
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "[PTL] Error removing overlay: ${e.message}", e)
        }
        
        mainHandler.removeCallbacks(fadeRunnable)
        overlayView = null
        miniView = null
        compactView = null
        expandedView = null
        layoutParams = null
        windowManager = null
        appContext = null
        isActive = false
        Log.i(TAG, "[PTL] Overlay stopped")
    }

    fun setMetrics(bitrateKbps: Int, fps: Int, droppedFrames: Int, elapsedMs: Long) {
        if (!isActive) return
        
        this.bitrateKbps = bitrateKbps
        this.fps = fps
        this.droppedFrames = droppedFrames
        this.elapsedMs = elapsedMs
        
        mainHandler.post {
            when (currentVariant) {
                Variant.COMPACT -> updateCompactView()
                Variant.EXPANDED -> updateExpandedView()
                else -> Unit
            }
            overlayView?.alpha = 1f
            kickAutoFade()
        }
    }

    fun setVariant(v: Variant) {
        if (v == currentVariant || !isActive) return
        
        mainHandler.post {
            val newView = when (v) {
                Variant.MINI -> miniView ?: createMiniView().also { miniView = it }
                Variant.COMPACT -> compactView ?: createCompactView().also { compactView = it }
                Variant.EXPANDED -> expandedView ?: createExpandedView().also { expandedView = it }
            }
            
            try {
                overlayView?.let { windowManager?.removeView(it) }
                overlayView = newView
                windowManager?.addView(overlayView, layoutParams)
                currentVariant = v
                overlayView?.alpha = 1f
                kickAutoFade()
                Log.i(TAG, "[PTL] Switched to variant: $v")
            } catch (e: Exception) {
                Log.e(TAG, "[PTL] Failed to switch variant: ${e.message}", e)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createMiniView(): View {
        val container = FrameLayout(appContext!!)
        container.layoutParams = FrameLayout.LayoutParams(dp(24), dp(24))
        
        val dot = View(appContext!!)
        dot.layoutParams = FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER)
        dot.setBackgroundColor(android.graphics.Color.parseColor("#FF3B30"))
        container.addView(dot)
        
        attachTouchHandler(container)
        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createCompactView(): View {
        val container = LinearLayout(appContext!!)
        container.orientation = LinearLayout.HORIZONTAL
        container.layoutParams = LinearLayout.LayoutParams(dp(196), dp(44))
        container.setBackgroundColor(android.graphics.Color.parseColor("#CC000000"))
        container.setPadding(dp(12), dp(6), dp(12), dp(6))
        container.gravity = Gravity.CENTER_VERTICAL
        
        val liveText = TextView(appContext!!)
        liveText.text = "● LIVE"
        liveText.setTextColor(android.graphics.Color.parseColor("#FF3B30"))
        liveText.textSize = 12f
        container.addView(liveText)
        
        val metrics = TextView(appContext!!)
        metrics.id = View.generateViewId()
        metrics.text = "0 kbps · 0 fps"
        metrics.setTextColor(android.graphics.Color.parseColor("#E6FFFFFF"))
        metrics.textSize = 12f
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginStart = dp(8)
        metrics.layoutParams = lp
        container.addView(metrics)
        
        compactView = container
        attachTouchHandler(container)
        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createExpandedView(): View {
        val container = LinearLayout(appContext!!)
        container.orientation = LinearLayout.VERTICAL
        container.layoutParams = LinearLayout.LayoutParams(dp(264), LinearLayout.LayoutParams.WRAP_CONTENT)
        container.setBackgroundColor(android.graphics.Color.parseColor("#CC000000"))
        container.setPadding(dp(14), dp(10), dp(14), dp(10))
        
        expandedView = container
        attachTouchHandler(container)
        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchHandler(view: View) {
        var startX = 0f
        var startY = 0f
        var startWinX = 0
        var startWinY = 0
        var isDragging = false
        
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    layoutParams?.let { startWinX = it.x; startWinY = it.y }
                    isDragging = false
                    mainHandler.removeCallbacks(fadeRunnable)
                    view.alpha = 1f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isDragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams?.x = (startWinX + dx).toInt()
                        layoutParams?.y = (startWinY + dy).toInt()
                        windowManager?.updateViewLayout(view, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Show confirmation dialog
                        Log.i(TAG, "[PTL] Overlay: user tapped - showing stop confirmation")
                        showStopConfirmationDialog()
                    }
                    kickAutoFade()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateCompactView() {
        (compactView as? LinearLayout)?.let { layout ->
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                if (child is TextView && child.text.contains("kbps")) {
                    child.text = "$bitrateKbps kbps · $fps fps"
                }
            }
        }
    }

    private fun updateExpandedView() {
        // Implement if needed
    }

    private fun kickAutoFade() {
        mainHandler.removeCallbacks(fadeRunnable)
        mainHandler.postDelayed(fadeRunnable, AUTO_FADE_DELAY_MS)
    }

    private fun dp(value: Int): Int {
        return (value * (appContext?.resources?.displayMetrics?.density ?: 1f)).toInt()
    }

    private fun showStopConfirmationDialog() {
        try {
            val builder = AlertDialog.Builder(appContext!!, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            builder.setTitle("Dừng livestream?")
            builder.setMessage("Bạn có chắc muốn kết thúc stream không?")
            
            builder.setPositiveButton("Dừng") { dialog, _ ->
                Log.i(TAG, "[PTL] User confirmed stop")
                onStopRequested?.invoke()
                dialog.dismiss()
            }
            
            builder.setNegativeButton("Hủy") { dialog, _ ->
                Log.i(TAG, "[PTL] User cancelled stop")
                dialog.dismiss()
            }
            
            val dialog = builder.create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "[PTL] Failed to show dialog: ${e.message}", e)
            // Fallback: direct stop if dialog fails
            onStopRequested?.invoke()
        }
    }
}
