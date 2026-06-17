package com.anant.mediacurator

import android.content.Intent
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    binding.tvEmpty.text = "Search file names\nand text inside PDFs"
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
        }

        binding.btnStats.setOnClickListener { StatsDialog.present(this) }

        // Focus the field and pop the keyboard on open.
        binding.searchView.isIconified = false
        binding.searchView.requestFocus()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

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
        startActivity(Intent(this, MediaViewerActivity::class.java)
            .putExtra(MediaViewerActivity.EXTRA_START_ID, item.id))
    }
}
