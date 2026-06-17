package com.anant.mediacurator

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import android.content.res.ColorStateList
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anant.mediacurator.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter
    
    private var offeredOneClickDelete = false
    private var offeredAllFilesAccess = false  // show the PDF-access prompt only once per session
    private var launchedAllFilesSettings = false  // Grant tapped → Settings open → resume handled by launcher
    private var allFilesPromptActive = false   // true while the PDF-access prompt/Settings is mid-flow
    private var awaitingAutoRestore = false     // true from auto-restore check start until it settles
    private var pendingResumeKey: String? = null  // Home "Resume" deep-link: scroll here once its header appears
    private var inSearchMode = false               // SearchView expanded → show prompt, not the gallery
    private var launchedForSearch = false          // opened from Home's Search card → back exits to Home
    private var pendingOpenSearch = false          // Home "Search" card: expand search once the menu exists
    private var pendingShowStats = false           // Home "Hidden & stats" card: show stats once loaded

    // Jump toggle: remembers the two positions so the swap FAB can bounce back and forth
    private var jumpAMonthKey: String? = null  // where we came FROM
    private var jumpBMonthKey: String? = null  // where we jumped TO

    // Pending move state — held across the createWriteRequest consent dialog
    private var pendingMoveItems:       List<MediaItem>? = null
    private var pendingMovePath:        String?          = null
    private var pendingMoveTargetName:  String?          = null
    private var pendingMoveSkipped:     Int              = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                checkAllFilesAccessAndLoad()
            } else {
                showToast("Storage permission is required to view your media")
            }
        }


    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.onDeletePermissionResult(result.resultCode == Activity.RESULT_OK)
        }

    private val moveLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val items      = pendingMoveItems      ?: return@registerForActivityResult
                val path       = pendingMovePath       ?: return@registerForActivityResult
                val targetName = pendingMoveTargetName ?: return@registerForActivityResult
                val skipped    = pendingMoveSkipped
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { moveItemsDirect(items, path) }
                    showMoveToast(items.size, skipped, targetName)
                    clearPendingMove()
                    exitSelectionMode()
                    viewModel.loadMedia(forceRefresh = true)
                }
            }
        }

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // User returned from the "All files access" settings screen.
            // If prefs are still empty, re-run the restore check now that we have file access
            // (the first attempt at init runs before this permission is granted).
            // Otherwise just reload so PDFs appear.
            if (hasAllFilesPermission() && viewModel.prefs.getDoneMonths().isEmpty()) {
                // Block onboarding for the whole async check — a "Backup found" prompt may follow.
                awaitingAutoRestore = true
                viewModel.checkAndAutoRestore()
            } else {
                viewModel.loadMedia()
            }
            // If photo hashing was deferred waiting for this permission, start it now.
            viewModel.resumeDeferredHashing()
            // Now that all-files access may be granted, retry restoring the lifetime deletion
            // counter from its Downloads mirror (the startup attempt ran before this permission).
            DeletionStatsStore.getInstance(this).ensureRestored()
            // PDF-access decision is now resolved — onboarding may proceed.
            allFilesPromptActive = false
            tryShowOnboarding()
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            importHiddenMonths(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.btnSort.setOnClickListener { showSortPopup() }
        // When opened from the Home hub, show an Up arrow back to it (consistent with the
        // other spokes like Duplicates). Once Home becomes the launcher this is always true.
        if (intent.getBooleanExtra(HomeActivity.EXTRA_FROM_HOME, false)) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }
        // Deep-links from the Home hub — applied before the first load below.
        intent.getStringExtra(HomeActivity.EXTRA_SORT)?.let { name ->
            runCatching { viewModel.setInitialSort(SortMode.valueOf(name)) }
        }
        intent.getStringExtra(HomeActivity.EXTRA_RESUME_MONTH_KEY)?.let { key ->
            pendingResumeKey = key
            viewModel.requestOpenAtMonth(key)
        }
        pendingOpenSearch = intent.getBooleanExtra(HomeActivity.EXTRA_OPEN_SEARCH, false)
        launchedForSearch = pendingOpenSearch   // came from Home's Search card → exit to Home on close
        pendingShowStats  = intent.getBooleanExtra(HomeActivity.EXTRA_SHOW_STATS, false)
        // Pad the bottom bar and FAB so they clear the gesture/nav-button bar on Android 15+
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.selectionBar.updatePadding(bottom = navBar.bottom + resources.getDimensionPixelSize(R.dimen.selection_bar_padding_v))
            (binding.fabScrollToTop.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = navBar.bottom + resources.getDimensionPixelSize(R.dimen.fab_margin)
                binding.fabScrollToTop.layoutParams = it
            }
            insets
        }
        setupRecyclerView()
        setupSelectionBar()
        setupRestoreSpinners()
        setupFilterChips()
        setupScrollToTop()
        observeViewModel()
        requestPermissionsIfNeeded()
        // Pre-warm the AlertDialog machinery so the first real dialog opens instantly.
        // Android inflates dialog windows lazily; the first one takes ~300-600 ms.
        window.decorView.post {
            androidx.appcompat.app.AlertDialog.Builder(this).create().also { it.show(); it.dismiss() }
        }
    }

    private fun setupScrollToTop() {
        binding.fabScrollToTop.setOnClickListener {
            binding.recyclerView.scrollToPosition(0)
            binding.appBarLayout.setExpanded(true, true)
        }

        binding.fabJumpSwap.setOnClickListener {
            val target = jumpAMonthKey ?: return@setOnClickListener
            // Swap A and B so next tap goes back the other way
            jumpAMonthKey = jumpBMonthKey
            jumpBMonthKey = target
            val pos = adapter.currentList.indexOfFirst {
                it is GalleryItem.Header && it.monthKey == target
            }
            if (pos >= 0) {
                (binding.recyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(pos, 0)
            }
        }

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: 0
                binding.fabScrollToTop.isVisible = firstVisible > 15
                updateStickyHeader(firstVisible)
            }
        })
    }

    private fun setupFilterChips() {
        binding.cardPhoto.tvStatLabel.text = "📷 Photos"
        binding.cardVideo.tvStatLabel.text = "🎬 Videos"
        binding.cardAudio.tvStatLabel.text = "🎵 Audio"
        binding.cardPdf.tvStatLabel.text   = "📄 PDFs"

        val photoOn = viewModel.includePhoto.value ?: true
        val videoOn = viewModel.includeVideo.value ?: true
        val audioOn = viewModel.includeAudio.value ?: true
        val pdfOn   = viewModel.includePdf.value   ?: true
        updateStatCard(binding.cardPhoto, photoOn)
        updateStatCard(binding.cardVideo, videoOn)
        updateStatCard(binding.cardAudio, audioOn)
        updateStatCard(binding.cardPdf,   pdfOn)

        fun activeCount() = listOf(
            viewModel.includePhoto.value ?: true,
            viewModel.includeVideo.value ?: true,
            viewModel.includeAudio.value ?: true,
            viewModel.includePdf.value   ?: true
        ).count { it }

        binding.cardPhoto.root.setOnClickListener {
            val cur = viewModel.includePhoto.value ?: true
            if (cur && activeCount() <= 1) { showToast("At least one filter must be active"); return@setOnClickListener }
            val next = !cur; updateStatCard(binding.cardPhoto, next); viewModel.setIncludePhoto(next)
        }
        binding.cardVideo.root.setOnClickListener {
            val cur = viewModel.includeVideo.value ?: true
            if (cur && activeCount() <= 1) { showToast("At least one filter must be active"); return@setOnClickListener }
            val next = !cur; updateStatCard(binding.cardVideo, next); viewModel.setIncludeVideo(next)
        }
        binding.cardAudio.root.setOnClickListener {
            val cur = viewModel.includeAudio.value ?: true
            if (cur && activeCount() <= 1) { showToast("At least one filter must be active"); return@setOnClickListener }
            val next = !cur; updateStatCard(binding.cardAudio, next); viewModel.setIncludeAudio(next)
        }
        binding.cardPdf.root.setOnClickListener {
            val cur = viewModel.includePdf.value ?: true
            if (cur && activeCount() <= 1) { showToast("At least one filter must be active"); return@setOnClickListener }
            val next = !cur; updateStatCard(binding.cardPdf, next); viewModel.setIncludePdf(next)
        }
    }

    private fun updateStatCard(card: com.anant.mediacurator.databinding.ItemStatChipBinding, isEnabled: Boolean) {
        val greenColor = ContextCompat.getColor(this, R.color.check_green)
        val errorColor = MaterialColors.getColor(card.root, com.google.android.material.R.attr.colorError)
        val color = if (isEnabled) greenColor else errorColor
        (card.root as com.google.android.material.card.MaterialCardView).strokeColor = color
        val iconRes = if (isEnabled) R.drawable.ic_check else R.drawable.ic_close
        card.ivStatState.setImageResource(iconRes)
        card.ivStatState.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    override fun onResume() {
        super.onResume()
        // Skip menu invalidation while search results are showing. Rebuilding the menu
        // tears down the expanded SearchView, which fires onQueryTextChange("") →
        // search("") → clearSearch() → search results are lost. The checkable items
        // (One-Click Delete, PDF content search) are only relevant outside search mode,
        // so skipping here costs nothing in practice.
        if (viewModel.searchResults.value == null) {
            invalidateOptionsMenu()
        }
        // We ALWAYS trigger a refresh on resume. The ViewModel handles caching and
        // immediate filtering of deleted items using the static flight set.
        // This is necessary because the MediaViewerActivity may have deleted files.
        viewModel.loadMedia(forceRefresh = true)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            adapter.selectionMode -> exitSelectionMode()
            else                  -> super.onBackPressed()
        }
    }

    // Up arrow (shown only when opened from the Home hub): exit selection first, else return to Home.
    override fun onSupportNavigateUp(): Boolean {
        if (adapter.selectionMode) exitSelectionMode() else finish()
        return true
    }

    private fun setupRecyclerView() {
        val spanCount = 4
        adapter = GalleryAdapter(
            onYearToggle     = { year     -> viewModel.toggleYearExpansion(year) },
            onMonthToggle    = { monthKey -> viewModel.toggleMonthExpansion(monthKey) },
            onSubGroupToggle = { subKey   -> viewModel.toggleSubGroupExpansion(subKey) },
            onMediaClick  = { item ->
                if (item.type == MediaType.PDF) {
                    val uri = Uri.parse(item.uri)
                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        startActivity(Intent.createChooser(openIntent, "Open PDF with"))
                    } catch (e: Exception) {
                        showToast("No PDF viewer found")
                    }
                } else {
                    // If we're in search mode, point the viewer at the search result list
                    // so it swipes through results rather than the full gallery.
                    // Without this, items from hidden months won't be found by ID in
                    // flatMediaItems and the viewer falls back to position 0 (wrong item).
                    val searchItems = viewModel.searchResults.value
                    if (searchItems != null) {
                        val mediaItems = searchItems
                            .filterIsInstance<GalleryItem.Media>()
                            .map { it.mediaItem }
                        viewModel.setSearchFlatItems(mediaItems)
                    } else {
                        // Confine the viewer to the tapped photo's month, in the grid's exact
                        // rendered order.  Prev/Next then stays within that month and won't jump
                        // into other (even adjacent, expanded) months.  In flat size-sorted mode
                        // every Media has an empty monthKey, so this naturally spans the whole
                        // list — matching that view, which shows everything in one sequence.
                        val rendered = adapter.currentList.filterIsInstance<GalleryItem.Media>()
                        val monthKey = rendered.firstOrNull { it.mediaItem.id == item.id }?.monthKey
                        val monthItems = rendered.filter { it.monthKey == monthKey }.map { it.mediaItem }
                        if (monthItems.isNotEmpty()) viewModel.setViewerItems(monthItems)
                    }
                    val intent = Intent(this, MediaViewerActivity::class.java).apply {
                        putExtra(MediaViewerActivity.EXTRA_START_ID, item.id)
                    }
                    startActivity(intent)
                }
            },
            onMonthHide = { group ->
                viewModel.markMonthDone(group.year, group.month)
                com.google.android.material.snackbar.Snackbar
                    .make(binding.root, "${group.label} hidden from this app", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .setAction("Undo") { viewModel.restoreMonth(group.year, group.month) }
                    .show()
            },
            onSelectionChanged = { count -> updateSelectionBar(count) }
        )
        val layoutManager = GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = GallerySpanSizeLookup(adapter, spanCount)
        
        binding.recyclerView.apply {
            this.layoutManager = layoutManager
            this.adapter = this@MainActivity.adapter
            setHasFixedSize(false)
            setItemViewCacheSize(30) 
        }
    }

    private fun setupSelectionBar() {
        binding.btnShareSelected.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isEmpty()) { showToast("No items selected"); return@setOnClickListener }
            shareItems(selected)
        }
        binding.btnMoveSelected.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isEmpty()) { showToast("No items selected"); return@setOnClickListener }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                showToast("Move requires Android 10+"); return@setOnClickListener
            }
            showAlbumPicker(selected)
        }
        binding.btnDeleteSelected.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isEmpty()) { showToast("No items selected"); return@setOnClickListener }
            exitSelectionMode()
            viewModel.deleteMedia(selected)
        }
    }

    private fun exitSelectionMode() {
        adapter.exitSelectionMode()
        binding.selectionBar.isVisible = false
        invalidateOptionsMenu()
    }

    /**
     * First-run intro to the core curation concept. Shown once, only after the app is
     * fully ready — media loaded AND the "All files access" decision resolved — so it never
     * stacks on the permission prompts during install.  Idempotent: safe to call from any
     * settle point (load finished, prompt dismissed, returned from Settings).
     */
    private fun tryShowOnboarding() {
        // The Home hub's hero ("Start curating" / "Continue curating") now teaches the concept,
        // so the in-gallery onboarding dialog is retired. Kept as a no-op (call sites are
        // harmless) to avoid churn; remove fully in a later cleanup.
        if (true) return

        @Suppress("UNREACHABLE_CODE")
        if (viewModel.prefs.hasSeenOnboarding()) return
        if (allFilesPromptActive) return                          // PDF-access decision still pending
        if (awaitingAutoRestore) return                           // auto-restore check / prompt pending
        if (!hasBasicPermissions()) return                        // media permission not granted yet
        if (viewModel.isLoading.value != false) return            // still loading
        if (viewModel.galleryItems.value.isNullOrEmpty()) return  // nothing on screen yet
        viewModel.prefs.setSeenOnboarding()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Curate, don't just scroll")
            .setMessage(
                "Media Curator remembers your progress so you never review the same photos twice.\n\n" +
                "1.  Work through a month's photos, videos and PDFs.\n\n" +
                "2.  Tap \"Hide this month\" when you're done — it steps out of your way " +
                "(your files are never deleted, and stay in your normal gallery).\n\n" +
                "3.  Next time, hidden months stay hidden — so you simply continue " +
                "with what's left."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    /**
     * Share the selected media via the system share sheet.  MediaStore content:// URIs
     * are already shareable across apps with FLAG_GRANT_READ_URI_PERMISSION — no FileProvider
     * needed.  Uses ACTION_SEND for one item, ACTION_SEND_MULTIPLE for several.
     */
    private fun shareItems(items: List<MediaItem>) {
        val uris = ArrayList(items.map { Uri.parse(it.uri) })
        // If the selection is all one type, use its concrete MIME so receivers filter well;
        // mixed selections fall back to */*.
        val types = items.map { it.type }.toSet()
        val mime = when {
            types == setOf(MediaType.IMAGE) -> "image/*"
            types == setOf(MediaType.VIDEO) -> "video/*"
            types == setOf(MediaType.AUDIO) -> "audio/*"
            types == setOf(MediaType.PDF)   -> "application/pdf"
            else                            -> "*/*"
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, "Share ${uris.size} item${if (uris.size > 1) "s" else ""}"))
        } catch (e: Exception) {
            showToast("No app available to share these files")
        }
        // Leave selection mode active so the user can keep curating after sharing.
    }

    private fun updateSelectionBar(count: Int) {
        if (count > 0) {
            if (!binding.selectionBar.isVisible) {
                binding.selectionBar.isVisible = true
            }
            val totalBytes = adapter.getSelectedItems().sumOf { it.size }
            binding.tvSelectionCount.text = "$count selected · ${GalleryAdapter.fmtBytes(totalBytes)}"
            binding.btnDeleteSelected.isEnabled = true
        } else {
            binding.selectionBar.isVisible = false
        }
        invalidateOptionsMenu()
    }

    private fun setupRestoreSpinners() {
        binding.autoCompleteYear.setOnItemClickListener { parent, _, position, _ ->
            val selectedItem = parent.getItemAtPosition(position) as String
            val year = selectedItem.substringBefore(" ").trim()
            updateMonthsDropdown(year)
            binding.autoCompleteMonth.setText("", false)
            binding.autoCompleteMonth.showDropDown()
        }

        binding.autoCompleteMonth.setOnItemClickListener { parent, _, position, _ ->
            val abbr = (parent.getItemAtPosition(position) as String).substringBefore(" (")
            val yearStr = binding.autoCompleteYear.text.toString().substringBefore(" ").trim()
            val year = yearStr.toIntOrNull() ?: return@setOnItemClickListener

            val groups = viewModel.doneMonthsAvailable.value ?: emptyList()
            val group = groups.find { it.year == year && it.label.startsWith(abbr) }
            group?.let {
                viewModel.restoreMonth(it.year, it.month)
                binding.autoCompleteMonth.setText("", false)
            }
        }
    }

    private fun updateMonthsDropdown(year: String) {
        val groups = viewModel.doneMonthsAvailable.value ?: emptyList()
        val monthsInYear = groups.filter { it.year.toString() == year }
        
        if (monthsInYear.isEmpty()) {
            binding.menuMonth.isEnabled = false
        } else {
            binding.menuMonth.isEnabled = true
            binding.menuMonth.isVisible = true
            val abbrs = monthsInYear.map { it.label.split(" ")[0].take(3) }
            val stats = monthsInYear.map { group ->
                val size = fmtBytes(group.items.sumOf { it.size }).replace(" ", "")
                "${group.items.size} / $size"
            }
            val monthAdapter = object : android.widget.ArrayAdapter<String>(
                this@MainActivity, android.R.layout.simple_dropdown_item_1line, abbrs
            ) {
                // MaterialAutoCompleteTextView wraps this adapter and routes
                // dropdown item creation through getView(), not getDropDownView().
                override fun getView(
                    position: Int,
                    convertView: android.view.View?,
                    parent: android.view.ViewGroup
                ): android.view.View {
                    val ctx = parent.context
                    // Resolve text colours from the current theme so this works
                    // on both dark and light themes.
                    val ta = ctx.theme.obtainStyledAttributes(
                        intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary)
                    )
                    val colorPrimary   = ta.getColor(0, android.graphics.Color.WHITE)
                    val colorSecondary = ta.getColor(1, 0xFFAAAAAA.toInt())
                    ta.recycle()

                    val ll = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(48, 24, 48, 24)
                    }
                    ll.addView(android.widget.TextView(ctx).apply {
                        text = abbrs[position]
                        textSize = 16f
                        setTextColor(colorPrimary)
                    })
                    ll.addView(android.widget.TextView(ctx).apply {
                        text = stats[position]
                        textSize = 13f
                        setTextColor(colorSecondary)
                    })
                    return ll
                }
            }
            binding.autoCompleteMonth.setAdapter(monthAdapter)
        }
    }

    /** Show the Unhide panel only when there are hidden months AND the tree view is active. */
    private fun updateRestoreLayoutVisibility() {
        val hasHidden  = (viewModel.doneMonthsAvailable.value?.isNotEmpty()) == true
        val isTreeMode = viewModel.sortMode.value != SortMode.SIZE_ABSOLUTE
        binding.restoreLayout.isVisible = hasHidden && isTreeMode
    }

    private fun observeViewModel() {
        viewModel.galleryItems.observe(this) { items ->
            // Search takes over the list area — don't let a gallery refresh overwrite results/prompt
            if (inSearchMode || viewModel.searchResults.value != null) return@observe
            adapter.submitList(items)
            val isLoading = viewModel.isLoading.value ?: false
            binding.tvEmpty.isVisible = items.isEmpty() && !isLoading
            if (items.isEmpty() && !isLoading) binding.tvEmpty.text = resolveEmptyMessage()
            binding.recyclerView.post { updateStickyHeader() }
            tryShowOnboarding()

            // Home "Resume" deep-link: scroll to the target month once its header is in the
            // list, then clear (so later startup loads don't keep yanking the scroll).
            pendingResumeKey?.let { key ->
                val pos = items.indexOfFirst { it is GalleryItem.Header && it.monthKey == key }
                if (pos >= 0) {
                    (binding.recyclerView.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(pos, 0)
                    binding.appBarLayout.setExpanded(true, false)
                    pendingResumeKey = null
                }
            }
        }

        viewModel.searchResults.observe(this) { results ->
            if (results == null) {
                if (inSearchMode) {
                    // Search bar still open, query cleared/empty → keep the prompt, not the gallery
                    showSearchPrompt()
                } else {
                    // Exiting search — restore gallery
                    binding.settingsBar.isVisible = true
                    updateRestoreLayoutVisibility()
                    binding.stickyHeader.visibility = android.view.View.GONE
                    viewModel.galleryItems.value?.let { adapter.submitList(it) }
                    binding.tvEmpty.isVisible = false
                }
            } else {
                // In search mode
                binding.settingsBar.isVisible = false
                binding.restoreLayout.isVisible = false
                binding.stickyHeader.visibility = android.view.View.GONE
                adapter.submitList(results)
                val isLoading = viewModel.isLoading.value ?: false
                binding.tvEmpty.isVisible = results.isEmpty() && !isLoading
                if (results.isEmpty() && !isLoading) {
                    binding.tvEmpty.text = "No results — try different keywords\nor check for typos"
                }
            }
        }
        
        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.isVisible = loading
            if (loading) binding.tvEmpty.isVisible = false  // never show empty state while loading
            if (!loading) tryShowOnboarding()               // app is ready — maybe show first-run intro
        }
        
        viewModel.sortMode.observe(this) { _ ->
            invalidateOptionsMenu()
            // Don't overwrite the subtitle if the SearchView is currently expanded
            if (viewModel.searchResults.value == null) updateSortSubtitle()
            updateRestoreLayoutVisibility()
        }

        viewModel.storageSavedEvent.observe(this) { bytes ->
            val text = when {
                bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
                bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
                bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
                else                    -> "$bytes B"
            }
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, "Freed $text", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show()
        }
        
        viewModel.deletePermissionRequest.observe(this) { intentSender ->
            intentSender?.let {
                deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
            }
        }
        
        viewModel.doneMonthsAvailable.observe(this) { groups ->
            if (groups.isEmpty()) {
                binding.restoreLayout.isVisible = false
            } else {
                updateRestoreLayoutVisibility()

                // Total hidden count
                val totalHidden = groups.sumOf { it.items.size }
                binding.tvHiddenTotal.text = "${fmtCount(totalHidden)} items hidden"

                // Year items with per-year counts and sizes: "2023 (450 / 10 MB)"
                val yearGroups = groups.groupBy { it.year }
                val yearCountMap = yearGroups.mapValues { (_, months) -> months.sumOf { it.items.size } }
                val yearSizeMap  = yearGroups.mapValues { (_, months) -> months.sumOf { g -> g.items.sumOf { it.size } } }
                val yearItems = groups.map { it.year }.distinct().sortedDescending()
                    .map { y -> "$y (${yearCountMap[y] ?: 0} / ${fmtBytes(yearSizeMap[y] ?: 0L)})" }
                    .toTypedArray()
                binding.autoCompleteYear.setSimpleItems(yearItems)

                // Re-populate month dropdown if a year is already selected
                val currentYear = binding.autoCompleteYear.text.toString().substringBefore(" ").trim()
                if (currentYear.isNotEmpty()) {
                    updateMonthsDropdown(currentYear)
                } else {
                    binding.menuMonth.isEnabled = false
                }
            }
        }

        viewModel.scrollToMonthKey.observe(this) { key ->
            if (key == null) return@observe
            viewModel.clearScrollToMonth()
            // Capture where we are NOW before jumping
            jumpAMonthKey = currentVisibleMonthKey()
            jumpBMonthKey = key
            binding.recyclerView.post {
                val pos = adapter.currentList.indexOfFirst {
                    it is GalleryItem.Header && it.monthKey == key
                }
                if (pos >= 0) {
                    (binding.recyclerView.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(pos, 0)
                }
                binding.fabJumpSwap.isVisible = jumpAMonthKey != null
            }
        }

        viewModel.mediaStats.observe(this) { stats ->
            if (stats == null) return@observe
            if (pendingShowStats) {
                pendingShowStats = false
                StatsDialog.present(this)
            }
            val totalPhotoBytes = stats.visiblePhotoBytes + stats.hiddenPhotoBytes
            val totalVideoBytes = stats.visibleVideoBytes + stats.hiddenVideoBytes
            val totalAudioBytes = stats.visibleAudioBytes + stats.hiddenAudioBytes
            val totalPdfBytes   = stats.visiblePdfBytes   + stats.hiddenPdfBytes
            binding.cardPhoto.tvStatCounts.text = statsText(stats.totalPhotos, stats.hiddenPhotos, totalPhotoBytes)
            binding.cardVideo.tvStatCounts.text = statsText(stats.totalVideos, stats.hiddenVideos, totalVideoBytes)
            binding.cardAudio.tvStatCounts.text = statsText(stats.totalAudios, stats.hiddenAudios, totalAudioBytes)
            binding.cardPdf.tvStatCounts.text   = statsText(stats.totalPdfs,   stats.hiddenPdfs,  totalPdfBytes)
            binding.cardAudio.root.isVisible = stats.totalAudios > 0
            binding.cardPdf.root.isVisible   = stats.totalPdfs   > 0
        }

        viewModel.autoRestorePrompt.observe(this) { months ->
            if (months == null) return@observe
            val count = months.size
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Backup found")
                .setMessage(
                    "Found ‘${ GalleryViewModel.AUTO_BACKUP_FILENAME}’ in Downloads " +
                    "with $count hidden month${if (count == 1) "" else "s"}.\n\nImport it?"
                )
                .setPositiveButton("Import") { _, _ ->
                    viewModel.confirmAutoRestore(months); awaitingAutoRestore = false; tryShowOnboarding()
                }
                .setNegativeButton("Skip")   { _, _ ->
                    viewModel.dismissAutoRestore(); awaitingAutoRestore = false; tryShowOnboarding()
                }
                .setCancelable(false)
                .show()
        }

        viewModel.autoRestoreCheckDone.observe(this) {
            // Check finished. If it did NOT raise a "Backup found" prompt, release the gate now;
            // otherwise the prompt's Import/Skip handler releases it after the user decides.
            if (viewModel.autoRestorePrompt.value == null) {
                awaitingAutoRestore = false
                tryShowOnboarding()
            }
        }


        viewModel.pdfIndexOomEvent.observe(this) {
            invalidateOptionsMenu()   // update the menu checkmark to reflect disabled state
            com.google.android.material.snackbar.Snackbar
                .make(
                    binding.root,
                    "PDF content search disabled — not enough memory. File name search still works.",
                    com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
                )
                .setAction("Re-enable") {
                    viewModel.setPdfContentSearchEnabled(true)
                    invalidateOptionsMenu()
                }
                .show()
        }

        viewModel.pdfIndexProgress.observe(this) { progress ->
            // Show indexing progress in the toolbar subtitle.
            // Skip if we're in search mode (subtitle already cleared) or after completion.
            if (viewModel.searchResults.value != null) return@observe
            when {
                progress == null || progress.isDone -> updateSortSubtitle()
                progress.isActive ->
                    supportActionBar?.subtitle = "Indexing PDFs… ${progress.indexed} / ${progress.total}"
            }
        }

        viewModel.photoHashOomEvent.observe(this) {
            invalidateOptionsMenu()
            com.google.android.material.snackbar.Snackbar
                .make(
                    binding.root,
                    "Duplicate detection disabled — not enough memory.",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                )
                .show()
        }

        viewModel.photoHashProgress.observe(this) { progress ->
            if (viewModel.searchResults.value != null) return@observe
            // Only show photo hash progress when PDF indexing is not already showing
            val pdfActive = viewModel.pdfIndexProgress.value?.isActive == true
            if (pdfActive) return@observe
            when {
                progress == null || progress.isDone -> updateSortSubtitle()
                progress.isActive ->
                    supportActionBar?.subtitle = "Hashing photos… ${progress.hashed} / ${progress.total}"
            }
        }

        viewModel.scrollToTopEvent.observe(this) {
            binding.recyclerView.scrollToPosition(0)
            binding.appBarLayout.setExpanded(true, false)
        }

        viewModel.scrollToMonthKey.observe(this) { monthKey ->
            if (monthKey == null) return@observe
            val pos = adapter.currentList.indexOfFirst {
                it is GalleryItem.Header && it.monthKey == monthKey
            }
            if (pos >= 0) {
                (binding.recyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(pos, 0)
                binding.appBarLayout.setExpanded(true, false)
            }
            viewModel.clearScrollToMonth()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        setupSearchView(menu)
        // Home "Search" card deep-link: expand the search field now that the menu exists.
        if (pendingOpenSearch) {
            pendingOpenSearch = false
            menu.findItem(R.id.action_search)?.expandActionView()
        }
        return true
    }

    private fun setupSearchView(menu: Menu) {
        val searchItem = menu.findItem(R.id.action_search) ?: return
        val searchView = searchItem.actionView as? androidx.appcompat.widget.SearchView ?: return

        searchView.queryHint = "Labels, filenames…"
        searchView.maxWidth  = Int.MAX_VALUE   // allow full toolbar width when expanded

        searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchView.clearFocus()
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.search(newText.orEmpty())
                return true
            }
        })

        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                inSearchMode = true
                supportActionBar?.subtitle = null
                showSearchPrompt()   // empty prompt until the user types — not the whole gallery
                return true
            }
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                inSearchMode = false
                // If we were opened from Home just to search, exit straight back to Home
                // instead of revealing the gallery behind the search.
                if (launchedForSearch) { finish(); return true }
                viewModel.clearSearch()
                updateSortSubtitle()
                return true
            }
        })
    }

    /** Search bar open with no query yet: show a prompt instead of the full gallery. */
    private fun showSearchPrompt() {
        binding.settingsBar.isVisible = false
        binding.restoreLayout.isVisible = false
        binding.stickyHeader.visibility = android.view.View.GONE
        adapter.submitList(emptyList())
        binding.tvEmpty.isVisible = true
        binding.tvEmpty.text = "Type to search photos, videos, PDFs…"
    }

    // Sort is shown in the sort chip now; the toolbar subtitle is reserved for indexing/hashing
    // progress. This updates the chip label and clears the subtitle when no progress is running.
    private fun updateSortSubtitle() {
        binding.btnSort.text = when (viewModel.sortMode.value ?: SortMode.DATE_OLDEST) {
            SortMode.DATE_NEWEST       -> "Newest first"
            SortMode.DATE_OLDEST       -> "Oldest first"
            SortMode.SIZE_ABSOLUTE     -> "Largest (overall)"
            SortMode.SIZE_WITHIN_MONTH -> "Largest (per month)"
            SortMode.COUNT_PER_MONTH   -> "Most items"
        }
        val progress = viewModel.pdfIndexProgress.value
        val hashProgress = viewModel.photoHashProgress.value
        if ((progress == null || !progress.isActive) && (hashProgress == null || !hashProgress.isActive)) {
            supportActionBar?.subtitle = null
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val inSelection = adapter.selectionMode
        // Search now lives on the Home hub (dedicated SearchActivity) — hide the gallery lens.
        menu.findItem(R.id.action_search)?.isVisible = false
        menu.findItem(R.id.action_refresh)?.isVisible = !inSelection
        
        menu.findItem(R.id.action_pdf_content_search)?.let {
            it.isVisible = !inSelection
            it.isChecked = viewModel.isPdfContentSearchEnabled()
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.sort_newest        -> { viewModel.setSortMode(SortMode.DATE_NEWEST);       true }
        R.id.sort_oldest        -> { viewModel.setSortMode(SortMode.DATE_OLDEST);       true }
        R.id.sort_size_absolute -> { viewModel.setSortMode(SortMode.SIZE_ABSOLUTE);     true }
        R.id.sort_size_month    -> { viewModel.setSortMode(SortMode.SIZE_WITHIN_MONTH); true }
        R.id.sort_count_month   -> { viewModel.setSortMode(SortMode.COUNT_PER_MONTH);   true }
        R.id.action_refresh -> { viewModel.loadMedia(forceRefresh = true); true }
        R.id.action_backup -> {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Hidden months backup")
                .setItems(arrayOf("Export to Downloads", "Import from file")) { _, which ->
                    if (which == 0) exportHiddenMonths()
                    else importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        R.id.action_pdf_content_search -> {
            val currentlyEnabled = viewModel.isPdfContentSearchEnabled()
            if (currentlyEnabled) {
                // Disabling — warn the user
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Disable PDF content search?")
                    .setMessage(
                        "Background indexing will stop and PDF results will be matched " +
                        "by filename only.\n\nThe existing index files are kept — " +
                        "re-enabling will pick up where it left off."
                    )
                    .setPositiveButton("Disable") { _, _ ->
                        viewModel.setPdfContentSearchEnabled(false)
                        invalidateOptionsMenu()
                        showToast("PDF content search disabled")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                viewModel.setPdfContentSearchEnabled(true)
                invalidateOptionsMenu()
                showToast("PDF content search enabled — indexing will resume")
            }
            true
        }
        R.id.action_stats_info -> { StatsDialog.present(this); true }
        R.id.action_help -> { startActivity(Intent(this, HelpActivity::class.java)); true }
        R.id.action_share_diagnostics -> { shareDiagnostics(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Assemble device info + app state + the ring log, hand to a share sheet. */
    private fun shareDiagnostics() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Share diagnostics?")
            .setMessage(
                "The report contains your device model, app settings, and a log of recent " +
                "app activity (indexing results, errors). No photos or file contents are " +
                "included. You can review the full text before sending."
            )
            .setPositiveButton("Continue") { _, _ -> doShareDiagnostics() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doShareDiagnostics() {
        val state = mutableListOf<String>()
        state += "All-files access : ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.os.Environment.isExternalStorageManager() else "n/a"}"
        state += "Hashes cached    : ${viewModel.photoHashStore.countEntries()}"
        state += "PDF search       : ${viewModel.prefs.isPdfContentSearchEnabled()}"
        state += "Dup detection    : ${viewModel.prefs.isPhotoDuplicateDetectionEnabled()}"
        state += "Gallery items    : ${viewModel.galleryItems.value?.size ?: 0}"

        val report = DebugLog.buildDiagnosticsReport(this, state)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Media Curator diagnostics")
            putExtra(android.content.Intent.EXTRA_TEXT, report)
        }
        startActivity(android.content.Intent.createChooser(intent, "Share diagnostics via"))
    }

    private fun showSortPopup() {
        val popup = androidx.appcompat.widget.PopupMenu(this, binding.btnSort)
        popup.menuInflater.inflate(R.menu.menu_sort, popup.menu)
        val checkedId = when (viewModel.sortMode.value ?: SortMode.DATE_OLDEST) {
            SortMode.DATE_NEWEST       -> R.id.sort_newest
            SortMode.DATE_OLDEST       -> R.id.sort_oldest
            SortMode.SIZE_ABSOLUTE     -> R.id.sort_size_absolute
            SortMode.SIZE_WITHIN_MONTH -> R.id.sort_size_month
            SortMode.COUNT_PER_MONTH   -> R.id.sort_count_month
        }
        popup.menu.findItem(checkedId)?.isChecked = true
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.sort_newest        -> viewModel.setSortMode(SortMode.DATE_NEWEST)
                R.id.sort_oldest        -> viewModel.setSortMode(SortMode.DATE_OLDEST)
                R.id.sort_size_absolute -> viewModel.setSortMode(SortMode.SIZE_ABSOLUTE)
                R.id.sort_size_month    -> viewModel.setSortMode(SortMode.SIZE_WITHIN_MONTH)
                R.id.sort_count_month   -> viewModel.setSortMode(SortMode.COUNT_PER_MONTH)
            }
            true
        }
        popup.show()
    }

    private fun hasBasicPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        // On Android 13+, we don't strictly require READ_EXTERNAL_STORAGE as it returns false
        // even if media permissions are granted.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
             ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED)
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.toTypedArray()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = getRequiredPermissions()
        if (hasBasicPermissions()) {
            checkAllFilesAccessAndLoad()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun hasAllFilesPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()

    /**
     * Load media immediately (photos/videos don't need broad storage access).
     * If MANAGE_EXTERNAL_STORAGE is not granted on Android 11+, show a one-time
     * prompt explaining that PDFs won't be visible without it.
     */
    private fun checkAllFilesAccessAndLoad() {
        viewModel.loadMedia()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && !hasAllFilesPermission()
            && !offeredAllFilesAccess
        ) {
            offeredAllFilesAccess = true
            allFilesPromptActive = true   // block onboarding until this decision is made
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("PDF access")
                .setMessage(
                    "To browse PDF files, Media Curator needs the \"All files access\" permission.\n\n" +
                    "Photos and videos work without it. Tap Grant to enable PDFs."
                )
                .setPositiveButton("Grant") { _, _ ->
                    launchedAllFilesSettings = true
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    allFilesAccessLauncher.launch(intent)
                }
                .setNegativeButton("Skip", null)
                // Fires for Skip, back-press, AND tap-outside.  Grant also dismisses, but in
                // that case the Settings screen is handling it — allFilesAccessLauncher resumes
                // hashing when the user returns.  Without this listener, dismissing the dialog
                // via back-press or outside-tap would leave deferred hashing stuck forever.
                .setOnDismissListener {
                    if (!launchedAllFilesSettings) {
                        // Skip / back / outside-tap — decision made without leaving the app.
                        viewModel.resumeDeferredHashing()
                        allFilesPromptActive = false
                        tryShowOnboarding()
                    }
                    // Grant path keeps allFilesPromptActive true until the Settings screen
                    // returns via allFilesAccessLauncher, which clears it and retries onboarding.
                    launchedAllFilesSettings = false
                }
                .show()
        }
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1_073_741_824L -> "%.1f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576L     -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1_024L         -> "%.1f KB".format(b / 1_024.0)
        else                -> "$b B"
    }

    /**
     * Returns the most helpful explanation for why the gallery is empty:
     *  1. A chip filter is on and is hiding items that exist → tell user to adjust chips
     *  2. All months are marked as done → tell user to use Unhide
     *  3. No media on device at all → generic message
     */
    private fun resolveEmptyMessage(): String {
        val stats        = viewModel.mediaStats.value
        val allChipsOn   = (viewModel.includePhoto.value ?: true) &&
                           (viewModel.includeVideo.value ?: true) &&
                           (viewModel.includeAudio.value ?: true) &&
                           (viewModel.includePdf.value   ?: true)
        val totalVisible = (stats?.visiblePhotos ?: 0) +
                           (stats?.visibleVideos ?: 0) +
                           (stats?.visibleAudios ?: 0) +
                           (stats?.visiblePdfs   ?: 0)

        if (!allChipsOn && totalVisible > 0) {
            return "No items match the current filter.\nTap the chips above to show more types."
        }

        val hasHiddenMonths = viewModel.doneMonthsAvailable.value?.isNotEmpty() == true
        if (hasHiddenMonths) {
            return "All months are marked as done!\nUse the Unhide panel above to restore months."
        }

        return "No media found on this device."
    }

    // ── Sticky header ─────────────────────────────────────────────────────────

    private fun updateStickyHeader(firstVisiblePos: Int = -1) {
        // No sticky header in flat size-absolute mode
        if (viewModel.sortMode.value == SortMode.SIZE_ABSOLUTE) {
            binding.stickyHeader.isVisible = false
            return
        }

        val lm = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
        val firstPos = if (firstVisiblePos >= 0) firstVisiblePos
                       else lm.findFirstVisibleItemPosition()
        if (firstPos < 0) { binding.stickyHeader.isVisible = false; return }

        val list      = adapter.currentList
        val firstItem = list.getOrNull(firstPos)

        // Walk back to find nearest YearHeader, Header, and SubHeader above the viewport top
        var yearCtx:  GalleryItem.YearHeader? = null
        var monthCtx: GalleryItem.Header?     = null
        var subCtx:   GalleryItem.SubHeader?  = null
        for (i in firstPos downTo 0) {
            val item = list.getOrNull(i) ?: break
            if (subCtx   == null && item is GalleryItem.SubHeader) subCtx   = item
            if (monthCtx == null && item is GalleryItem.Header)    monthCtx = item
            if (item is GalleryItem.YearHeader) { yearCtx = item; break }
        }

        // Hide when no year context, or the year header itself is already at the top
        if (yearCtx == null || firstItem is GalleryItem.YearHeader) {
            binding.stickyHeader.isVisible = false
            return
        }

        binding.stickyHeader.isVisible = true

        // Sub row — only when inside a sub-group (sub-header itself has scrolled off top)
        val showSub   = subCtx   != null && firstItem !is GalleryItem.SubHeader && firstItem !is GalleryItem.Header
        // Month row — when inside a month and sub row is not shown OR when sub-header is the first visible item
        val showMonth = monthCtx != null && firstItem !is GalleryItem.Header
        binding.stickySubRow.isVisible   = showSub
        binding.stickyMonthRow.isVisible = showMonth

        // Year row — always shown; tap behaviour: go up one level
        binding.tvStickyYearArrow.text = if (yearCtx.isExpanded) "▼" else "▶"
        binding.tvStickyYear.text      = yearCtx.year.toString()
        binding.tvStickyYearStats.text = GalleryAdapter.formatTypeBreakdown(yearCtx.photoCount, yearCtx.videoCount, yearCtx.pdfCount, yearCtx.totalBytes)
        val capturedYear = yearCtx.year
        if (monthCtx != null && (showMonth || showSub)) {
            val capturedMonthKey = monthCtx.monthKey
            binding.stickyYearRow.setOnClickListener { viewModel.toggleMonthExpansion(capturedMonthKey) }
        } else {
            binding.stickyYearRow.setOnClickListener { viewModel.toggleYearExpansion(capturedYear) }
        }

        // Month row — tap collapses the month
        if (showMonth && monthCtx != null) {
            binding.tvStickyMonthArrow.text = if (monthCtx.isExpanded) "▼" else "▶"
            binding.tvStickyMonth.text      = monthCtx.label
            binding.tvStickyMonthStats.text = GalleryAdapter.formatTypeBreakdown(monthCtx.photoCount, monthCtx.videoCount, monthCtx.pdfCount, monthCtx.totalBytes)
            val capturedMonthKey = monthCtx.monthKey
            binding.stickyMonthRow.setOnClickListener { viewModel.toggleMonthExpansion(capturedMonthKey) }
        }

        // Sub row — tap collapses the sub-group
        if (showSub && subCtx != null) {
            binding.tvStickySubArrow.text = if (subCtx.isExpanded) "▼" else "▶"
            binding.tvStickySubLabel.text = subCtx.label
            binding.tvStickySubStats.text = GalleryAdapter.formatTypeBreakdown(subCtx.photoCount, subCtx.videoCount, subCtx.pdfCount, subCtx.totalBytes)
            val capturedSubKey = subCtx.subKey
            binding.stickySubRow.setOnClickListener { viewModel.toggleSubGroupExpansion(capturedSubKey) }
        }
    }

    // ── Export / Import ───────────────────────────────────────────────────────

    private fun exportHiddenMonths() {
        val months = viewModel.prefs.getDoneMonths()
        if (months.isEmpty()) {
            showToast("No hidden months to export")
            return
        }
        lifecycleScope.launch {
            try {
                val stamp    = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
                val filename = "mediacurator_hidden_$stamp.json"
                val json     = buildExportJson(months)

                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, filename)
                            put(MediaStore.Downloads.MIME_TYPE, "application/json")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = contentResolver.insert(
                            MediaStore.Downloads.getContentUri("external"), values
                        ) ?: throw Exception("Could not create file in Downloads")
                        contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        File(dir, filename).writeText(json, Charsets.UTF_8)
                    }
                }
                showToast("Exported ${months.size} hidden months → Downloads/$filename")
            } catch (e: Exception) {
                Log.e("MainActivity", "Export failed", e)
                showToast("Export failed: ${e.message}")
            }
        }
    }

    private fun buildExportJson(months: Set<String>): String {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val arr = months.sorted().joinToString(",\n    ") { "\"$it\"" }
        return "{\n  \"version\": 1,\n  \"exportedAt\": \"$ts\",\n  \"hiddenMonths\": [\n    $arr\n  ]\n}"
    }

    private fun importHiddenMonths(uri: Uri) {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: run { showToast("Could not read file"); return@launch }

                val obj      = org.json.JSONObject(json)
                val arr      = obj.getJSONArray("hiddenMonths")
                val incoming = (0 until arr.length()).map { arr.getString(it) }.toSet()

                val existing = viewModel.prefs.getDoneMonths()
                val newCount = (incoming - existing).size
                viewModel.prefs.setDoneMonths(existing + incoming)
                viewModel.loadMedia(forceRefresh = false)

                showToast("Import done — $newCount new months added (${existing.size + newCount} total hidden)")
            } catch (e: Exception) {
                Log.e("MainActivity", "Import failed", e)
                showToast("Import failed: ${e.message}")
            }
        }
    }

    // ── Move to album ─────────────────────────────────────────────────────────

    private fun showAlbumPicker(items: List<MediaItem>) {
        lifecycleScope.launch {
            val albums = fetchAlbums()
            if (albums.isEmpty()) { showToast("No albums found"); return@launch }
            val names = albums.keys.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Move to album")
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
        val toMove  = items.filter {
            it.relativePath.trimEnd('/').lowercase() != targetPath.trimEnd('/').lowercase()
        }
        val skipped = items.size - toMove.size
        if (toMove.isEmpty()) { showToast("All items already in $targetName"); return }

        pendingMoveItems      = toMove
        pendingMovePath       = targetPath
        pendingMoveTargetName = targetName
        pendingMoveSkipped    = skipped

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = toMove.map { Uri.parse(it.uri) }
                val pi   = MediaStore.createWriteRequest(contentResolver, uris)
                moveLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                showToast("Move failed: ${e.message}")
                clearPendingMove()
            }
        } else {
            // API 29: WRITE_EXTERNAL_STORAGE covers direct update
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { moveItemsDirect(toMove, targetPath) }
                showMoveToast(toMove.size, skipped, targetName)
                clearPendingMove()
                exitSelectionMode()
                viewModel.loadMedia(forceRefresh = true)
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
                android.util.Log.e("Move", "Failed to move ${item.displayName}", e)
            }
        }
    }

    private fun showMoveToast(moved: Int, skipped: Int, targetName: String) {
        val msg = if (skipped > 0)
            "Moved $moved · $skipped already in $targetName"
        else
            "Moved $moved item${if (moved == 1) "" else "s"} to $targetName"
        showToast(msg)
    }

    private fun clearPendingMove() {
        pendingMoveItems      = null
        pendingMovePath       = null
        pendingMoveTargetName = null
        pendingMoveSkipped    = 0
    }

    /** Returns the monthKey of the Header at or just above the first visible item. */
    private fun currentVisibleMonthKey(): String? {
        val lm = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return null
        val firstPos = lm.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: return null
        val list = adapter.currentList
        for (i in firstPos downTo 0) {
            val item = list.getOrNull(i)
            if (item is GalleryItem.Header) return item.monthKey
        }
        return null
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun fmtSize(b: Long): String = when {
        b >= 1_073_741_824L -> "%.1fG".format(b / 1_073_741_824.0)
        b >= 1_048_576L     -> "%.1fM".format(b / 1_048_576.0)
        b >= 1_024L         -> "%.1fk".format(b / 1_024.0)
        else                -> "${b}B"
    }

    private fun fmtCount(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).trimEnd('0').trimEnd('.')
        n >= 1_000     -> "%.1fk".format(n / 1_000.0).trimEnd('0').trimEnd('.')
        else           -> "$n"
    }

    // No-decimal variants for compact chip labels
    private fun fmtCountShort(n: Int): String = when {
        n >= 1_000_000 -> "${Math.round(n / 1_000_000.0)}M"
        n >= 1_000     -> "${Math.round(n / 1_000.0)}k"
        else           -> "$n"
    }
    private fun fmtSizeShort(b: Long): String = when {
        b >= 1_073_741_824L -> "${Math.round(b / 1_073_741_824.0)}G"
        b >= 1_048_576L     -> "${Math.round(b / 1_048_576.0)}M"
        b >= 1_024L         -> "${Math.round(b / 1_024.0)}k"
        else                -> "${b}B"
    }

    private fun statsText(total: Int, hidden: Int, bytes: Long): String {
        val counts = if (hidden > 0) "${fmtCountShort(total)}/${fmtCountShort(hidden)}" else fmtCountShort(total)
        return "$counts · ${fmtSizeShort(bytes)}"
    }

    // ── Help ──────────────────────────────────────────────────────────────────

    private fun showHelpDialog() {
        val text = """
Most gallery apps let you delete — but none of them remember what you’ve already reviewed. Come back a week later and you’re staring at the same pile from the beginning (or end, depending on your sorting order).

Media Curator fixes this. Work through a month, mark it as hidden, and it steps out of your way. Next session you pick up exactly where you left off — every time. Result? A super-charged media curation experience.

Months you hide are never deleted — they stay fully accessible in your phone’s regular gallery, and you can unhide them here anytime.

Built for people with years of accumulated photos, videos, PDFs and audio files who want to clean up methodically — not just browse.
─────────────────────────────
📊 STAT CARDS (top bar)
Three cards always visible: 📷 Photos · 🎬 Videos · 🎵 Audio. A 📄 PDFs card appears when PDFs are present. Each card shows total count, hidden count, and size. Tap a card to toggle that type on/off — at least one must stay active.

📈 STATS (ⓘ in the toolbar)
Tap the info icon for a full breakdown of counts and sizes (visible + hidden = total) per type. It also shows your lifetime "Cleaned up" total — how many items you've deleted through the app and how much space that freed. This running total survives reinstalls.

📂 BROWSING (Tree View)
Years are shown collapsed. Each year row shows a type breakdown (📷/🎬/🎵/📄 with counts), how many months have been curated, and thumbnail previews from the start and end of the year. Tap a year to expand it and see its months. Tap a month to see its items. Tap again to collapse.

📌 STICKY HEADER
While scrolling inside a year/month, a floating bar stays pinned at the top showing where you are. Tap it to collapse the open month (or the year, if no month is open).

📄 PDF FILES
PDF tiles show the document's first page as the thumbnail with a small "PDF" badge. Open one to preview the first page full-screen; tap it to open the PDF in your preferred PDF app.

🙈 HIDING A MONTH
Scroll to the bottom of any open month and tap "Hide this month". The month disappears from your gallery — files are NOT deleted, just hidden from this app.

👁️ UNHIDING
When you have hidden months, a bar appears at the top. Pick a year from the first dropdown — the month list populates automatically. Select a month to restore it instantly. The app scrolls straight to it.

🗑️ DELETING FILES
Long-press any item to enter multi-select mode. Tap more items to add them to the selection. Tap Delete in the bar at the bottom. Press Back to leave multi-select without deleting.

📦 MOVING FILES
In multi-select mode, tap Move to relocate the selected files to a folder of your choice.

📤 SHARING FILES
In multi-select mode, tap Share to send the selected photos, videos, audio, or PDFs through any app — messaging, email, cloud, and more.

♊ FIND DUPLICATES (menu)
Finds exact duplicate photos and videos (identical file content, even with different names). The app fingerprints your media in the background after PDF indexing completes — progress shows in the toolbar subtitle. Open Find Duplicates to review groups side by side: the best copy in each group (preferring your camera folder, then the oldest) is pre-selected to keep — tap a different copy to keep that one instead. Tap "Delete marked" to remove all the others and reclaim the space.

🔢 SORT ORDER
Tap the sort chip (e.g. "Oldest first") below the type cards to change the sort order:
• Newest / Oldest first — by capture date
• Largest first (overall) — biggest files first, across all months
• Largest first (per month) — months ordered by their total size
• Most items first — months with the most files appear at the top

🔍 SEARCH (toolbar search icon)
Tap the search icon to search by filename or photo labels (things ML Kit sees in your photos — dog, beach, car…). Fuzzy matching handles typos: "flwoer" finds "flower.png".

PDF content is also indexed and searched. The first 5 pages of each PDF are indexed in the background — a counter in the toolbar subtitle shows progress. Results from PDF content show a "📄 content (first 5 pg)" badge. For long PDFs (> 5 pages) the content beyond page 5 is not indexed and hence not searchable.

Multi-word queries use AND logic: "dog beach" only returns items tagged with BOTH concepts.

A result from a hidden month is marked with a 🙈 — so you can tell a match comes from a month you've already curated away. (Generic filename bits like "IMG", "jpg" or plain numbers are ignored, so they don't flood results with every camera photo.)

💾 BACKUP (menu → Export / Import hidden months list)
Hidden-month state is auto-saved to mediacurator_hidden.json in your Downloads folder after every hide/unhide. It survives app-data clears and reinstalls — just reinstall and the state restores automatically. Use Export / Import hidden months list to move your state to another device.

The PDF content index is also backed up automatically to mediacurator_pdf_index.json.gz in Downloads after indexing completes. Likewise, duplicate-detection fingerprints are backed up to mediacurator_photo_hashes.txt.gz. On a fresh install both are restored automatically — so you don't have to re-index your PDFs or re-scan your media.

ℹ️ Note: these backup files (mediacurator_hidden.json, mediacurator_pdf_index.json.gz, mediacurator_photo_hashes.txt.gz) are NOT deleted when you uninstall the app. Delete them manually from your Downloads folder if you want a completely clean slate.

🪲 SHARE DIAGNOSTICS (menu)
Hit a problem? Menu → Share diagnostics builds a report of device info, app settings, and recent activity (no photos, file names, or file contents) that you can review and send to the developer.
        """.trimIndent()

        val tv = android.widget.TextView(this).apply {
            this.text = text
            textSize  = 13f
            setLineSpacing(4f, 1f)
            setPadding(56, 32, 56, 24)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Media Curator — Help  (v${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE})")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Got it", null)
            .show()
    }
}
