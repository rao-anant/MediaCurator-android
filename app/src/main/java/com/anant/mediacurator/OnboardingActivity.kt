package com.anant.mediacurator

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.anant.mediacurator.databinding.ActivityOnboardingBinding

/**
 * First-run (and replayable) explainer for the core "hide a month" idea, as a **self-paced deck**:
 * the viewer taps Next / Back through a few slides, so nothing races off-screen. Only the one action
 * slide animates (finger → delete junk → hide → the month tucks into a named "Hidden" shelf); the
 * rest are calm states the reader dwells on as long as they like.
 *
 * Slides: 0 backlog · 1 review + hide (animated) · 2 come back (it remembers) · 3 recap.
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        /** When true, opened from Help as a replay (closeable anytime, no mandatory opt-out). */
        const val EXTRA_REPLAY = "replay"

        private const val TOTAL_SLIDES = 4

        private val TILE_COLORS = intArrayOf(
            Color.parseColor("#5C8AC6"), Color.parseColor("#5BB89A"), Color.parseColor("#D08A5B"),
            Color.parseColor("#C76F97"), Color.parseColor("#9B8AD4"), Color.parseColor("#C9A95B"),
            Color.parseColor("#7FB05B")
        )
        private val TILE_EMOJI = arrayOf(
            "🌅", "🐶", "🎂", "🏖️", "🐱", "🌸", "🍕", "🚗", "🎸",
            "🏔️", "🐠", "🌮", "🎈", "🌻", "🍎", "🐦", "🚀", "🎨"
        )
    }

    private lateinit var binding: ActivityOnboardingBinding
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { PreferencesManager(this) }
    private var replay = false
    private var demoCompleted = false
    private var slideIndex = 0

    private val dotViews = mutableListOf<View>()

    // Refs for the review slide's post-hide reveal.
    private var reviewShelf: LinearLayout? = null
    private var reviewShelfNames: TextView? = null
    private var reviewReassure: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        replay = intent.getBooleanExtra(EXTRA_REPLAY, false)
        binding.btnCloseDemo.visibility = if (replay) View.VISIBLE else View.GONE

        binding.btnBack.setOnClickListener { goBack() }
        binding.btnNext.setOnClickListener { goNext() }
        binding.btnCloseDemo.setOnClickListener { finish() }
        binding.cbDontShowAgain.setOnCheckedChangeListener { _, checked ->
            if (checked && demoCompleted) optOutAndFinish()
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    slideIndex > 0 -> goBack()                 // step back through the deck
                    replay -> finish()                          // replay: closeable at slide 0
                    // mandatory first run: block exit until the deck is walked through
                }
            }
        })

        buildDots()
        binding.root.post { renderSlide() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private fun goNext() {
        if (slideIndex < TOTAL_SLIDES - 1) { slideIndex++; renderSlide() } else onDone()
    }

    private fun goBack() {
        if (slideIndex > 0) { slideIndex--; renderSlide() }
    }

    private fun onDone() {
        demoCompleted = true
        if (replay) { finish(); return }
        if (binding.cbDontShowAgain.isChecked) optOutAndFinish() else finish()
    }

    private fun optOutAndFinish() {
        prefs.setDemoDisabled()
        val app = applicationContext
        Thread { OnboardingMarker.write(app) }.start()
        finish()
    }

    // ── Slide rendering ──────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun renderSlide() {
        handler.removeCallbacksAndMessages(null)
        binding.tapFinger.animate().cancel()
        binding.tapFinger.apply { visibility = View.INVISIBLE; alpha = 0f; translationX = 0f; translationY = 0f }
        binding.slideContainer.removeAllViews()
        reviewShelf = null; reviewShelfNames = null; reviewReassure = null

        val last = slideIndex == TOTAL_SLIDES - 1
        if (last) demoCompleted = true
        updateDots()
        binding.btnBack.visibility = if (slideIndex == 0) View.INVISIBLE else View.VISIBLE
        binding.btnNext.text = if (last) "Done" else "Next"
        binding.cbDontShowAgain.visibility = if (!replay && last) View.VISIBLE else View.GONE

        when (slideIndex) {
            0 -> slideBacklog()
            1 -> slideReview()
            2 -> slideReturn()
            else -> slideRecap()
        }
    }

    private fun buildDots() {
        binding.dots.removeAllViews(); dotViews.clear()
        for (i in 0 until TOTAL_SLIDES) {
            val d = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.surface_variant)) }
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginStart = if (i == 0) 0 else dp(6) }
            }
            dotViews.add(d); binding.dots.addView(d)
        }
    }

    private fun updateDots() = dotViews.forEachIndexed { i, d ->
        (d.background as GradientDrawable).setColor(color(if (i == slideIndex) R.color.primary else R.color.surface_variant))
    }

    // ── View builders ────────────────────────────────────────────────────────

    private fun addTo(v: View, topMargin: Int = 0) {
        v.layoutParams = (v.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            .apply { if (topMargin != 0) this.topMargin = topMargin }
        binding.slideContainer.addView(v)
    }

    private fun heading(text: String, sizeSp: Float, colorRes: Int, bold: Boolean, topMargin: Int = 0): TextView {
        val t = TextView(this).apply {
            this.text = text; textSize = sizeSp; setTextColor(color(colorRes))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addTo(t, topMargin); return t
    }

    private fun addYear() {
        val y = TextView(this).apply {
            text = "2024"; textSize = 13f; setTextColor(color(R.color.on_surface_variant))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addTo(y, dp(6))
    }

    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(color(R.color.surface))
        setStroke(dp(1), color(R.color.surface_variant))
    }

    private fun addMonthCard(name: String, isNew: Boolean = false): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = cardBg(); setPadding(dp(11), dp(10), dp(11), dp(10))
        }
        val arrow = TextView(this).apply { text = "▸"; textSize = 12f; setTextColor(color(R.color.on_surface_variant)) }
        val title = TextView(this).apply {
            text = name; textSize = 13f; setTextColor(color(R.color.on_surface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        }
        card.addView(arrow); card.addView(title)
        if (isNew) card.addView(pill("new", R.color.background, R.color.primary, outline = true))
        addTo(card, dp(7)); return card
    }

    private fun pill(text: String, bgRes: Int, textRes: Int, outline: Boolean = false): TextView = TextView(this).apply {
        this.text = text; textSize = 11f; setTextColor(color(textRes))
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            if (outline) setStroke(dp(1), color(textRes)) else setColor(color(bgRes))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(5) }
    }

    /** Dashed "Hidden · <names>" shelf — where hidden months are filed (named, so nothing looks back). */
    private fun addShelf(names: String, visible: Boolean = true): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(9), dp(11), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(color(R.color.surface))
                setStroke(dp(1), color(R.color.on_surface_variant), dp(5).toFloat(), dp(4).toFloat())
            }
            visibility = if (visible) View.VISIBLE else View.GONE
        }
        val label = TextView(this).apply {
            text = "🙈  Hidden"; textSize = 12f; setTextColor(color(R.color.on_surface_variant)); }
        val nm = TextView(this).apply {
            text = names; textSize = 12f; setTextColor(color(R.color.on_surface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        }
        row.addView(label); row.addView(nm)
        addTo(row, 0); return row to nm
    }

    private fun addReassure(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat(); setColor(color(R.color.surface)); setStroke(dp(1), color(R.color.check_green))
            }
            alpha = 0f; visibility = View.GONE
        }
        bar.addView(TextView(this).apply {
            text = "Hidden only in this app — never deleted, still in your gallery."
            textSize = 12f; setTextColor(color(R.color.on_surface)); setLineSpacing(dp(1).toFloat(), 1f)
        })
        addTo(bar, dp(10)); return bar
    }

    private fun buildTile(seed: Int, i: Int): FrameLayout {
        val emoji = TextView(this).apply {
            text = TILE_EMOJI[(seed * 2 + i) % TILE_EMOJI.size]; gravity = Gravity.CENTER; textSize = 15f
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(TILE_COLORS[(seed + i) % TILE_COLORS.size]) }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val scrim = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(0x77B3261E.toInt()) }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val badge = TextView(this).apply {
            text = "✓"; gravity = Gravity.CENTER; textSize = 9f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.delete_red)); setStroke(dp(1), Color.WHITE) }
            visibility = View.GONE
            val b = dp(15); layoutParams = FrameLayout.LayoutParams(b, b, Gravity.TOP or Gravity.END)
        }
        return FrameLayout(this).apply {
            layoutParams = GridLayout.LayoutParams().apply { width = dp(34); height = dp(34); setMargins(dp(2), dp(2), dp(2), dp(2)) }
            addView(emoji); addView(scrim); addView(badge)
        }
    }

    // ── Slides ───────────────────────────────────────────────────────────────

    private fun slideBacklog() {
        heading("Months piling up", 18f, R.color.on_surface, bold = true, topMargin = dp(2))
        heading("Years of photos, waiting to be sorted.", 13f, R.color.on_surface_variant, bold = false, topMargin = dp(6))
        addYear()
        val cards = listOf(addMonthCard("March"), addMonthCard("April"), addMonthCard("May"))
        cards.forEachIndexed { i, c -> c.alpha = 0f; c.translationY = dp(6).toFloat()
            c.animate().alpha(1f).translationY(0f).setStartDelay((150L * i)).setDuration(400).start() }
    }

    private fun slideReview() {
        heading("Review, then hide", 18f, R.color.on_surface, bold = true, topMargin = dp(2))
        heading("Open a month, delete the junk, then hide it — any month, in any order.", 13f, R.color.on_surface_variant, bold = false, topMargin = dp(6))
        addYear()

        val (shelf, shelfNames) = addShelf("", visible = false)
        reviewShelf = shelf; reviewShelfNames = shelfNames

        // March, opened, with tiles + Delete (hidden) / Hide month (shown) pills.
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = cardBg(); setPadding(dp(11), dp(9), dp(11), dp(10)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = "▾"; textSize = 12f; setTextColor(color(R.color.on_surface_variant)) })
        row.addView(TextView(this).apply {
            text = "March"; textSize = 13f; setTextColor(color(R.color.on_surface)); setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        })
        val delPill = pill("Delete", R.color.delete_red, android.R.color.white).apply { alpha = 0f; visibility = View.INVISIBLE }
        val hidePill = pill("Hide month", R.color.primary, R.color.on_primary)
        row.addView(delPill); row.addView(hidePill)
        card.addView(row)
        val grid = GridLayout(this).apply {
            columnCount = 6; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9) }
        }
        val tiles = ArrayList<FrameLayout>()
        for (i in 0 until 6) { val t = buildTile(0, i); tiles.add(t); grid.addView(t) }
        card.addView(grid)
        addTo(card, dp(7))

        addMonthCard("April"); addMonthCard("May")
        reviewReassure = addReassure()

        binding.slideContainer.post { runReviewSequence(card, tiles, delPill, hidePill) }
    }

    private fun slideReturn() {
        heading("It remembers so you don't have to", 18f, R.color.on_surface, bold = true, topMargin = dp(2))
        heading("Come back tomorrow or next month — March stays hidden. You pick up exactly where you left off, plus what's new.",
            13f, R.color.on_surface_variant, bold = false, topMargin = dp(6))
        addYear()
        addShelf("March")
        addMonthCard("April"); addMonthCard("May")
        val july = addMonthCard("July", isNew = true)
        july.alpha = 0f; july.translationY = dp(6).toFloat()
        july.animate().alpha(1f).translationY(0f).setStartDelay(250).setDuration(400).start()
    }

    private fun slideRecap() {
        heading("Curate once. Stays curated.", 19f, R.color.check_green, bold = true, topMargin = dp(2))
        heading(
            "• Curate any month, in any order.\n" +
            "• Hidden only in this app — never deleted, filed away and reopenable.\n" +
            "• Come back later — it stays hidden.\n" +
            "• You move on to what's new, making real progress.",
            16f, R.color.on_surface, bold = false, topMargin = dp(14)
        ).apply { setLineSpacing(dp(6).toFloat(), 1f) }
    }

    // ── The one animated slide: finger → delete → hide → tuck into Hidden ─────

    private fun runReviewSequence(card: View, tiles: List<FrameLayout>, delPill: TextView, hidePill: TextView) {
        val finger = binding.tapFinger
        finger.animate().cancel()
        finger.translationX = 0f; finger.translationY = 0f; finger.alpha = 0f; finger.visibility = View.VISIBLE
        val fHome = IntArray(2); finger.getLocationOnScreen(fHome)

        fun moveTap(target: View, onTap: () -> Unit, next: () -> Unit) {
            val t = IntArray(2); target.getLocationOnScreen(t)
            val dx = (t[0] + target.width / 2f) - (fHome[0] + finger.width / 2f)
            val dy = (t[1] + target.height / 2f) - fHome[1].toFloat() - dp(4)
            finger.animate().alpha(1f).translationX(dx).translationY(dy).setDuration(760)
                .withEndAction {
                    finger.animate().scaleX(0.78f).scaleY(0.78f).setDuration(170).withEndAction {
                        onTap()
                        finger.animate().scaleX(1f).scaleY(1f).setDuration(170)
                            .withEndAction { handler.postDelayed({ next() }, 340) }.start()
                    }.start()
                }.start()
        }

        moveTap(tiles[1], { selectTile(tiles[1]); showPill(delPill) }, {
            moveTap(tiles[4], { selectTile(tiles[4]) }, {
                moveTap(delPill, { deleteTile(tiles[1]); deleteTile(tiles[4]); delPill.visibility = View.GONE }, {
                    handler.postDelayed({
                        moveTap(hidePill, {
                            collapse(card)
                            finger.animate().alpha(0f).setDuration(250).withEndAction { finger.visibility = View.INVISIBLE }.start()
                            revealHidden()
                        }, {})
                    }, 520)
                })
            })
        })
    }

    private fun selectTile(frame: FrameLayout) {
        frame.animate().scaleX(0.9f).scaleY(0.9f).setDuration(150).start()
        frame.getChildAt(1)?.apply { alpha = 0f; visibility = View.VISIBLE; animate().alpha(1f).setDuration(160).start() }
        frame.getChildAt(2)?.apply {
            scaleX = 0f; scaleY = 0f; visibility = View.VISIBLE
            animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator()).start()
        }
    }

    private fun deleteTile(frame: FrameLayout) {
        frame.animate().alpha(0f).scaleX(0.1f).scaleY(0.1f).setStartDelay(150).setDuration(400).start()
    }

    private fun showPill(p: TextView) {
        p.alpha = 0f; p.visibility = View.VISIBLE; p.animate().alpha(1f).setDuration(220).start()
    }

    private fun collapse(card: View) {
        val start = card.height
        if (start == 0) { card.visibility = View.GONE; return }
        ValueAnimator.ofInt(start, 0).apply {
            duration = 550
            addUpdateListener { a ->
                val lp = card.layoutParams; lp.height = a.animatedValue as Int; card.layoutParams = lp
                card.alpha = (a.animatedValue as Int).toFloat() / start
            }
            start()
        }
    }

    private fun revealHidden() {
        reviewShelfNames?.text = "March"
        reviewShelf?.apply { visibility = View.VISIBLE; alpha = 0f; animate().alpha(1f).setDuration(350).start() }
        handler.postDelayed({
            reviewReassure?.apply { visibility = View.VISIBLE; animate().alpha(1f).setDuration(400).start() }
        }, 450)
    }
}
