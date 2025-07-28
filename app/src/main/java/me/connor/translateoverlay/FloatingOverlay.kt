package me.connor.translateoverlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams

class FloatingOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: CaptionOverlay? = null
    private var params: LayoutParams? = null

    fun show() {
        if (overlayView != null) return

        overlayView = CaptionOverlay(context).apply {
            setOnTouchListener(DragTouchListener())
        }

        val type = LayoutParams.TYPE_APPLICATION_OVERLAY
        val screenW = context.resources.displayMetrics.widthPixels
        val overlayW = (screenW * 0.8).toInt() // 80% of the display

        params = LayoutParams(
            overlayW,
            LayoutParams.WRAP_CONTENT,
            type,
            LayoutParams.FLAG_NOT_FOCUSABLE or
                    LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        windowManager.addView(overlayView, params)
    }

    fun updateText(newText: String) {
        overlayView?.updateTranscript(newText)
    }

    fun remove() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
            params = null
        }
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var lastX = 0
        private var lastY = 0

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.rawX.toInt()
                    lastY = ev.rawY.toInt()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX.toInt() - lastX
                    val dy = ev.rawY.toInt() - lastY
                    params?.let {
                        it.x += dx
                        it.y += dy
                        windowManager.updateViewLayout(v, it)
                    }
                    lastX = ev.rawX.toInt()
                    lastY = ev.rawY.toInt()
                }
            }
            return true
        }
    }
}
