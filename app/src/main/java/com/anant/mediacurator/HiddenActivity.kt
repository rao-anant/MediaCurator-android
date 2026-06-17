package com.anant.mediacurator

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import com.anant.mediacurator.databinding.ActivityHiddenBinding

/**
 * Sparse, dedicated screen for bringing hidden months back (Home → "Hidden months").
 *
 * Nothing is shown until the user picks a month. Picking it UNHIDES it immediately (Option A)
 * and displays it for a last review; "Hide again" puts it back. The gallery no longer hosts an
 * unhide panel — this is the single place to manage hidden months.
 */
class HiddenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHiddenBinding
    private val viewModel: HiddenViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter

    private var yearList: List<Int> = emptyList()
    private var monthsInSelectedYear: List<MonthGroup> = emptyList()
    private var selectedYear: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHiddenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Hidden months"

        adapter = GalleryAdapter(
            onMediaClick = { item -> openViewer(item) },
            onMonthHide = { }, onYearToggle = { }, onMonthToggle = { },
            onSubGroupToggle = { }, onSelectionChanged = { }
        )
        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.adapter = adapter

        binding.autoYear.setOnItemClickListener { _, _, position, _ ->
            val year = yearList.getOrNull(position) ?: return@setOnItemClickListener
            selectedYear = year
            populateMonths(year)
            binding.autoMonth.setText("", false)
            binding.autoMonth.showDropDown()
        }
        binding.autoMonth.setOnItemClickListener { _, _, position, _ ->
            val group = monthsInSelectedYear.getOrNull(position) ?: return@setOnItemClickListener
            val cur = viewModel.shown.value
            if (cur != null && cur.year == group.year && cur.month == group.month) {
                binding.autoMonth.setText("", false); return@setOnItemClickListener   // already showing it
            }
            // Switching to another month: confirm the current one first (it was unhidden the
            // moment they picked it), so a forgotten "Hide again" doesn't silently lose it.
            confirmShownThen {
                viewModel.selectMonth(group.year, group.month)
                // Keep the year selected; renderPickers refreshes its month list (minus this
                // one). Just clear the month field so it's ready for the next pick.
                binding.autoMonth.setText("", false)
            }
        }
        binding.btnHideAgain.setOnClickListener { viewModel.hideShown() }

        viewModel.hiddenMonths.observe(this) { groups -> renderPickers(groups) }
        viewModel.shown.observe(this) { renderShown(it) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = attemptExit()
        })
    }

    /** Leaving the screen while a just-unhidden month is on display → confirm intent first. */
    private fun attemptExit() = confirmShownThen { finish() }

    /**
     * If a month is currently shown (it was unhidden the moment it was picked) and not yet
     * re-hidden, ask whether to hide it again or keep it unhidden, THEN run [onResolved].
     * Defaults to "Hide again" so a forgotten re-hide doesn't quietly undo their curation.
     * Dismissing the dialog (back / outside tap) cancels — [onResolved] does not run.
     * No prompt when nothing is shown.
     */
    private fun confirmShownThen(onResolved: () -> Unit) {
        val s = viewModel.shown.value
        if (s == null) { onResolved(); return }
        AlertDialog.Builder(this)
            .setTitle("Keep \"${s.label}\" unhidden?")
            .setMessage("You unhid it but didn't hide it again. Hide it again to keep it curated, or keep it unhidden in your gallery.")
            .setPositiveButton("Hide again") { _, _ -> viewModel.hideShown(); onResolved() }
            .setNegativeButton("Keep unhidden") { _, _ -> onResolved() }
            .show()
    }

    override fun onStart() {
        super.onStart()
        viewModel.load()
    }

    override fun onSupportNavigateUp(): Boolean { attemptExit(); return true }

    private fun renderPickers(groups: List<MonthGroup>) {
        binding.menuYear.isEnabled = groups.isNotEmpty()

        yearList = groups.map { it.year }.distinct().sortedDescending()
        // Show the total hidden items per year, e.g. "2022 (156)".
        val countByYear = groups.groupBy { it.year }.mapValues { (_, g) -> g.sumOf { it.items.size } }
        binding.autoYear.setSimpleItems(
            yearList.map { y -> "$y (${countByYear[y] ?: 0})" }.toTypedArray()
        )

        // Preserve the chosen year across reloads so the user can keep picking months from it;
        // refresh its month list (the just-unhidden one is now gone). Drop it if the year has
        // no hidden months left.
        val sel = selectedYear
        if (sel != null && sel in yearList) {
            binding.autoYear.setText("$sel (${countByYear[sel] ?: 0})", false)
            populateMonths(sel)
        } else {
            selectedYear = null
            binding.autoYear.setText("", false)
            binding.autoMonth.setText("", false)
            monthsInSelectedYear = emptyList()
            binding.menuMonth.isEnabled = false
        }

        renderShown(viewModel.shown.value)   // refresh empty-state wording
    }

    private fun populateMonths(year: Int) {
        monthsInSelectedYear = (viewModel.hiddenMonths.value ?: emptyList())
            .filter { it.year == year }
            .sortedBy { it.month }
        val items = monthsInSelectedYear.map { g ->
            "${g.label.substringBefore(" ")} · ${g.items.size} item${if (g.items.size > 1) "s" else ""}"
        }
        binding.autoMonth.setSimpleItems(items.toTypedArray())
        binding.menuMonth.isEnabled = items.isNotEmpty()
    }

    private fun renderShown(shown: HiddenViewModel.Shown?) {
        if (shown != null) {
            binding.shownBar.isVisible = true
            binding.tvShownLabel.text = "${shown.label} · restored"
            binding.tvEmpty.isVisible = false
            adapter.submitList(shown.items)
        } else {
            binding.shownBar.isVisible = false
            adapter.submitList(emptyList())
            val hasHidden = (viewModel.hiddenMonths.value?.isNotEmpty()) == true
            binding.tvEmpty.text = if (hasHidden)
                "Pick a year and month above\nto bring it back."
            else
                "You haven't hidden any months yet.\nMonths you hide will appear here to bring back."
            binding.tvEmpty.isVisible = true
        }
    }

    private fun openViewer(item: MediaItem) {
        if (item.type == MediaType.PDF) {
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(item.uri), "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { startActivity(Intent.createChooser(open, "Open PDF with")) } catch (_: Exception) {}
            return
        }
        // Page through exactly the items of the month currently shown.
        val ids = (viewModel.shown.value?.items ?: emptyList()).map { it.mediaItem.id }.toLongArray()
        startActivity(Intent(this, MediaViewerActivity::class.java)
            .putExtra(MediaViewerActivity.EXTRA_START_ID, item.id)
            .putExtra(MediaViewerActivity.EXTRA_ID_LIST, ids))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_search, menu)   // Help + Settings (shared app-level menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_help     -> { startActivity(Intent(this, HelpActivity::class.java)); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }
}
