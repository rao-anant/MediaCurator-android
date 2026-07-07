package com.anant.mediacurator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
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
 * First-run (and replayable) animated explainer for the core "hide a month" idea.
 *
 * Drives a small fake gallery — three month cards, each a pile of photo tiles — through the
 * curation loop: review → tap "Hide month" → the month collapses away → the progress bar fills
 * → "All caught up." Plays automatically when opened; Rewind / Play / Pause let the user re-watch.
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        /** When true, the screen is opened from Help as a replay (primary button says "Done"). */
        const val EXTRA_REPLAY = "replay"

        /** Muted-but-distinct colours so each tile clearly reads as a different item. */
        private val TILE_COLORS = intArrayOf(
            Color.parseColor("#5C8AC6"), // blue
            Color.parseColor("#5BB89A"), // teal
            Color.parseColor("#D08A5B"), // coral
            Color.parseColor("#C76F97"), // pink
            Color.parseColor("#9B8AD4"), // purple
            Color.parseColor("#C9A95B"), // amber
            Color.parseColor("#7FB05B")  // green
        )

        /** A spread of little pictures so tiles read as varied content, not blank swatches. */
        private val TILE_EMOJI = arrayOf(
            "🌅", "🐶", "🎂", "🏖️", "🐱", "🌸", "🍕", "🚗", "🎸",
            "🏔️", "🐠", "🌮", "🎈", "🌻", "🍎", "🐦", "🚀", "🎨"
        )
    }

    private lateinit var binding: ActivityOnboardingBinding
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { PreferencesManager(this) }
    private var replay = false          // opened from Help (not mandatory)
    private var demoCompleted = false   // the timeline has played through at least once

    private data class Demo(val card: LinearLayout, val pill: TextView, val body: GridLayout,
                            val arrow: TextView, val deletePill: TextView,
                            val deleteIndices: List<Int>, val header: LinearLayout)
    private val demos = mutableListOf<Demo>()

    private var stepIndex = 0
    private var playing = false

    private val months = listOf("March 2024" to 9, "April 2024" to 6, "June 2024" to 8)
    private lateinit var steps: List<Pair<Long, () -> Unit>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        replay = intent.getBooleanExtra(EXTRA_REPLAY, false)

        binding.btnRewind.setOnClickListener { rewind() }
        binding.btnPlay.setOnClickListener { play() }
        binding.btnPause.setOnClickListener { pause() }
        binding.btnCloseDemo.setOnClickListener { finish() }   // close without opting out
        binding.cbDontShowAgain.setOnCheckedChangeListener { _, checked ->
            if (checked && demoCompleted) optOutAndFinish()
        }

        if (replay) {
            // Replay from Help: watchable & closeable anytime, no opt-out control.
            binding.controlsRow.visibility = View.VISIBLE
            binding.cbDontShowAgain.visibility = View.GONE
            binding.btnCloseDemo.visibility = View.VISIBLE
        } else {
            // Mandatory first-run: no transport controls, must watch to the end.
            binding.controlsRow.visibility = View.GONE
            binding.cbDontShowAgain.visibility = View.VISIBLE
            binding.btnCloseDemo.visibility = View.GONE        // appears when the demo finishes
        }

        // Block Back until the demo has played through (mandatory mode); always allow in replay.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (replay || demoCompleted) finish()
            }
        })

        renderInitial()   // builds the cards and the step timeline (shuffled)
        binding.demoStack.post { play() }   // auto-play on open (both first-run and replay)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun renderInitial() {
        playing = false
        handler.removeCallbacksAndMessages(null)
        stepIndex = 0
        demos.clear()
        binding.demoStack.removeAllViews()
        binding.pbCurated.setProgressCompat(0, false)
        binding.tvPct.text = "0% curated"
        binding.tvDeletedToast.animate().cancel()
        binding.tvDeletedToast.visibility = View.GONE
        binding.tapFinger.animate().cancel()
        binding.tapFinger.visibility = View.INVISIBLE
        binding.tvCaption.text = "Press play to see how curating works."

        // Guarantee one month deletes 1, another 2, another 3 — but which month gets which count is
        // random each run. Positions within a month are randomised too (see buildCard).
        val delCounts = listOf(1, 2, 3).shuffled()
        months.forEachIndexed { idx, (label, count) ->
            demos.add(buildCard(label, count, idx * 3, delCounts.getOrElse(idx) { 1 }))
        }
        // Rebuild the timeline too, so the open-order re-shuffles on each replay.
        steps = buildSteps()
    }

    private fun cardBackground(highlighted: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(ContextCompat.getColor(this@OnboardingActivity, R.color.background))
        val strokeColor = if (highlighted) ContextCompat.getColor(this@OnboardingActivity, R.color.check_green)
                          else ContextCompat.getColor(this@OnboardingActivity, R.color.surface_variant)
        setStroke(dp(if (highlighted) 2 else 1), strokeColor)
    }

    private fun buildCard(label: String, tileCount: Int, tileSeed: Int, deleteCount: Int): Demo {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(false)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val arrow = TextView(this).apply {
            text = "▸"
            setTextColor(ContextCompat.getColor(this@OnboardingActivity, R.color.primary))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        val title = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@OnboardingActivity, R.color.on_surface))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val deletePill = TextView(this).apply {
            text = "🗑 Delete"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@OnboardingActivity, android.R.color.white))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(ContextCompat.getColor(this@OnboardingActivity, R.color.delete_red))
            }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
        val pill = TextView(this).apply {
            text = "Hide month"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@OnboardingActivity, R.color.on_primary))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(ContextCompat.getColor(this@OnboardingActivity, R.color.primary))
            }
            visibility = View.INVISIBLE
        }
        headerRow.addView(arrow)
        headerRow.addView(title)
        headerRow.addView(deletePill)
        headerRow.addView(pill)
        card.addView(headerRow)

        // Content-tile grid (6 per row) so each card clearly reads as a pile of items. Each tile is
        // a coloured swatch with a little picture, so it looks like varied content, not blank boxes.
        // Starts collapsed (gone) — the animation "opens" each month before hiding it, like real use.
        val grid = GridLayout(this).apply {
            columnCount = 6
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val cell = dp(34)
        for (i in 0 until tileCount) {
            // Each tile is a frame: the coloured emoji, a (hidden) red delete-wash, and a (hidden)
            // check badge — so selecting-for-deletion reads clearly, like the app's selection.
            val emoji = TextView(this).apply {
                text = TILE_EMOJI[(tileSeed * 2 + i) % TILE_EMOJI.size]
                gravity = Gravity.CENTER
                textSize = 15f
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(TILE_COLORS[(tileSeed + i) % TILE_COLORS.size])
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val scrim = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(0x77B3261E.toInt())   // delete-red @ ~47% over the photo
                }
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val badge = TextView(this).apply {
                text = "✓"
                gravity = Gravity.CENTER
                textSize = 9f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@OnboardingActivity, R.color.delete_red))
                    setStroke(dp(1), Color.WHITE)
                }
                visibility = View.GONE
                val b = dp(15)
                layoutParams = FrameLayout.LayoutParams(b, b, Gravity.TOP or Gravity.END)
            }
            val frame = FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cell; height = cell
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
                addView(emoji); addView(scrim); addView(badge)
            }
            grid.addView(frame)
        }
        card.addView(grid)

        binding.demoStack.addView(card)
        // Delete a fixed count for this month (1, 2 or 3 — assigned per month by the caller), at
        // random positions, leaving at least a couple of tiles behind so the card still reads full.
        val n = deleteCount.coerceIn(1, (tileCount - 2).coerceAtLeast(1))
        val deleteIndices = (0 until tileCount).shuffled().take(n).sorted()
        return Demo(card, pill, grid, arrow, deletePill, deleteIndices, headerRow)
    }

    // ── Animation primitives ─────────────────────────────────────────────────

    private fun highlight(i: Int) {
        demos.getOrNull(i)?.card?.background = cardBackground(true)
    }

    private fun showPill(i: Int) {
        val d = demos.getOrNull(i) ?: return
        d.deletePill.apply { animate().cancel(); visibility = View.GONE }   // never show Hide + Delete together
        d.pill.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(350).start()
        }
    }

    /** Finger taps the month's header, then the month opens. */
    private fun openMonth(i: Int) {
        val d = demos.getOrNull(i) ?: run { expand(i); return }
        tapFinger(d.header) { expand(i) }
    }

    /** "Open" a collapsed month — slide its tile grid down, flip the arrow, and highlight it. */
    private fun expand(i: Int) {
        val d = demos.getOrNull(i) ?: return
        val body = d.body
        d.arrow.text = "▾"
        highlight(i)
        if (body.visibility == View.VISIBLE) return
        body.visibility = View.VISIBLE
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            (d.card.width - d.card.paddingLeft - d.card.paddingRight).coerceAtLeast(1),
            View.MeasureSpec.EXACTLY
        )
        body.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val target = body.measuredHeight
        ValueAnimator.ofInt(0, target).apply {
            duration = 550
            addUpdateListener { a ->
                body.layoutParams = body.layoutParams.apply { height = a.animatedValue as Int }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    body.layoutParams = body.layoutParams.apply {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
            })
            start()
        }
    }

    /** Reveal one tile's selected state — red wash + check badge pop in. */
    private fun revealSelection(frame: FrameLayout) {
        frame.animate().scaleX(0.9f).scaleY(0.9f).setDuration(160).start()
        frame.getChildAt(1)?.apply {   // red delete-wash
            alpha = 0f; visibility = View.VISIBLE
            animate().alpha(1f).setDuration(160).start()
        }
        frame.getChildAt(2)?.apply {   // check badge — pop in
            scaleX = 0f; scaleY = 0f; visibility = View.VISIBLE
            animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(OvershootInterpolator()).start()
        }
    }

    private fun showDeletePill(d: Demo) {
        if (d.deletePill.visibility == View.VISIBLE) return
        d.deletePill.apply { alpha = 0f; visibility = View.VISIBLE; animate().alpha(1f).setDuration(200).start() }
    }

    /** The finger visits each target in turn and taps it (invoking [onTapEach]), then calls [onDone]. */
    private fun fingerTapSequence(targets: List<View>, onTapEach: (Int) -> Unit, onDone: () -> Unit) {
        if (targets.isEmpty()) { onDone(); return }
        val finger = binding.tapFinger
        finger.animate().cancel()
        finger.translationX = 0f; finger.translationY = 0f
        val fHome = IntArray(2); finger.getLocationOnScreen(fHome)
        fun txTo(target: View): Pair<Float, Float> {
            val t = IntArray(2); target.getLocationOnScreen(t)
            return (t[0] + target.width / 2f) - (fHome[0] + finger.width / 2f) to
                   (t[1] + target.height / 2f) - fHome[1] - dp(4)
        }
        finger.apply { alpha = 0f; scaleX = 1f; scaleY = 1f; visibility = View.VISIBLE }
        fun tapAt(j: Int) {
            if (j >= targets.size) {
                // All selected — Delete only proceeds now, so the finger can't be cut off.
                handler.postDelayed({ onDone() }, 250)
                return
            }
            val (tx, ty) = txTo(targets[j])
            finger.animate().alpha(1f).translationX(tx).translationY(ty)
                .setDuration(if (j == 0) 220 else 180)
                .withEndAction {
                    finger.animate().scaleX(0.75f).scaleY(0.75f).setDuration(65).withEndAction {
                        onTapEach(j)
                        finger.animate().scaleX(1f).scaleY(1f).setDuration(65)
                            .withEndAction { handler.postDelayed({ tapAt(j + 1) }, 35) }.start()
                    }.start()
                }.start()
        }
        tapAt(0)
    }

    /**
     * The finger taps each to-be-deleted photo (its check pops on tap; Delete appears on the first),
     * and only AFTER all are selected does it tap Delete (via [onAllSelected]) — so a 3-photo month
     * can never have its 3rd tap cut off by a timer.
     */
    private fun selectForDelete(i: Int, onDone: () -> Unit) {
        val d = demos.getOrNull(i) ?: return
        val targets = d.deleteIndices.filter { it < d.body.childCount }.map { d.body.getChildAt(it) }
        fingerTapSequence(targets, onTapEach = { j ->
            (targets[j] as? FrameLayout)?.let { revealSelection(it) }
            if (j == 0) showDeletePill(d)
        }, onDone = onDone)
    }

    /** A finger moves onto [target] and taps it (synced with the button's own press via [onTap]). */
    private fun tapFinger(target: View, onTap: () -> Unit) {
        val finger = binding.tapFinger
        finger.post { runTapFinger(target, onTap) }
    }

    private fun runTapFinger(target: View, onTap: () -> Unit) {
        val finger = binding.tapFinger
        finger.animate().cancel()
        finger.translationX = 0f; finger.translationY = 0f     // read the finger's untranslated home
        val fLoc = IntArray(2); finger.getLocationOnScreen(fLoc)
        val tLoc = IntArray(2); target.getLocationOnScreen(tLoc)
        // Move the fingertip (top-centre of the glyph) onto the target's centre.
        val dx = (tLoc[0] + target.width / 2f) - (fLoc[0] + finger.width / 2f)
        val dy = (tLoc[1] + target.height / 2f) - fLoc[1] - dp(4)
        finger.apply {
            translationX = dx + dp(12); translationY = dy + dp(16)   // approach from below-right
            alpha = 0f; scaleX = 1.25f; scaleY = 1.25f
            visibility = View.VISIBLE
            animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(dx).translationY(dy).setDuration(300)
                .withEndAction {
                    animate().scaleX(0.78f).scaleY(0.78f).setDuration(110).withEndAction {   // tap down
                        onTap()
                        animate().scaleX(1f).scaleY(1f).setDuration(110).withEndAction {       // release
                            animate().alpha(0f).setStartDelay(250).setDuration(250)
                                .withEndAction { visibility = View.INVISIBLE }.start()
                        }.start()
                    }.start()
                }.start()
        }
    }

    /**
     * Finger taps the Delete button → selected tiles vanish + a floating confirmation pops up →
     * then [onComplete] (chained, so the Hide pill only appears once the delete has fully finished —
     * never overlapping the Delete button).
     */
    private fun confirmDelete(i: Int, onComplete: () -> Unit = {}) {
        val d = demos.getOrNull(i) ?: return
        tapFinger(d.deletePill) {
            d.deletePill.animate().scaleX(0.85f).scaleY(0.85f).setDuration(120)
                .withEndAction {
                    d.deletePill.animate().scaleX(1f).scaleY(1f).setDuration(120)
                        .withEndAction { d.deletePill.visibility = View.GONE }.start()
                }.start()
            val count = d.deleteIndices.count { it < d.body.childCount }
            d.deleteIndices.filter { it < d.body.childCount }.forEach { k ->
                d.body.getChildAt(k).animate()
                    .alpha(0f).scaleX(0.1f).scaleY(0.1f).setStartDelay(200).setDuration(450).start()
            }
            showDeletedToast(count)
            handler.postDelayed({ onComplete() }, 900)   // tiles gone → then reveal the Hide pill
        }
    }

    /** Floating "Deleted N photos you didn't want to keep" — fades in, holds, fades out. */
    private fun showDeletedToast(count: Int) {
        binding.tvDeletedToast.apply {
            text = "Deleted $count photo${if (count == 1) "" else "s"} you didn't want to keep"
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setStartDelay(300).setDuration(300).withEndAction {
                animate().alpha(0f).setStartDelay(2200).setDuration(400)
                    .withEndAction { visibility = View.GONE }.start()
            }.start()
        }
    }

    /** Hide the month: finger taps the "Hide month" pill (press down→up), then the card collapses. */
    private fun hideMonth(i: Int) {
        val pill = demos.getOrNull(i)?.pill
        if (pill == null) { collapse(i); return }
        tapFinger(pill) {
            pill.animate().scaleX(0.85f).scaleY(0.85f).setDuration(120)
                .withEndAction {
                    pill.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    collapse(i)
                }.start()
        }
    }

    private fun collapse(i: Int) {
        val card = demos.getOrNull(i)?.card ?: return
        val start = card.height
        if (start == 0) return
        val anim = ValueAnimator.ofInt(start, 0).apply { duration = 600 }
        anim.addUpdateListener { a ->
            val lp = card.layoutParams
            lp.height = a.animatedValue as Int
            card.layoutParams = lp
            card.alpha = (a.animatedValue as Int).toFloat() / start
        }
        anim.start()
    }

    private fun animateProgress(pct: Int) {
        binding.tvPct.text = "$pct% curated"
        ObjectAnimator.ofInt(binding.pbCurated, "progress", binding.pbCurated.progress, pct).apply {
            duration = 700
            start()
        }
    }

    // ── Step timeline ────────────────────────────────────────────────────────

    private fun buildSteps(): List<Pair<Long, () -> Unit>> {
        val steps = mutableListOf<Pair<Long, () -> Unit>>()
        fun step(wait: Long, action: () -> Unit) { steps.add(wait to action) }
        fun cap(text: String?) { text?.let { binding.tvCaption.text = it } }

        step(1500L) { binding.tvCaption.text = "Here are your months, waiting to be reviewed." }

        // Open the middle month first, then the two ends in random order — so we never open two
        // adjacent months back-to-back except from the middle (avoids a top→bottom / bottom→top
        // sequential feel). Valid orders: [1,0,2] or [1,2,0].
        val order = listOf(1) + listOf(0, 2).shuffled()
        val progress = listOf(34, 67, 100)
        val expandCap  = listOf("Open one and look through its photos.", "Next month — open it up.", "…and the last one.")
        val selectCap  = listOf("Tap the ones you don't want…", "Tap the junk, Delete it…", null)
        val confirmCap = listOf("…and Delete them.", null, null)
        val pillCap    = listOf("Then hide the whole month.", "…then hide it.", null)
        val hideCap    = listOf("It steps out of your way — this app gets cleaner.", null, null)

        order.forEachIndexed { pos, m ->
            step(2000L) { cap(expandCap[pos]); openMonth(m) }
            // Fully chained: finger selects every photo → taps Delete → tiles vanish → THEN the Hide
            // pill appears. Nothing here is on a timer relative to the previous beat, so the Delete
            // and Hide buttons can never overlap regardless of device speed.
            step(5200L) {
                cap(selectCap[pos])
                selectForDelete(m) {
                    cap(confirmCap[pos])
                    confirmDelete(m) {
                        cap(pillCap[pos]); showPill(m)
                    }
                }
            }
            step(if (pos == 0) 2200L else 1800L) { cap(hideCap[pos]); hideMonth(m); animateProgress(progress[pos]) }
        }

        step(900L) {
            binding.tvCaption.text = "All caught up — clean and curated."
            binding.tvCaption.setTextColor(ContextCompat.getColor(this, R.color.check_green))
        }
        return steps
    }

    private fun tick() {
        if (!playing || stepIndex >= steps.size) {
            val finished = stepIndex >= steps.size
            playing = false
            if (finished) onDemoComplete()
            return
        }
        val (wait, action) = steps[stepIndex]
        action()
        stepIndex++
        handler.postDelayed({ tick() }, wait)
    }

    /** The timeline reached the end. In mandatory mode, reveal the close ✕ (or opt out + close). */
    private fun onDemoComplete() {
        demoCompleted = true
        if (replay) return
        if (binding.cbDontShowAgain.isChecked) optOutAndFinish()
        else binding.btnCloseDemo.visibility = View.VISIBLE
    }

    private fun optOutAndFinish() {
        prefs.setDemoDisabled()
        // Persist the opt-out durably so it survives uninstall/reinstall (SharedPreferences doesn't).
        // HomeActivity re-applies it on the next fresh install. Off the main thread (MediaStore/file).
        val app = applicationContext
        Thread { OnboardingMarker.write(app) }.start()
        finish()
    }

    private fun play() {
        if (playing || stepIndex >= steps.size) return
        playing = true
        tick()
    }

    private fun pause() {
        playing = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun rewind() {
        binding.tvCaption.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
        renderInitial()
    }
}
