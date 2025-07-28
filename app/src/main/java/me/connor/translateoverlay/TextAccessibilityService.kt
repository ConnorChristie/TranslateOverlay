package me.connor.translateoverlay

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class TextAccessibilityService : AccessibilityService() {

    private lateinit var overlay: FloatingOverlay

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = FloatingOverlay(this /* as Context */)
        overlay.show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // your magnifier logic here…
    }

    override fun onInterrupt() { }

    override fun onDestroy() {
        super.onDestroy()
        overlay.remove()
    }
}