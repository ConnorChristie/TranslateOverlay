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
import androidx.core.content.ContextCompat

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
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: CaptionOverlay? = null
    private var params: LayoutParams? = null
    private var isDragging = false
    private var lastTouchDownTime = 0L

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
            if (intent?.action == ACTION_STOP_SERVICES) {
                remove()
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

        // Register for stop services broadcast
        val stopFilter = IntentFilter(ACTION_STOP_SERVICES)
        ContextCompat.registerReceiver(
            context,
            stopServicesReceiver,
            stopFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        overlayView = CaptionOverlay(context).apply {
            setOnTouchListener(CombinedTouchListener())
        }

        val type = LayoutParams.TYPE_APPLICATION_OVERLAY
        val screenW = context.resources.displayMetrics.widthPixels
        val overlayW = (screenW * 0.8).toInt() // 80%

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
            } catch (e: IllegalArgumentException) {
                // Receiver not registered
            }
            overlayView = null
            params = null
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
                            it.x += dx
                            it.y += dy
                            windowManager.updateViewLayout(v, it)
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
                            prefs.edit()
                                .putInt(KEY_X, it.x)
                                .putInt(KEY_Y, it.y)
                                .putInt(KEY_WIDTH, it.width)
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
}
