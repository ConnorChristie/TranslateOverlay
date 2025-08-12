package me.connor.translateoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat

// Removed explicit resize mode; overlay width is static and managed centrally

class FloatingOverlay(private val context: Context) {

    companion object {
        const val PREFS_NAME         = "OverlayPrefs"
        const val KEY_X              = "overlay_x"
        const val KEY_Y              = "overlay_y"
        const val KEY_WIDTH          = "overlay_width"
        const val KEY_HEIGHT         = "overlay_height"

        const val ACTION_UPDATE_TEXT  = "me.connor.translateoverlay.ACTION_UPDATE_TEXT"
        const val ACTION_TEXT_UPDATED = "me.connor.translateoverlay.ACTION_TEXT_UPDATED"
        const val ACTION_STOP_SERVICES = "me.connor.translateoverlay.ACTION_STOP_SERVICES"
        const val ACTION_LANGUAGE_CHANGED = "me.connor.translateoverlay.ACTION_LANGUAGE_CHANGED"
        const val ACTION_RESET_OVERLAY_POSITION = "me.connor.translateoverlay.ACTION_RESET_OVERLAY_POSITION"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: CaptionOverlay? = null
    private var params: LayoutParams? = null
    private var isDragging = false
    private var lastTouchDownTime = 0L
    private var configChangeReceiver: BroadcastReceiver? = null

    // receive translated text from your AccessibilityService
    private val textUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent?.getStringExtra("extracted_text")
                ?.let { updateText(it, replace = true) }
        }
    }

    // receive stop services broadcast
    private val stopServicesReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP_SERVICES -> remove()
                ACTION_RESET_OVERLAY_POSITION -> resetPosition()
            }
        }
    }

    fun show(updateTextOnAccessibility: Boolean) {
        if (overlayView != null) return

        if (updateTextOnAccessibility) {
            val filter = IntentFilter(ACTION_TEXT_UPDATED)
            ContextCompat.registerReceiver(
                context,
                textUpdatedReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        // Register for stop services broadcast ONLY for accessibility overlay.
        // For the STT overlay owned by a Service, the Service handles stop and removes the overlay.
        if (updateTextOnAccessibility) {
            val stopFilter = IntentFilter().apply {
                addAction(ACTION_STOP_SERVICES)
                addAction(ACTION_RESET_OVERLAY_POSITION)
            }
            ContextCompat.registerReceiver(
                context,
                stopServicesReceiver,
                stopFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        val themedContext = ContextThemeWrapper(context, R.style.Theme_TranslateOverlay)
        overlayView = CaptionOverlay(themedContext).apply {
            // If this is the STT overlay (not the accessibility drag overlay),
            // stop services when the view detaches (dismissed or removed).
            stopServicesOnDetach = !updateTextOnAccessibility
            setOnTouchListener(CombinedTouchListener())
        }

        val type = LayoutParams.TYPE_APPLICATION_OVERLAY
        val screenW = context.resources.displayMetrics.widthPixels
        val overlayW = (screenW * 0.85).toInt().coerceAtLeast(200.dp)

        params = LayoutParams(
            overlayW,
            LayoutParams.WRAP_CONTENT,
            type,
            LayoutParams.FLAG_NOT_FOCUSABLE
                    or LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 100)
            y = prefs.getInt(KEY_Y, 200)
        }

        windowManager.addView(overlayView, params)

        // Listen for configuration changes to re-apply width (orientation changes)
        configChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                updateWidthForScreen()
            }
        }
        ContextCompat.registerReceiver(
            context,
            configChangeReceiver,
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun updateText(newText: String, replace: Boolean = false) {
        overlayView?.updateTranscript(newText, replace)
    }

    fun isShown(): Boolean {
        return overlayView != null
    }

    fun remove() {
        overlayView?.let {
            windowManager.removeView(it)
            try {
                context.unregisterReceiver(textUpdatedReceiver)
                context.unregisterReceiver(stopServicesReceiver)
                configChangeReceiver?.let { r -> context.unregisterReceiver(r) }
            } catch (e: IllegalArgumentException) {
                // Receiver not registered
            }
            overlayView = null
            params = null
        }
    }

    private fun resetPosition() {
        params?.let {
            val screenW = context.resources.displayMetrics.widthPixels
            val defaultW = (screenW * 0.8).toInt()
            val minW = 200.dp
            val overlayW = defaultW.coerceIn(minW, screenW)
            it.width = overlayW
            it.x = 100
            it.y = 200
            windowManager.updateViewLayout(overlayView, it)
            prefs.edit()
                .putInt(KEY_X, it.x)
                .putInt(KEY_Y, it.y)
                .putInt(KEY_WIDTH, it.width)
                .putInt(KEY_HEIGHT, overlayView?.height ?: 0)
                .apply()
        }
    }

    private inner class CombinedTouchListener : View.OnTouchListener {
        private var lastX = 0
        private var lastY = 0
        private var initialX = 0
        private var initialY = 0
        private val CLICK_DURATION = 200L
        private val DRAG_THRESHOLD = 10

        

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            val overlay = overlayView
            // If the touch starts within the control bar area, let the child handle it
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && overlay is CaptionOverlay) {
                if (overlay.isPointInControlBar(ev.rawX.toInt(), ev.rawY.toInt())) {
                    return false
                }
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.rawX.toInt()
                    lastY = ev.rawY.toInt()
                    initialX = lastX
                    initialY = lastY
                    lastTouchDownTime = System.currentTimeMillis()
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX.toInt() - lastX
                    val dy = ev.rawY.toInt() - lastY
                    // Check if we've moved enough to consider it a drag
                    val totalMoved = Math.abs(ev.rawX.toInt() - initialX) + Math.abs(ev.rawY.toInt() - initialY)
                    if (totalMoved > DRAG_THRESHOLD) {
                        isDragging = true
                    }

                    if (isDragging) {
                        params?.let {
                            val dm = context.resources.displayMetrics
                            val screenW = dm.widthPixels
                            val screenH = dm.heightPixels
                            val maxX = (screenW - v.width).coerceAtLeast(0)
                            val maxY = (screenH - v.height).coerceAtLeast(0)
                            val newX = (it.x + dx).coerceIn(0, maxX)
                            val newY = (it.y + dy).coerceIn(0, maxY)
                            if (newX != it.x || newY != it.y) {
                                it.x = newX
                                it.y = newY
                                windowManager.updateViewLayout(v, it)
                            }
                        }
                        lastX = ev.rawX.toInt()
                        lastY = ev.rawY.toInt()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val touchDuration = System.currentTimeMillis() - lastTouchDownTime
                    if (!isDragging && touchDuration < CLICK_DURATION) {
                        // This was a tap - let the CaptionOverlay handle it
                        v.performClick()
                    } else if (isDragging) {
                        // Save final bounds after drag
                        params?.let {
                            val dm = context.resources.displayMetrics
                            val screenW = dm.widthPixels
                            val screenH = dm.heightPixels
                            val maxX = (screenW - v.width).coerceAtLeast(0)
                            val maxY = (screenH - v.height).coerceAtLeast(0)
                            var finalX = it.x.coerceIn(0, maxX)
                            var finalY = it.y.coerceIn(0, maxY)
                            val snap = 24.dp
                            // Snap only on release
                            finalX = when {
                                finalX <= snap -> 0
                                (maxX - finalX) <= snap -> maxX
                                else -> finalX
                            }
                            finalY = when {
                                finalY <= snap -> 0
                                (maxY - finalY) <= snap -> maxY
                                else -> finalY
                            }
                            it.x = finalX
                            it.y = finalY
                            prefs.edit()
                                .putInt(KEY_X, it.x)
                                .putInt(KEY_Y, it.y)
                                .putInt(KEY_HEIGHT, v.height)
                                .apply()
                        }

                        context.sendBroadcast(Intent(ACTION_UPDATE_TEXT).setPackage(context.packageName))
                    }
                }
            }
            return true
        }
    }

    private fun updateWidthForScreen() {
        params?.let {
            val screenW = context.resources.displayMetrics.widthPixels
            val newW = (screenW * 0.85).toInt().coerceAtLeast(200.dp)
            if (it.width != newW) {
                it.width = newW
                windowManager.updateViewLayout(overlayView, it)
            }
            prefs.edit().putInt(KEY_WIDTH, newW).apply()
        }
    }
}
