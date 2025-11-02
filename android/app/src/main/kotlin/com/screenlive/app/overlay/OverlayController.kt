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
        val container = LinearLayout(appContext!!)
        container.orientation = LinearLayout.HORIZONTAL
        // GIẢM SIZE 2/3: từ 116x40 → 77x27dp (2/3 của kích thước cũ)
        container.layoutParams = LinearLayout.LayoutParams(dp(77), dp(27))
        container.setBackgroundColor(android.graphics.Color.parseColor("#80000000")) // Semi-transparent background
        container.setPadding(dp(2), dp(2), dp(2), dp(2))
        container.gravity = Gravity.CENTER_VERTICAL
        
        // MIC button (21x21dp - 2/3 của 32dp)
        val micButton = TextView(appContext!!)
        micButton.id = View.generateViewId()
        micButton.text = "🎤" // Mic emoji
        micButton.textSize = 12f // giảm từ 18f
        micButton.gravity = Gravity.CENTER
        micButton.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50")) // Green = ON
        micButton.layoutParams = LinearLayout.LayoutParams(dp(21), dp(21))
        micButton.setOnClickListener {
            val newState = com.screenlive.app.MainActivity.toggleMicFromOverlay()
            Log.i(TAG, "[PTL] Mic button clicked, new state: $newState")
            micButton.setBackgroundColor(
                if (newState) android.graphics.Color.parseColor("#4CAF50") // Green ON
                else android.graphics.Color.parseColor("#757575") // Gray OFF
            )
            // Reset fade timer khi tương tác
            kickAutoFade()
        }
        container.addView(micButton)
        
        // SOUND button (21x21dp - 2/3 của 32dp)
        val soundButton = TextView(appContext!!)
        soundButton.id = View.generateViewId()
        soundButton.text = "🔊" // Speaker emoji
        soundButton.textSize = 12f // giảm từ 18f
        soundButton.gravity = Gravity.CENTER
        soundButton.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50")) // Green = ON
        val soundParams = LinearLayout.LayoutParams(dp(21), dp(21))
        soundParams.marginStart = dp(3)
        soundButton.layoutParams = soundParams
        soundButton.setOnClickListener {
            val newState = com.screenlive.app.MainActivity.toggleGameAudioFromOverlay()
            Log.i(TAG, "[PTL] Sound button clicked, new state: $newState")
            soundButton.setBackgroundColor(
                if (newState) android.graphics.Color.parseColor("#4CAF50") // Green ON
                else android.graphics.Color.parseColor("#757575") // Gray OFF
            )
            // Reset fade timer khi tương tác
            kickAutoFade()
        }
        container.addView(soundButton)
        
        // Red dot (drag handle) - 8x8dp (2/3 của 12dp)
        val dotContainer = FrameLayout(appContext!!)
        val dotParams = LinearLayout.LayoutParams(dp(21), dp(21))
        dotParams.marginStart = dp(3)
        dotContainer.layoutParams = dotParams
        
        val dot = View(appContext!!)
        dot.layoutParams = FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER)
        dot.setBackgroundColor(android.graphics.Color.parseColor("#FF3B30"))
        dotContainer.addView(dot)
        container.addView(dotContainer)
        
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
        var touchStartedOnButton = false
        
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    layoutParams?.let { startWinX = it.x; startWinY = it.y }
                    isDragging = false
                    
                    // Check if touch started on a button (MIC or SOUND)
                    touchStartedOnButton = false
                    if (view is ViewGroup) {
                        for (i in 0 until view.childCount) {
                            val child = view.getChildAt(i)
                            if (child is TextView && (child.text == "🎤" || child.text == "🔊")) {
                                val location = IntArray(2)
                                child.getLocationOnScreen(location)
                                val x = event.rawX.toInt()
                                val y = event.rawY.toInt()
                                if (x >= location[0] && x <= location[0] + child.width &&
                                    y >= location[1] && y <= location[1] + child.height) {
                                    touchStartedOnButton = true
                                    break
                                }
                            }
                        }
                    }
                    
                    mainHandler.removeCallbacks(fadeRunnable)
                    view.alpha = 1f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Only drag if not on button
                    if (!touchStartedOnButton) {
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
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Show stop dialog only if tapped on red dot (not dragging, not on buttons)
                    if (!isDragging && !touchStartedOnButton) {
                        // Check if tap was on red dot area (rightmost part of mini view)
                        if (view is ViewGroup && view.childCount >= 3) {
                            val redDotContainer = view.getChildAt(2) // Third child is red dot container
                            val location = IntArray(2)
                            redDotContainer.getLocationOnScreen(location)
                            val x = event.rawX.toInt()
                            val y = event.rawY.toInt()
                            if (x >= location[0] && x <= location[0] + redDotContainer.width &&
                                y >= location[1] && y <= location[1] + redDotContainer.height) {
                                Log.i(TAG, "[PTL] Overlay: red dot tapped - showing stop confirmation")
                                showStopConfirmationDialog()
                            }
                        } else {
                            // For non-mini views (compact, expanded), show dialog on any tap
                            Log.i(TAG, "[PTL] Overlay: user tapped - showing stop confirmation")
                            showStopConfirmationDialog()
                        }
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
