package me.connor.translateoverlay

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import kotlin.math.min

val Int.dp: Int
    get() = (this * Resources.getSystem().displayMetrics.density).toInt()

/**
 * A floating overlay that shows *at most the last [maxVisibleLines]* lines of live captions.
 *
 * It copes with ASR engines that keep re‑emitting the *entire* transcript by
 * extracting only the **delta** (new words) using an "anchor search".
 *
 * 1.  We keep the previous full transcript string in [prev].
 * 2.  From that, take the last [anchorChars] characters as an *anchor*.
 * 3.  Look for that anchor in the new string and slice off everything that follows it.
 * 4.  If the anchor is not found (engine rewound further than [anchorChars]),
 *     fall back to treating the whole string as fresh.
 */
class CaptionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val maxVisibleLines: Int = 4,
    private val anchorChars: Int = 32,        // ≈ 5–6 English words
    private val ringCapacity: Int = 512       // how many *completed* lines we keep
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Buffer that stores completed *lines* (not constant‑time growth/trim). */
    private val ring = ArrayDeque<String>(ringCapacity)

    /** Holds the line we're still building; NOT yet pushed to [ring]. */
    private val currentLine = StringBuilder()

    /** Paint used solely to measure text width for word‑wrapping. */
    private val paint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16.dp.toFloat()
    }

    /** The caption TextView that the user sees. */
    private val tv = TextView(context).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        maxLines = maxVisibleLines
        setLineSpacing(2f, 1f)
        setTextIsSelectable(false)
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, 8.dp) // Add bottom padding for controls
    }

    /** Control bar containing language spinner and close button */
    private val controlBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(4.dp, 4.dp, 4.dp, 4.dp)
        visibility = View.GONE // Initially hidden
        setBackgroundColor(Color.parseColor("#33000000")) // Semi-transparent background
    }

    /** Language selection spinner */
    private val languageSpinner = Spinner(context).apply {
        val adapter = ArrayAdapter.createFromResource(
            context,
            R.array.language_names,
            android.R.layout.simple_spinner_item
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        this.adapter = adapter
        
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val languageCodes = resources.getStringArray(R.array.language_codes)
                // Broadcast language change
                context.sendBroadcast(
                    Intent("me.connor.translateoverlay.ACTION_LANGUAGE_CHANGED")
                        .putExtra("language_code", languageCodes[position])
                        .setPackage(context.packageName)
                )
                // Keep controls visible while interacting
                resetAutoHide()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Close button with trash can icon */
    private val closeButton = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_delete)
        background = null
        setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        setOnClickListener {
            // Stop all services and remove overlay
            context.sendBroadcast(
                Intent(FloatingOverlay.ACTION_STOP_SERVICES)
                    .setPackage(context.packageName)
            )
        }
    }

    /** Running *full* transcript received from the ASR engine last time we updated. */
    private var prev: String = ""

    /** Handler for auto-hiding controls */
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private val AUTO_HIDE_DELAY = 3000L // 3 seconds

    init {
        // Create a vertical layout to hold text and controls
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Add text view first
        contentLayout.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Add controls to the control bar
        controlBar.addView(languageSpinner, LinearLayout.LayoutParams(
            0,  // width will be determined by weight
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f  // take up remaining space
        ))
        
        controlBar.addView(closeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Add control bar at the bottom
        contentLayout.addView(controlBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Add the content layout to the frame
        addView(contentLayout, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ))

        background = ContextCompat.getDrawable(context, R.drawable.caption_bg)
        setPadding(12.dp, 6.dp, 12.dp, 6.dp)   // keep text off the edge
        clipToOutline = true

        // Handle taps on the overlay
        setOnClickListener {
            if (controlBar.visibility == View.VISIBLE) {
                hideControls()
            } else {
                showControls()
            }
        }
    }

    private fun showControls() {
        controlBar.visibility = View.VISIBLE
        resetAutoHide()
    }

    private fun hideControls() {
        controlBar.visibility = View.GONE
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun resetAutoHide() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hideHandler.removeCallbacks(hideRunnable)
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 🔑  Public API --------------------------------------------------------------
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Feed a *full* transcript string exactly as supplied by the ASR engine.
     * The overlay will figure out what changed and update itself smoothly.
     */
    fun updateTranscript(full: String, replace: Boolean = false) {
        if (replace) {
            runOnMain {
                reset()
                tv.text = full
            }
        } else {
            val delta = extractDelta(full)
            wrapAndPush(delta)
            redraw()
        }
    }

    /** Clears history (e.g. when ASR engine resets). */
    fun reset() {
        ring.clear()
        prev = ""
        tv.text = ""
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 🔧  Internals ---------------------------------------------------------------
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Returns ONLY the *new* characters added by the recogniser since the last call.
     * Uses the "anchor search" trick to handle small back‑tracks where the engine
     * rewrites the tail of the transcript.
     */
    private fun extractDelta(now: String): String {
        if (prev.isEmpty()) {
            prev = now
            return now          // first call: everything is new
        }

        val anchor = prev.takeLast(min(anchorChars, prev.length))
        val idx =
            now.lastIndexOf(anchor)     // Kotlin stdlib: O(N) over small strings
        val delta = if (idx >= 0) {
            now.substring(idx + anchor.length)
        } else {
            // recogniser rewound further than anchor – treat whole string as new
            now
        }
        prev = now
        return delta
    }

    /**
     * Feeds the freshly detected *words* through a whitespace‑aware word‑wrapper.
     * Completed lines are appended to the ring; any final partial word fragment
     * is left hanging until its line is complete on a later call.
     */
    private fun wrapAndPush(text: String) {
        if (text.isBlank()) return
        currentLine.append(text)

        val widthPx = resources.displayMetrics.widthPixels * 0.8f

        while (true) {
            val line = currentLine.toString()
            val fit  = paint.breakText(line, 0, line.length, true, widthPx, null)
            if (fit == line.length) break          // whole thing still fits → stop

            var end = fit
            val lastSpace = line.lastIndexOf(' ', fit - 1)
            if (lastSpace >= 0) end = lastSpace + 1   // keep words intact

            val completed = line.substring(0, end).trim()
            if (completed.isNotEmpty()) pushLine(completed)

            currentLine.delete(0, end)               // remove wrapped part
        }
    }

    private fun pushLine(line: String) {
        if (ring.size == ringCapacity) ring.removeFirst()
        ring.addLast(line)
    }

    /** Rebuilds the overlay from the ring *plus* the still‑growing current line. */
    private fun redraw() {
        val lines = ArrayList<String>(maxVisibleLines)
        lines += ring.takeLast(maxVisibleLines - 1)

        if (currentLine.isNotBlank()) lines += currentLine.toString()

        // keep at most 3 lines visible
        runOnMain { tv.text = lines.takeLast(maxVisibleLines).joinToString("\n") }
    }

    private fun runOnMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}