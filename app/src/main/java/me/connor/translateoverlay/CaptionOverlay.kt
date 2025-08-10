package me.connor.translateoverlay

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
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

    // No manual width-based wrapping; rely on TextView layout.

    /** The caption TextView that the user sees. */
    private val tv = TextView(context).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        maxLines = maxVisibleLines
        setLineSpacing(2f, 1.05f)
        setTextIsSelectable(false)
        // Apply Material3 body text appearance and colors
        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        setTextColor(onSurface)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
            breakStrategy = Layout.BREAK_STRATEGY_BALANCED
        }
    }

    // We no longer pre-wrap by width; let TextView wrap naturally.

    /** Control bar containing language spinner and close button */
    private val controlBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(8.dp, 6.dp, 8.dp, 6.dp)
        visibility = View.GONE // Initially hidden
        // Subtle surface-variant chip-like bar
        val surfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
        background = MaterialShapeDrawable(
            ShapeAppearanceModel.builder().setAllCornerSizes(12.dp.toFloat()).build()
        ).apply {
            fillColor = ContextCompat.getColorStateList(context, android.R.color.transparent)
            setTint(surfaceVariant)
            setStroke(1f, outline and 0x33FFFFFF)
            elevation = 0f
        }
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
        
        // Prevent initial selection from firing a broadcast
        var initializing = true
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initializing) {
                    initializing = false
                    return
                }
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
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        contentDescription = context.getString(R.string.close_overlay)
        // Borderless ripple for icon buttons
        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val ta = context.obtainStyledAttributes(attrs)
        foreground = ta.getDrawable(0)
        ta.recycle()
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

    /** If true, broadcast a stop request when this view detaches (used for STT overlay). */
    var stopServicesOnDetach: Boolean = false

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

        applyMaterialBackground()
        setPadding(0, 0, 0, 0)
        clipToOutline = true
        isClickable = true
        isFocusable = false
        // Ripple on tap to match Android components
        val attrs = intArrayOf(android.R.attr.selectableItemBackground)
        val ta = context.obtainStyledAttributes(attrs)
        foreground = ta.getDrawable(0)
        ta.recycle()

        // Handle taps on the overlay
        setOnClickListener {
            if (controlBar.visibility == View.VISIBLE) {
                hideControls()
            } else {
                showControls()
            }
        }

        // Initialize spinner selection from the saved source language (if available)
        try {
            val prefs: SharedPreferences = context.getSharedPreferences("TranslateOverlayPrefs", Context.MODE_PRIVATE)
            val saved = prefs.getString("sourceLanguage", null)
            if (!saved.isNullOrEmpty()) {
                val codes = resources.getStringArray(R.array.language_codes)
                val idx = codes.indexOf(saved)
                if (idx >= 0) {
                    languageSpinner.setSelection(idx, false)
                }
            }
        } catch (_: Exception) {
            // ignore; leave default selection
        }

        // No width tracking needed; TextView handles wrapping.

        // Add a subtle bottom-right resize affordance (visual only; resizing handled by parent touch listener)
        val outlineColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
        val handle = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_crop)
            imageAlpha = 140
            setColorFilter(outlineColor)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(handle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = 6.dp
            bottomMargin = 6.dp
        })
    }

    private fun applyMaterialBackground() {
        val corner = 12.dp.toFloat()
        val shape = ShapeAppearanceModel.builder()
            .setAllCornerSizes(corner)
            .build()

        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)

        background = MaterialShapeDrawable(shape).apply {
            // Slightly translucent surface to let content show subtly
            val bg = (surface and 0x00FFFFFF) or (0xE5 shl 24) // ~90% alpha
            setTint(bg)
            setStroke(1f, outline and 0x33FFFFFF)
            initializeElevationOverlay(context)
            elevation = 8f
        }
        ViewCompat.setElevation(this, 8.dp.toFloat())
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
        if (stopServicesOnDetach) {
            // Ensure services are stopped if STT overlay is removed or dismissed by the system
            context.sendBroadcast(
                Intent(FloatingOverlay.ACTION_STOP_SERVICES)
                    .setPackage(context.packageName)
            )
        }
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
            processIncoming(delta)
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
    private fun processIncoming(text: String) {
        if (text.isBlank()) return

        // Ensure a single boundary space when concatenating chunks mid-line
        if (currentLine.isNotEmpty()) {
            val lastChar = currentLine.last()
            val firstNew = text.first()
            val needsSpace = !lastChar.isWhitespace() && !firstNew.isWhitespace()
            if (needsSpace) currentLine.append(' ')
        }

        currentLine.append(text)

        // Split on natural sentence boundaries rather than width.
        var start = 0
        val s = currentLine.toString()
        for (i in s.indices) {
            val c = s[i]
            val isDelim = when (c) {
                '.', '!', '?', '。', '！', '？', '…', '\n' -> true
                else -> false
            }
            if (isDelim) {
                val seg = s.substring(start, i + 1).trim()
                if (seg.isNotEmpty()) pushLine(seg)
                start = i + 1
            }
        }
        // Keep any trailing fragment in currentLine
        currentLine.clear()
        if (start < s.length) currentLine.append(s.substring(start))
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

    // No reflow needed on width change; TextView handles layout.
}
