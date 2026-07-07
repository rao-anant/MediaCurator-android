package com.anant.mediacurator

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import com.anant.mediacurator.databinding.ActivitySearchBinding

/**
 * Dedicated search spoke (reached from the Home hub).
 *
 * A standalone screen — just a search bar + results — so there's no gallery to flash on
 * enter/exit, and Back returns straight to Home. Shows an empty prompt until the user types.
 * Tapping a result opens the full-screen viewer (which finds the item by id and loads itself).
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val viewModel: SearchViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter

    private var showingPrompt = true   // no query yet → the place chips may show
    private var hasPlaces = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = GalleryAdapter(
            onMediaClick      = { item -> openViewer(item) },
            onMonthHide       = { },
            onYearToggle      = { },
            onMonthToggle     = { },
            onSubGroupToggle  = { },
            onSelectionChanged = { }
        )
        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.adapter = adapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.searchView.clearFocus(); return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.search(newText.orEmpty()); return true
            }
        })

        viewModel.results.observe(this) { res ->
            when {
                res == null -> {                       // no query yet → prompt
                    adapter.submitList(emptyList())
                    binding.tvEmpty.text =
                        if (PreferencesManager(this).isPlaceSearchEnabled())
                            "Search file names, PDF text,\nand places"
                        else "Search file names\nand text inside PDFs"
                    binding.tvEmpty.isVisible = true
                }
                res.isEmpty() -> {                     // searched, nothing matched
                    adapter.submitList(emptyList())
                    binding.tvEmpty.text = "No results — try different keywords\nor check for typos"
                    binding.tvEmpty.isVisible = true
                }
                else -> {
                    binding.tvEmpty.isVisible = false
                    adapter.submitList(res)
                }
            }
            showingPrompt = res == null
            updatePromptExtras()
        }

        // Browseable place list → tappable chips shown under the empty-state prompt.
        viewModel.places.observe(this) { renderPlaces(it) }
        viewModel.loadPlaces()

        binding.btnStats.setOnClickListener { StatsDialog.present(this) }

        // Focus the field and pop the keyboard on open.
        binding.searchView.isIconified = false
        binding.searchView.requestFocus()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    /** Build the place chips (top cities by photo count). Tapping one runs that search. */
    private fun renderPlaces(places: List<PlaceCount>) {
        val group = binding.chipPlaces
        group.removeAllViews()
        for (p in places.take(24)) {
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = "${p.name} · ${p.count}"
                isClickable = true
                isCheckable = false
                setOnClickListener { binding.searchView.setQuery(p.name, true) }
            }
            group.addView(chip)
        }
        hasPlaces = group.childCount > 0
        updatePromptExtras()
    }

    /** Place chips + label only when the field is empty (the prompt) and we have places. */
    private fun updatePromptExtras() {
        val show = showingPrompt && hasPlaces
        binding.tvPlacesLabel.isVisible = show
        binding.chipPlaces.isVisible = show
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_search, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = when (item.itemId) {
        R.id.action_help     -> { startActivity(Intent(this, HelpActivity::class.java)); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
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
        // Page through the current result set, in result order.
        val ids = (viewModel.results.value ?: emptyList()).map { it.mediaItem.id }.toLongArray()
        startActivity(Intent(this, MediaViewerActivity::class.java)
            .putExtra(MediaViewerActivity.EXTRA_START_ID, item.id)
            .putExtra(MediaViewerActivity.EXTRA_ID_LIST, ids))
    }
}
