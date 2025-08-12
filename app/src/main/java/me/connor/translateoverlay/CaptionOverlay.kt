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
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.content.SharedPreferences
import androidx.appcompat.app.AlertDialog
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
    // Backing background so we can live-tune opacity
    private var overlayBackground: MaterialShapeDrawable? = null
    private var overlaySurfaceBaseColor: Int = 0
    private var overlayAlphaPercent: Int = 90


    // We no longer pre-wrap by width; let TextView wrap naturally.

    /** Control bar containing language spinner and close button */
    private val controlBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(8.dp, 4.dp, 8.dp, 6.dp)
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
            R.layout.spinner_item_small
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item_small)
        }
        this.adapter = adapter
        minimumHeight = 36.dp
        setPadding(10.dp, 4.dp, 10.dp, 4.dp)
        
        // Prevent initial selection from firing a broadcast
        var initializing = true
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                isInteractingWithControls = true
                isSpinnerOpen = true
                showControls()
            }
            false
        }
        setOnFocusChangeListener { _, hasFocus ->
            isInteractingWithControls = hasFocus
            if (!hasFocus) {
                // Resume auto-hide when focus leaves
                isSpinnerOpen = false
            }
        }
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
                isSpinnerOpen = false
                // Allow a short grace period after selection before auto-hide resumes
                isInteractingWithControls = false
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Close button */
    private val closeButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_close_24)
        contentDescription = context.getString(R.string.close_overlay)
        // Borderless ripple for icon buttons
        val borderlessRippleAttrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val ta = context.obtainStyledAttributes(borderlessRippleAttrs)
        foreground = ta.getDrawable(0)
        ta.recycle()
        background = null
        minimumWidth = 36.dp
        minimumHeight = 36.dp
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(6.dp, 6.dp, 6.dp, 6.dp)
        // Let the vector define its tint via attribute
        imageTintList = null
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

    /** Whether the user is currently interacting with the control bar */
    private var isInteractingWithControls: Boolean = false

    /** Whether the spinner dropdown is currently open */
    private var isSpinnerOpen: Boolean = false

    /** If true, broadcast a stop request when this view detaches (used for STT overlay). */
    var stopServicesOnDetach: Boolean = false

    init {
        // Load user settings
        try {
            val settings = context.getSharedPreferences("TranslateOverlayPrefs", Context.MODE_PRIVATE)
            overlayAlphaPercent = settings.getInt("overlayBgAlpha", 90).coerceIn(60, 100)
            val textSizeSp = settings.getFloat("captionTextSizeSp", 0f)
            if (textSizeSp > 0f) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            }
        } catch (_: Exception) {}

        // Create a vertical layout to hold text and controls
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

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

        // Add text view first
        contentLayout.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Place control bar at the BOTTOM
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
        val rippleAttrs = intArrayOf(android.R.attr.selectableItemBackground)
        val overlayAttrsTa = context.obtainStyledAttributes(rippleAttrs)
        foreground = overlayAttrsTa.getDrawable(0)
        overlayAttrsTa.recycle()

        // Handle taps on the overlay
        setOnClickListener {
            if (controlBar.visibility == View.VISIBLE) {
                hideControls()
            } else {
                showControls()
            }
        }

        // Long-press to open quick settings
        setOnLongClickListener {
            showQuickSettingsDialog()
            true
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

        // Resize affordance removed: static width overlay
    }

    private fun applyMaterialBackground() {
        val corner = 12.dp.toFloat()
        val shape = ShapeAppearanceModel.builder()
            .setAllCornerSizes(corner)
            .build()

        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)

        overlaySurfaceBaseColor = surface and 0x00FFFFFF
        overlayBackground = MaterialShapeDrawable(shape).apply {
            setTint(applyAlphaToColor(overlaySurfaceBaseColor, overlayAlphaPercent))
            setStroke(1f, outline and 0x33FFFFFF)
            initializeElevationOverlay(context)
            elevation = 8f
        }
        background = overlayBackground
        ViewCompat.setElevation(this, 8.dp.toFloat())
    }

    private fun showControls() {
        controlBar.visibility = View.VISIBLE
    }

    private fun hideControls() {
        if (isInteractingWithControls || isSpinnerOpen) return
        controlBar.visibility = View.GONE
    }
    

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
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
        runOnMain {
            val text = lines.takeLast(maxVisibleLines).joinToString("\n")
            if (tv.text.toString() != text) {
                tv.alpha = 0.94f
                tv.text = text
                tv.animate().alpha(1f).setDuration(120).start()
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    // Pin button removed

    /** Returns true if the raw screen point lies within the control bar bounds. */
    fun isPointInControlBar(rawX: Int, rawY: Int): Boolean {
        val loc = IntArray(2)
        controlBar.getLocationOnScreen(loc)
        val left = loc[0]
        val top = loc[1]
        val right = left + controlBar.width
        val bottom = top + controlBar.height
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom && controlBar.visibility == View.VISIBLE
    }

    private fun applyAlphaToColor(rgb: Int, percent: Int): Int {
        val alpha = (percent.coerceIn(0, 100) * 255 / 100)
        return (alpha shl 24) or (rgb and 0x00FFFFFF)
    }

    private fun showQuickSettingsDialog() {
        val context = this.context
        val padding = 16.dp
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // Text size controls
        val minSp = 14
        val maxSp = 28
        val currentSp = pxToSp(tv.textSize).toInt().coerceIn(minSp, maxSp)
        val textSizeLabel = TextView(context).apply { text = context.getString(R.string.settings_text_size) }
        val textSeek = SeekBar(context).apply {
            max = maxSp - minSp
            progress = currentSp - minSp
        }
        textSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sp = (minSp + progress).toFloat()
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveTextSize(pxToSp(tv.textSize))
            }
        })

        // Opacity controls
        val opacityLabel = TextView(context).apply { text = context.getString(R.string.settings_bg_opacity) }
        val opacitySeek = SeekBar(context).apply {
            min = 60
            max = 100
            progress = overlayAlphaPercent.coerceIn(60, 100)
        }
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                overlayAlphaPercent = progress
                overlayBackground?.setTint(applyAlphaToColor(overlaySurfaceBaseColor, overlayAlphaPercent))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveOverlayAlpha(overlayAlphaPercent)
            }
        })

        container.addView(textSizeLabel)
        container.addView(textSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(opacityLabel)
        container.addView(opacitySeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        AlertDialog.Builder(context)
            .setTitle(R.string.settings_title)
            .setView(container)
            .setNeutralButton(R.string.reset_position) { _, _ ->
                context.sendBroadcast(Intent(FloatingOverlay.ACTION_RESET_OVERLAY_POSITION).setPackage(context.packageName))
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun pxToSp(px: Float): Float {
        return px / resources.displayMetrics.scaledDensity
    }

    private fun saveTextSize(sp: Float) {
        try {
            val settings = context.getSharedPreferences("TranslateOverlayPrefs", Context.MODE_PRIVATE)
            settings.edit().putFloat("captionTextSizeSp", sp).apply()
        } catch (_: Exception) {}
    }

    private fun saveOverlayAlpha(percent: Int) {
        try {
            val settings = context.getSharedPreferences("TranslateOverlayPrefs", Context.MODE_PRIVATE)
            settings.edit().putInt("overlayBgAlpha", percent).apply()
        } catch (_: Exception) {}
    }

    // No reflow needed on width change; TextView handles layout.
}
