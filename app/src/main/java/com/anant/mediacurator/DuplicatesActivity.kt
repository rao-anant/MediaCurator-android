package com.anant.mediacurator

import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anant.mediacurator.databinding.ActivityDuplicatesBinding
import com.anant.mediacurator.databinding.ItemDuplicateGroupBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners

class DuplicatesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDuplicatesBinding
    private val viewModel: DuplicatesViewModel by viewModels()
    private lateinit var adapter: DuplicateGroupsAdapter

    private val deletePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onDeletePermissionResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDuplicatesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Insets handled by android:fitsSystemWindows on the root (toolbar below the status
        // bar, bottom bar above the nav bar) — consistent with Home/Search/Gallery.

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Duplicate Photos & Videos"

        adapter = DuplicateGroupsAdapter(
            onKeepToggle = { groupIdx, itemIdx -> viewModel.setKeepIndex(groupIdx, itemIdx) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        observeViewModel()

        if (savedInstanceState == null) viewModel.loadDuplicates()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_duplicates, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_refresh_dupes) {
            viewModel.loadDuplicates()
            return true
        }
        if (item.itemId == R.id.action_dupes_stats) {
            StatsDialog.present(this)
            return true
        }
        if (item.itemId == R.id.action_help) {
            startActivity(android.content.Intent(this, HelpActivity::class.java))
            return true
        }
        if (item.itemId == R.id.action_settings) {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.isVisible = loading
            binding.recyclerView.isVisible = !loading
            updateEmptyState()
        }

        viewModel.indexedCount.observe(this) { count ->
            if (count > 0) supportActionBar?.subtitle = "$count files indexed"
        }

        viewModel.groups.observe(this) { groups ->
            adapter.submitGroups(groups)
            updateBottomBar(groups)
            updateEmptyState()
        }

        viewModel.deletionResult.observe(this) { count ->
            count ?: return@observe
            // Soft-delete is silent (the duplicates are confirmed via a dialog first, and go to
            // a recoverable Trash). Only surface the no-op case.
            if (count == 0) Toast.makeText(this, "Nothing deleted", Toast.LENGTH_SHORT).show()
            viewModel.clearDeletionResult()
        }

        viewModel.deletePermissionRequest.observe(this) { sender ->
            sender ?: return@observe
            deletePermissionLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    /**
     * Show/hide the empty-state message.  Called from BOTH the groups and isLoading
     * observers — their delivery order isn't guaranteed, so whichever arrives last
     * makes the final call.
     */
    private fun updateEmptyState() {
        val doneLoading = viewModel.isLoading.value == false
        val groups = viewModel.groups.value ?: emptyList()
        binding.tvEmpty.isVisible = groups.isEmpty() && doneLoading
        val indexed = viewModel.indexedCount.value ?: 0
        binding.tvEmpty.text = if (indexed == 0)
            "No files indexed yet.\nHashing runs after PDF indexing completes."
        else
            "No exact duplicates found in $indexed indexed files 🎉"
    }

    private fun updateBottomBar(groups: List<DuplicateGroup>) {
        val toDeleteCount = groups.sumOf { g -> g.items.size - 1 }
        val reclaimable   = groups.sumOf { it.reclaimableBytes }

        // No duplicates → hide the whole action bar (the centre empty-state says it all).
        if (toDeleteCount == 0) {
            binding.bottomBar.isVisible = false
            return
        }
        binding.bottomBar.isVisible = true
        binding.tvStats.text = "$toDeleteCount to delete · ${Formatter.formatShortFileSize(this, reclaimable)} reclaimable"
        binding.btnDelete.isEnabled = true

        binding.btnDelete.setOnClickListener {
            val count  = groups.sumOf { it.items.size - 1 }
            val size   = Formatter.formatShortFileSize(this, groups.sumOf { it.reclaimableBytes })
            AlertDialog.Builder(this)
                .setTitle("Delete duplicates?")
                .setMessage("$count files ($size) will be permanently deleted. The kept copy in each group will not be affected.")
                .setPositiveButton("Delete") { _, _ -> viewModel.deleteMarked() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class DuplicateGroupsAdapter(
        private val onKeepToggle: (groupIdx: Int, itemIdx: Int) -> Unit
    ) : RecyclerView.Adapter<DuplicateGroupsAdapter.GroupVH>() {

        private var groups: List<DuplicateGroup> = emptyList()

        fun submitGroups(newGroups: List<DuplicateGroup>) {
            groups = newGroups
            notifyDataSetChanged()
        }

        override fun getItemCount() = groups.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupVH {
            val binding = ItemDuplicateGroupBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return GroupVH(binding)
        }

        override fun onBindViewHolder(holder: GroupVH, position: Int) {
            holder.bind(groups[position], position)
        }

        inner class GroupVH(private val b: ItemDuplicateGroupBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(group: DuplicateGroup, groupIdx: Int) {
                val count     = group.items.size
                val reclaim   = Formatter.formatShortFileSize(b.root.context, group.reclaimableBytes)
                b.tvHeader.text = "$count copies · $reclaim reclaimable"

                b.llThumbs.removeAllViews()

                group.items.forEachIndexed { itemIdx, item ->
                    val thumbView = LayoutInflater.from(b.root.context)
                        .inflate(R.layout.item_duplicate_thumb, b.llThumbs, false)

                    val ivThumb  = thumbView.findViewById<ImageView>(R.id.ivThumb)
                    val tvBadge  = thumbView.findViewById<TextView>(R.id.tvBadge)
                    val tvSize   = thumbView.findViewById<TextView>(R.id.tvSize)
                    val tvFolder = thumbView.findViewById<TextView>(R.id.tvFolder)

                    // Thumbnail image
                    val cornerPx = (b.root.context.resources.displayMetrics.density * 4).toInt()
                    Glide.with(b.root.context)
                        .load(Uri.parse(item.uri))
                        .override(220)
                        .transform(CenterCrop(), RoundedCorners(cornerPx))
                        .into(ivThumb)

                    // Keep / delete badge
                    val isKeep = (itemIdx == group.keepIndex)
                    tvBadge.text  = if (isKeep) "✓" else "✕"
                    tvBadge.setBackgroundResource(
                        if (isKeep) R.drawable.badge_keep else R.drawable.badge_delete
                    )

                    // Labels
                    tvSize.text   = Formatter.formatShortFileSize(b.root.context, item.size)
                    tvFolder.text = friendlyFolder(item.relativePath)

                    // Tap to toggle — can't un-keep the only kept item
                    thumbView.setOnClickListener {
                        if (!isKeep) onKeepToggle(groupIdx, itemIdx)
                    }

                    // Dim items marked for deletion
                    thumbView.alpha = if (isKeep) 1.0f else 0.5f

                    b.llThumbs.addView(thumbView)
                }
            }

            private fun friendlyFolder(relativePath: String): String {
                if (relativePath.isBlank()) return ""
                val parts = relativePath.trimEnd('/').split('/')
                return parts.lastOrNull { it.isNotBlank() } ?: ""
            }
        }
    }
}
