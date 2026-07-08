package com.anant.mediacurator

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.anant.mediacurator.databinding.ActivityHiddenBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sparse, dedicated screen for bringing hidden months back (Home → "Hidden months").
 *
 * On open it auto-previews the most recently hidden month (else nothing until the user picks one).
 * Previewing shows a month's contents but leaves it HIDDEN — months stay hidden until the user
 * explicitly taps "Unhide this month". No confirmation dialogs; switching/leaving changes nothing.
 *
 * Two view models: [hiddenVm] owns the dropdown / unhide bookkeeping; [galleryVm] is the
 * grid + Share/Move/Delete engine. The grid is driven from galleryVm.flatMediaItems via
 * loadExplicit() — the same explicit-list path the viewer uses, so deletes filter cleanly
 * and never trigger the gallery's full-list reloads.
 */
class HiddenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHiddenBinding
    private val hiddenVm: HiddenViewModel by viewModels()
    private val galleryVm: GalleryViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter

    private var yearList: List<Int> = emptyList()
    private var monthsInSelectedYear: List<MonthGroup> = emptyList()
    private var selectedYear: Int? = null
    // One-shot: on first open, auto-preview the most recently hidden month so the user can see
    // what they last curated without having to remember which month it was. Preview keeps it
    // hidden, so this stays reliable across visits (the month doesn't vanish after a glance).
    private var autoShowAttempted = false

    // Pending move state (mirrors MainActivity's move flow).
    private var pendingMoveItems: List<MediaItem>? = null
    private var pendingMovePath: String? = null
    private var pendingMoveTargetName: String? = null
    private var pendingMoveSkipped: Int = 0

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            galleryVm.onDeletePermissionResult(result.resultCode == Activity.RESULT_OK)
        }

    private val moveLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val items = pendingMoveItems ?: return@registerForActivityResult
                val path = pendingMovePath ?: return@registerForActivityResult
                val targetName = pendingMoveTargetName ?: return@registerForActivityResult
                val skipped = pendingMoveSkipped
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { moveItemsDirect(items, path) }
                    showMoveToast(items.size, skipped, targetName)
                    clearPendingMove()
                    // Keep the selection (like Share) — non-destructive, lets the user keep going.
                    MediaCache.invalidate()   // folder changed — Home/gallery recompute later
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHiddenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Hidden months"

        adapter = GalleryAdapter(
            onMediaClick = { item -> openViewer(item) },
            onMonthHide = { }, onYearToggle = { }, onMonthToggle = { },
            onSubGroupToggle = { },
            onSelectionChanged = { count -> updateSelectionBar(count) }
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
            // Preview the month — it stays hidden. No guard: switching months changes nothing.
            hiddenVm.selectMonth(group.year, group.month)
            binding.autoMonth.setText("", false)
        }
        binding.btnHideAgain.setOnClickListener { hiddenVm.unhideShown() }

        binding.btnShareSelected.setOnClickListener {
            val sel = adapter.getSelectedItems()
            if (sel.isEmpty()) { toast("No items selected") } else shareItems(sel)
        }
        binding.btnMoveSelected.setOnClickListener {
            val sel = adapter.getSelectedItems()
            when {
                sel.isEmpty() -> toast("No items selected")
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> toast("Move requires Android 10+")
                else -> showAlbumPicker(sel)
            }
        }
        binding.btnDeleteSelected.setOnClickListener {
            val sel = adapter.getSelectedItems()
            if (sel.isEmpty()) { toast("No items selected"); return@setOnClickListener }
            exitSelectionMode()
            galleryVm.deleteMedia(sel)
        }
        binding.btnRenameSelected.setOnClickListener {
            adapter.getSelectedItems().singleOrNull()?.let { showRenameDialog(it) }
        }
        binding.btnGallerySelected.setOnClickListener {
            adapter.getSelectedItems().singleOrNull()?.let { openInGallery(it) }
        }

        hiddenVm.hiddenMonths.observe(this) { groups -> renderPickers(groups) }
        hiddenVm.shown.observe(this) { onShownChanged(it) }

        // Grid is driven by the explicit-list engine.
        galleryVm.flatMediaItems.observe(this) { flat ->
            if (hiddenVm.shown.value == null) return@observe
            adapter.submitList(flat.mapIndexed { i, m -> GalleryItem.Media(m, "", i) })
            binding.tvEmpty.isVisible = flat.isEmpty()
            if (flat.isEmpty()) binding.tvEmpty.text = "This month is now empty."
        }
        galleryVm.deletePermissionRequest.observe(this) { sender ->
            sender?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) }
        }
        galleryVm.deletionCompletedEvent.observe(this) { done ->
            if (done == true) toast("Deleted")
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = attemptExit()
        })
    }

    override fun onStart() {
        super.onStart()
        hiddenVm.load()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // Preview is read-only — leaving changes nothing, so no confirmation is needed.
    private fun attemptExit() = finish()

    // ── Pickers ──────────────────────────────────────────────────────────────────

    private fun renderPickers(groups: List<MonthGroup>) {
        // On first open, auto-preview the most recently hidden month (if it's still hidden), so the
        // user lands on what they last curated instead of a blank screen. One-shot — afterwards we
        // don't fight manual navigation. Preview keeps the month hidden, so it stays in the list/
        // dropdowns below (we fall through rather than returning).
        if (!autoShowAttempted && hiddenVm.shown.value == null && groups.isNotEmpty()) {
            autoShowAttempted = true
            val target = groups.find { it.key == hiddenVm.lastHiddenMonthKey() }
            if (target != null) {
                selectedYear = target.year
                hiddenVm.selectMonth(target.year, target.month)
            }
        }

        binding.menuYear.isEnabled = groups.isNotEmpty()

        yearList = groups.map { it.year }.distinct().sortedDescending()
        val countByYear = groups.groupBy { it.year }.mapValues { (_, g) -> g.sumOf { it.items.size } }
        binding.autoYear.setSimpleItems(
            yearList.map { y -> "$y (${countByYear[y] ?: 0})" }.toTypedArray()
        )

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

        // Refresh the empty-state wording now that the hidden-month list has arrived (load()
        // is async, so the first shown-observer pass ran before we knew any months existed).
        if (hiddenVm.shown.value == null) {
            binding.tvEmpty.isVisible = true
            binding.tvEmpty.text = if (groups.isNotEmpty())
                "Pick a year and month above\nto view it."
            else
                "You haven't hidden any months yet.\nMonths you hide will appear here to bring back."
        }
    }

    private fun populateMonths(year: Int) {
        monthsInSelectedYear = (hiddenVm.hiddenMonths.value ?: emptyList())
            .filter { it.year == year }
            .sortedBy { it.month }
        val items = monthsInSelectedYear.map { g ->
            "${g.label.substringBefore(" ")} · ${g.items.size} item${if (g.items.size > 1) "s" else ""}"
        }
        binding.autoMonth.setSimpleItems(items.toTypedArray())
        binding.menuMonth.isEnabled = items.isNotEmpty()
    }

    /** Shown-month changed: drive the grid via the explicit-list engine, toggle the banner. */
    private fun onShownChanged(shown: HiddenViewModel.Shown?) {
        exitSelectionMode()
        if (shown != null) {
            binding.shownBar.isVisible = true
            binding.tvShownLabel.text = "${shown.label} · still hidden"
            binding.tvEmpty.isVisible = false
            galleryVm.loadExplicit(shown.items.map { it.mediaItem.id }.toLongArray())
        } else {
            binding.shownBar.isVisible = false
            adapter.submitList(emptyList())
            val hasHidden = (hiddenVm.hiddenMonths.value?.isNotEmpty()) == true
            binding.tvEmpty.text = if (hasHidden)
                "Pick a year and month above\nto view it."
            else
                "You haven't hidden any months yet.\nMonths you hide will appear here to bring back."
            binding.tvEmpty.isVisible = true
        }
    }

    // ── Selection actions ────────────────────────────────────────────────────────

    private fun updateSelectionBar(count: Int) {
        if (count > 0) {
            binding.selectionBar.isVisible = true
            val bytes = adapter.getSelectedItems().sumOf { it.size }
            binding.tvSelectionCount.text = "$count selected · ${GalleryAdapter.fmtBytes(bytes)}"
            val single = count == 1
            binding.btnRenameSelected.isVisible = single
            binding.btnGallerySelected.isVisible = single
        } else {
            binding.selectionBar.isVisible = false
        }
    }

    /** Rename dialog (base name only; extension preserved), then rename via the view-model. */
    private fun showRenameDialog(item: MediaItem) {
        val fullName = item.displayName
        val dotIdx = fullName.lastIndexOf('.')
        val baseName = if (dotIdx > 0) fullName.substring(0, dotIdx) else fullName
        val ext = if (dotIdx > 0) fullName.substring(dotIdx) else ""
        val input = android.widget.EditText(this).apply {
            setText(baseName); setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val wrapper = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0); addView(input)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Rename")
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rename") { _, _ ->
                val newBase = input.text.toString().trim()
                if (newBase.isNotEmpty()) { exitSelectionMode(); galleryVm.initiateRename(item, newBase + ext) }
            }
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.post { input.requestFocus(); input.selectAll() }
    }

    /** Hand the photo to the phone's own gallery app. */
    private fun openInGallery(item: MediaItem) {
        val mime = when (item.type) {
            MediaType.IMAGE -> "image/*"
            MediaType.VIDEO -> "video/*"
            MediaType.AUDIO -> "audio/*"
            MediaType.PDF   -> "application/pdf"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(item.uri), mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) { toast("No gallery app found") }
    }

    private fun exitSelectionMode() {
        if (::adapter.isInitialized) adapter.exitSelectionMode()
        binding.selectionBar.isVisible = false
    }

    private fun shareItems(items: List<MediaItem>) {
        val uris = ArrayList(items.map { Uri.parse(it.uri) })
        val types = items.map { it.type }.toSet()
        val mime = when {
            types == setOf(MediaType.IMAGE) -> "image/*"
            types == setOf(MediaType.VIDEO) -> "video/*"
            types == setOf(MediaType.AUDIO) -> "audio/*"
            types == setOf(MediaType.PDF)   -> "application/pdf"
            else                            -> "*/*"
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = mime; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, "Share ${uris.size} item${if (uris.size > 1) "s" else ""}"))
        } catch (e: Exception) {
            toast("No app available to share these files")
        }
    }

    // ── Move to album (mirrors MainActivity) ─────────────────────────────────────

    private fun showAlbumPicker(items: List<MediaItem>) {
        lifecycleScope.launch {
            val albums = fetchAlbums()
            if (albums.isEmpty()) { toast("No albums found"); return@launch }
            val names = albums.keys.toTypedArray()
            AlertDialog.Builder(this@HiddenActivity)
                .setTitle("Switch Album")
                .setItems(names) { _, which ->
                    val targetName = names[which]
                    val targetPath = albums[targetName] ?: return@setItems
                    moveToAlbum(items, targetName, targetPath)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private suspend fun fetchAlbums(): LinkedHashMap<String, String> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext result
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pathCol   = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val name = cursor.getString(bucketCol) ?: continue
                val path = cursor.getString(pathCol)   ?: continue
                if (!result.containsKey(name)) result[name] = path
            }
        }
        result
    }

    private fun moveToAlbum(items: List<MediaItem>, targetName: String, targetPath: String) {
        val toMove = items.filter {
            it.relativePath.trimEnd('/').lowercase() != targetPath.trimEnd('/').lowercase()
        }
        val skipped = items.size - toMove.size
        if (toMove.isEmpty()) { toast("All items already in $targetName"); return }

        pendingMoveItems = toMove
        pendingMovePath = targetPath
        pendingMoveTargetName = targetName
        pendingMoveSkipped = skipped

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = toMove.map { Uri.parse(it.uri) }
                val pi = MediaStore.createWriteRequest(contentResolver, uris)
                moveLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                toast("Move failed: ${e.message}")
                clearPendingMove()
            }
        } else {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { moveItemsDirect(toMove, targetPath) }
                showMoveToast(toMove.size, skipped, targetName)
                clearPendingMove()
                MediaCache.invalidate()
            }
        }
    }

    private fun moveItemsDirect(items: List<MediaItem>, targetPath: String) {
        for (item in items) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, targetPath)
                }
                contentResolver.update(Uri.parse(item.uri), values, null, null)
            } catch (e: Exception) {
                android.util.Log.e("HiddenMove", "Failed to move ${item.displayName}", e)
            }
        }
    }

    private fun showMoveToast(moved: Int, skipped: Int, targetName: String) {
        toast(
            if (skipped > 0) "Switched $moved · $skipped already in $targetName"
            else "Switched $moved item${if (moved == 1) "" else "s"} to $targetName"
        )
    }

    private fun clearPendingMove() {
        pendingMoveItems = null
        pendingMovePath = null
        pendingMoveTargetName = null
        pendingMoveSkipped = 0
    }

    // ── Viewer / menu ────────────────────────────────────────────────────────────

    private fun openViewer(item: MediaItem) {
        if (item.type == MediaType.PDF) {
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(item.uri), "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { startActivity(Intent.createChooser(open, "Open PDF with")) } catch (_: Exception) {}
            return
        }
        val ids = (galleryVm.flatMediaItems.value ?: emptyList()).map { it.id }.toLongArray()
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
