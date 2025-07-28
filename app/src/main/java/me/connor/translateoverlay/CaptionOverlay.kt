package me.connor.translateoverlay

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
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
    private val maxVisibleLines: Int = 3,
    private val anchorChars: Int = 32,        // ≈ 5–6 English words
    private val ringCapacity: Int = 512       // how many *completed* lines we keep
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Buffer that stores completed *lines* (not raw chars). Constant‑time growth/trim. */
    private val ring = ArrayDeque<String>(ringCapacity)

    /** Holds the line we’re still building; NOT yet pushed to [ring]. */
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
    }

    /** Running *full* transcript received from the ASR engine last time we updated. */
    private var prev: String = ""

    init {
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        background = ContextCompat.getDrawable(context, R.drawable.caption_bg)
        setPadding(12.dp, 6.dp, 12.dp, 6.dp)   // keep text off the edge
        clipToOutline = true
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 🔑  Public API --------------------------------------------------------------
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Feed a *full* transcript string exactly as supplied by the ASR engine.
     * The overlay will figure out what changed and update itself smoothly.
     */
    fun updateTranscript(full: String) {
        val delta = extractDelta(full)
        wrapAndPush(delta)
        redraw()
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

        // ➊ completed lines (oldest → newest)
        lines += ring.takeLast(maxVisibleLines - 1)

        // ➋ current unfinished line (if any)
        if (currentLine.isNotBlank()) lines += currentLine.toString()

        // keep at most 3 lines visible
        tv.text = lines.takeLast(maxVisibleLines).joinToString("\n")
    }
}