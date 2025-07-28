package me.connor.translateoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat

class TextAccessibilityService : AccessibilityService() {

    companion object {
        /** Sent by your overlay view when the user lifts their finger after dragging */
        const val ACTION_UPDATE_TEXT = "me.connor.translateoverlay.ACTION_UPDATE_TEXT"
        /** Sent by this service with the text extracted under the overlay */
        const val ACTION_TEXT_UPDATED = "me.connor.translateoverlay.ACTION_TEXT_UPDATED"
        /** SharedPreferences where overlay position/size is stored by your overlay view */
        const val PREFS_NAME = "OverlayPrefs"
        const val KEY_X = "overlay_x"
        const val KEY_Y = "overlay_y"
        const val KEY_WIDTH = "overlay_width"
        const val KEY_HEIGHT = "overlay_height"
    }

    private lateinit var updateReceiver: BroadcastReceiver

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Configure the types of events we want to listen to (we'll trigger reads manually)
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        // Listen for when the overlay signals that dragging has ended
        updateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                performReadUnderOverlay()
            }
        }

        val filter = IntentFilter(ACTION_UPDATE_TEXT)
        ContextCompat.registerReceiver(
            this,
            updateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't react directly to standard accessibility events;
        // reads are triggered by the broadcast when dragging ends.
    }

    override fun onInterrupt() {
        // Required override, but no cleanup needed here
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(updateReceiver)
    }

    /**
     * Reads all visible text under the last-known overlay bounds,
     * and broadcasts it with ACTION_TEXT_UPDATED.
     */
    private fun performReadUnderOverlay() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val left = prefs.getInt(KEY_X, 0)
        val top = prefs.getInt(KEY_Y, 0)
        val width = prefs.getInt(KEY_WIDTH, 0)
        val height = prefs.getInt(KEY_HEIGHT, 0)
        val overlayRect = Rect(left, top, left + width, top + height)

        val root = rootInActiveWindow ?: return
        val collected = mutableListOf<String>()
        traverseAndCollect(root, overlayRect, collected)

        // Combine and send back to your overlay/view
        val textResult = collected.joinToString(separator = "\n")
        Intent(ACTION_TEXT_UPDATED).setPackage(packageName).also { intent ->
            intent.putExtra("extracted_text", textResult)
            sendBroadcast(intent)
        }
    }

    /**
     * Recursively walks the view hierarchy, collecting any node.text
     * whose screen bounds intersect the overlayRect.
     */
    private fun traverseAndCollect(
        node: AccessibilityNodeInfo,
        overlayRect: Rect,
        out: MutableList<String>
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (Rect.intersects(bounds, overlayRect)) {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseAndCollect(it, overlayRect, out) }
        }
    }
}
