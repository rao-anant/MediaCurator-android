package com.anant.mediacurator

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import com.anant.mediacurator.databinding.ActivityTrashBinding

/**
 * The recycle bin (Home → "Trash"). Lists everything currently in trash; multi-select to
 * Restore or Delete forever, or Empty the lot. Items also auto-purge after ~30 days (OS) .
 */
class TrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrashBinding
    private val viewModel: TrashViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Trash"

        adapter = GalleryAdapter(
            onMediaClick = { }, onMonthHide = { }, onYearToggle = { }, onMonthToggle = { },
            onSubGroupToggle = { },
            onSelectionChanged = { count -> updateSelectionBar(count) }
        )
        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.adapter = adapter

        binding.btnRestore.setOnClickListener {
            val sel = adapter.getSelectedItems()
            if (sel.isEmpty()) { toast("Nothing selected"); return@setOnClickListener }
            exitSelectionMode()
            viewModel.restore(sel)
            toast("Restoring ${sel.size}…")
        }
        binding.btnDeleteForever.setOnClickListener {
            val sel = adapter.getSelectedItems()
            if (sel.isEmpty()) { toast("Nothing selected"); return@setOnClickListener }
            confirmDeleteForever(sel.size) { exitSelectionMode(); viewModel.deleteForever(sel) }
        }

        viewModel.items.observe(this) { items ->
            adapter.submitList(items.mapIndexed { i, m -> GalleryItem.Media(m, "", i) })
            binding.tvEmpty.isVisible = items.isEmpty()
            binding.tvNote.isVisible = items.isNotEmpty()
            invalidateOptionsMenu()
            exitSelectionMode()
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.load()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun updateSelectionBar(count: Int) {
        if (count > 0) {
            binding.selectionBar.isVisible = true
            val bytes = adapter.getSelectedItems().sumOf { it.size }
            binding.tvSelectionCount.text = "$count selected · ${GalleryAdapter.fmtBytes(bytes)}"
        } else {
            binding.selectionBar.isVisible = false
        }
        invalidateOptionsMenu()   // hide "Empty Trash" while a selection is active
    }

    private fun exitSelectionMode() {
        if (::adapter.isInitialized) adapter.exitSelectionMode()
        binding.selectionBar.isVisible = false
    }

    private fun confirmDeleteForever(count: Int, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Delete forever?")
            .setMessage("$count item${if (count == 1) "" else "s"} will be permanently deleted and cannot be recovered.")
            .setPositiveButton("Delete forever") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_trash, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasItems = viewModel.items.value?.isNotEmpty() == true
        menu.findItem(R.id.action_empty_trash)?.isVisible = hasItems && !adapter.selectionMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_empty_trash -> {
            confirmDeleteForever(viewModel.items.value?.size ?: 0) { viewModel.empty() }
            true
        }
        R.id.action_help     -> { startActivity(Intent(this, HelpActivity::class.java)); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
