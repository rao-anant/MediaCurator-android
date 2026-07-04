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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import android.content.res.ColorStateList
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
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
    private var pendingScrollToTopMonth: String? = null  // month just opened → scroll its header to top

    // Released once the durable "demo already shown" marker has been consulted — blocks the
    // first-run demo from flashing on a reinstall before that read completes.
    private var onboardingGateReady = false

    // The walk-through latch for the open month: it counts as "reached end" once BOTH its header and
    // footer have been on screen (a long month thus needs a real top-to-bottom pass; a month that
    // fits qualifies immediately). Resets only when the open month changes OR its rendered length
    // changes (a sub-group expand/collapse) — NOT on every rebuild, so background refreshes don't
    // wipe progress. Pure decision logic lives in [WalkLatch] (unit-tested, shared spec with iOS).
    private val walk = WalkLatch()

    // Runs the chevron "wave" on the teaser hint; null when not animating.
    private var chevronAnimator: android.animation.ValueAnimator? = null
    private var hintPointsUp = false   // current chevron direction (▲ when the target is above)

    // RecyclerView's resting bottom padding (from XML); the bar's height is added on top when shown.
    private var baseRvBottomPad = -1

    // Jump toggle: remembers the two positions so the swap FAB can bounce back and forth
    private var jumpAMonthKey: String? = null  // where we came FROM
    private var jumpBMonthKey: String? = null  // where we jumped TO

    // Pending move state — held across the createWriteRequest consent dialog
    private var batchValidating = false   // true while re-validating the quick-undo on resume
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
                    // Keep the selection (like Share) — non-destructive, lets the user keep going.
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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Edge-to-edge: lift the pinned Hide bar above the system navigation bar so it doesn't
        // overlap the nav buttons/gesture pill. (fitsSystemWindows lets CoordinatorLayout dispatch
        // insets to this child; the listener then applies only the bottom inset.)
        binding.hideMonthBarContainer.fitsSystemWindows = true
        val hideBarBasePadBottom = binding.hideMonthBarContainer.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.hideMonthBarContainer) { v, insets ->
            val navBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, hideBarBasePadBottom + navBottom)
            insets
        }
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
                updateHideBar()
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
        // A delete on another screen — or an external restore from the system gallery — may have
        // changed the last-deleted batch. Hide the quick-undo until validation confirms it's still
        // recoverable, so it doesn't briefly flash a stale "Restore last deleted (N)".
        batchValidating = true
        viewModel.refreshLastBatchSize()
        // Skip menu invalidation while search results are showing. Rebuilding the menu
        // tears down the expanded SearchView, which fires onQueryTextChange("") →
        // search("") → clearSearch() → search results are lost. The checkable items
        // (One-Click Delete, PDF content search) are only relevant outside search mode,
        // so skipping here costs nothing in practice.
        if (viewModel.searchResults.value == null) {
            invalidateOptionsMenu()
        }
        // If progress was reset from Settings (reachable straight from this screen's menu), the
        // ViewModel re-seeds its in-memory review/expansion state from the cleared prefs; clear our
        // own transient walk state to match, so a reviewed/walked month no longer shows the Hide bar.
        if (viewModel.consumeResetIfPending()) {
            walk.reset()
        }
        // We ALWAYS trigger a refresh on resume. The ViewModel handles caching and
        // immediate filtering of deleted items using the static flight set.
        // This is necessary because the MediaViewerActivity may have deleted files.
        viewModel.loadMedia(forceRefresh = true)
    }

    override fun onPause() {
        super.onPause()
        // Remember the month at the top of the viewport so Home's "Resume" can bring the user
        // back here. Saved in onPause (not onStop) because it must land BEFORE Home's onStart →
        // load() reads it — otherwise Home would compute the resume target from a stale value.
        // Null in flat (size) mode or before the tree exists — skip then.
        currentVisibleMonthKey()?.let { viewModel.prefs.setLastViewedMonth(it) }
        stopChevronWave()
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
            onMonthToggle    = { monthKey ->
                // Opening a month (not collapsing) → land at its TOP once the list rebuilds, so the
                // user sees it from the first photo (the accordion/relayout would otherwise leave
                // the viewport deep in the month).
                val willExpand = adapter.currentList.none {
                    it is GalleryItem.Header && it.monthKey == monthKey && it.isExpanded
                }
                if (willExpand) pendingScrollToTopMonth = monthKey
                viewModel.toggleMonthExpansion(monthKey)
            },
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
                    // Build the explicit, ordered id list the viewer pages through, passed via
                    // the intent (the viewer has its own VM, so it can't read this one's flat
                    // list). Without it, lookup-by-id can fail and the viewer falls back to
                    // position 0 (the wrong photo).
                    val searchItems = viewModel.searchResults.value
                    val viewerIds: LongArray = if (searchItems != null) {
                        searchItems.filterIsInstance<GalleryItem.Media>().map { it.mediaItem.id }
                    } else {
                        // Confine to the tapped photo's month, in the grid's exact rendered order
                        // (in flat size-sorted mode monthKey is empty, so this spans the whole list).
                        val rendered = adapter.currentList.filterIsInstance<GalleryItem.Media>()
                        val monthKey = rendered.firstOrNull { it.mediaItem.id == item.id }?.monthKey
                        rendered.filter { it.monthKey == monthKey }.map { it.mediaItem.id }
                    }.toLongArray()
                    val intent = Intent(this, MediaViewerActivity::class.java).apply {
                        putExtra(MediaViewerActivity.EXTRA_START_ID, item.id)
                        putExtra(MediaViewerActivity.EXTRA_ID_LIST, viewerIds)
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
        baseRvBottomPad = binding.recyclerView.paddingBottom
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
        updateHideBar()
        invalidateOptionsMenu()
    }

    /**
     * The first-run demo is now launched by HomeActivity on app launch (mandatory until the user
     * opts out) — not from the gallery. Kept as a harmless no-op so the old call sites don't need
     * touching; remove in a later cleanup.
     */
    private fun tryShowOnboarding() { /* no-op — see HomeActivity */ }

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
        updateHideBar()
        invalidateOptionsMenu()
    }

    /**
     * Drive the bottom bar for the **currently open month** (bound to the open month, not scroll
     * position — see the accordion). It guides the user through the whole curation flow:
     *   • not fully reviewed yet (a section still unseen) + hint not retired → **review hint**
     *     ("Also open WhatsApp to review {Month}") so the user knows there's more to look at.
     *   • reviewed + NOT yet at the end + hint not retired → **scroll hint** ("Delete what you don't
     *     want — hide {Month} at the end").
     *   • reviewed + reached the end → live green **"Hide {Month}"** (tappable) + first-run coach-mark.
     *   • otherwise (e.g. hint retired, or nothing reviewable) → bar hidden.
     * All hints share the amber down-chevron wave and are retired together (first hide / dismiss).
     */
    private fun updateHideBar() {
        updateEndReached()
        val suppressed = binding.selectionBar.isVisible ||
            inSearchMode || viewModel.searchResults.value != null
        val k = if (suppressed) null else openMonthKey()
        if (k == null) { hideHideBar(); return }
        val footer = adapter.currentList.filterIsInstance<GalleryItem.Footer>().find { it.monthKey == k }
        if (footer == null) { hideHideBar(); return }
        val label = adapter.currentList.filterIsInstance<GalleryItem.Header>()
            .find { it.monthKey == k }?.label ?: ""
        val retired = viewModel.prefs.isScrollHintRetired()
        // "Reached the end" this session OR already walked through on a prior visit at the same item
        // count (no new photos) — so a fully-reviewed month doesn't demand re-scrolling on revisit.
        val reached = walk.isReached(k) || viewModel.prefs.isMonthWalked(k, footer.fullCount)
        val reviewMsg = reviewHintMessage(footer, label)
        when (HideBarDecision.decide(footer.showHideButton, reached, retired, reviewMsg != null)) {
            HideBarKind.HIDE          -> showLiveHideBar(k, label, footer.fullCount)
            HideBarKind.SCROLL_TEASER -> showHintBar("Delete junk as you scroll back and forth — hide $label at the end", false)
            HideBarKind.REVIEW_HINT   -> showHintBar(reviewMsg!!, reviewHintPointsUp(k, footer))
            HideBarKind.NONE          -> hideHideBar()
        }
    }

    /**
     * Which way the hint chevrons should point to reach the still-to-review section. Up when that
     * section sits in the upper half of (or above) the viewport — i.e. the user has to look up to it
     * (e.g. opening WhatsApp first leaves Camera above; or a WhatsApp-only month scrolled past). Down
     * otherwise (the section is below the middle / off the bottom).
     */
    private fun reviewHintPointsUp(monthKey: String, footer: GalleryItem.Footer): Boolean {
        val targetSub = when {
            footer.camPresent && !footer.camReviewed -> "$monthKey:cam"
            !footer.waReviewed                       -> "$monthKey:wa"
            else                                     -> return false
        }
        val pos = adapter.currentList.indexOfFirst {
            it is GalleryItem.SubHeader && it.subKey == targetSub
        }
        if (pos < 0) return false
        val lm = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return false
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < 0) return false
        return pos <= (first + last) / 2   // target in the upper half / above → point up
    }

    /** The single open (expanded) month, or null. With the accordion there is at most one. */
    private fun openMonthKey(): String? =
        adapter.currentList.filterIsInstance<GalleryItem.Header>()
            .firstOrNull { it.isExpanded }?.monthKey

    /** Message nudging the user to review the still-unseen section(s), or null if none apply. */
    private fun reviewHintMessage(footer: GalleryItem.Footer, label: String): String? {
        // WhatsApp present ⇒ the two-section (Camera & Others / WhatsApp) layout.
        if (footer.waPresent) {
            val remaining = buildList {
                if (footer.camPresent && !footer.camReviewed) add("Camera & Others")
                if (!footer.waReviewed) add("WhatsApp")
            }
            return when (remaining.size) {
                1 -> "Also open ${remaining[0]} to review $label"
                2 -> "Open both sections to review $label"
                else -> null
            }
        }
        // Flat month not fully reviewed ⇒ a type filter is off, hiding items the gate needs.
        return if (footer.camPresent && !footer.camReviewed)
            "Turn on all type filters to review $label" else null
    }

    private fun hideHideBar() {
        binding.hideMonthBarContainer.isVisible = false
        binding.coachHideTip.isVisible = false
        binding.teaserHideHint.isVisible = false
        stopChevronWave()
        applyBarInsets()
    }

    /**
     * Keep the list and the floating bottom bar separate: pad the RecyclerView's bottom by the bar's
     * height so the last months/year can scroll clear of the bar/coach-mark instead of hiding behind
     * it. (clipToPadding=false in the layout lets items scroll into the padding region.)
     */
    private fun applyBarInsets() {
        val rv = binding.recyclerView
        rv.post {
            val pad = if (binding.hideMonthBarContainer.isVisible)
                binding.hideMonthBarContainer.height else baseRvBottomPad
            if (rv.paddingBottom != pad) {
                rv.setPadding(rv.paddingLeft, rv.paddingTop, rv.paddingRight, pad)
            }
        }
    }

    private fun showLiveHideBar(key: String, label: String, fullCount: Int) {
        // Remember this month was fully reviewed + walked through (at this item count), so a later
        // visit can offer Hide immediately without forcing another scroll.
        viewModel.prefs.setMonthWalked(key, fullCount)
        stopChevronWave()
        binding.teaserHideHint.isVisible = false
        binding.btnHideMonthBar.isVisible = true
        binding.btnHideMonthBar.text = "Hide $label"
        binding.hideMonthBarContainer.isVisible = true
        binding.coachHideTip.isVisible = !viewModel.prefs.hasSeenHideCoach()
        if (binding.coachHideTip.isVisible) {
            binding.coachHideTip.setOnClickListener { dismissHideCoach() }
        }
        binding.btnHideMonthBar.setOnClickListener {
            dismissHideCoach()
            val parts = key.split("-")
            hideMonthWithUndo(parts.getOrNull(0)?.toIntOrNull() ?: 0,
                parts.getOrNull(1)?.toIntOrNull() ?: 0, label)
        }
        applyBarInsets()
    }

    private fun showHintBar(message: String, pointUp: Boolean) {
        binding.btnHideMonthBar.isVisible = false
        binding.coachHideTip.isVisible = false
        binding.tvTeaserText.text = message
        val glyph = if (pointUp) "▲" else "▼"
        if (binding.chev1.text != glyph) {
            binding.chev1.text = glyph; binding.chev2.text = glyph; binding.chev3.text = glyph
        }
        if (pointUp != hintPointsUp) { hintPointsUp = pointUp; stopChevronWave() }  // direction flip → restart wave
        binding.teaserHideHint.isVisible = true
        binding.hideMonthBarContainer.isVisible = true
        binding.btnTeaserDismiss.setOnClickListener {
            viewModel.prefs.setScrollHintRetired()
            updateHideBar()
        }
        startChevronWave()
        applyBarInsets()
    }

    /** Down-chevron "wave" (Vegas-marquee feel, tastefully): brightness sweeps top→bottom. */
    private fun startChevronWave() {
        if (chevronAnimator != null) return
        val chevs = listOf(binding.chev1, binding.chev2, binding.chev3)
        if (animationsDisabled()) { chevs.forEach { it.alpha = 1f }; return }
        val bobPx = 3f * resources.displayMetrics.density   // small downward bob amplitude
        chevronAnimator = android.animation.ValueAnimator.ofFloat(0f, 3f).apply {
            duration = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val phase = a.animatedValue as Float
                chevs.forEachIndexed { i, v ->
                    // Sweep top→bottom normally, bottom→top when pointing up.
                    val idx = if (hintPointsUp) (chevs.size - 1 - i) else i
                    val d = ((phase - idx) % 3f + 3f) % 3f        // 0 when the wave is on this chevron
                    v.alpha = (0.45f + 0.55f * (1f - d.coerceAtMost(1f))).coerceIn(0.45f, 1f)
                }
                // Gentle vertical bob synced with the wave — a physical "pull" toward the target.
                val dir = if (hintPointsUp) -1f else 1f
                binding.teaserChevrons.translationY =
                    dir * bobPx * kotlin.math.sin(phase / 3f * 2f * Math.PI).toFloat()
            }
            start()
        }
    }

    private fun stopChevronWave() {
        chevronAnimator?.cancel()
        chevronAnimator = null
        binding.teaserChevrons.translationY = 0f
    }

    private fun animationsDisabled(): Boolean = try {
        android.provider.Settings.Global.getFloat(
            contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    } catch (e: Exception) { false }

    private fun dismissHideCoach() {
        if (binding.coachHideTip.isVisible) binding.coachHideTip.isVisible = false
        viewModel.prefs.setSeenHideCoach()
        applyBarInsets()   // coach gone → bar shorter → reclaim the list padding
    }

    private fun hideMonthWithUndo(year: Int, month: Int, label: String) {
        viewModel.prefs.setScrollHintRetired()   // first successful hide → user understands; retire teaser
        viewModel.markMonthDone(year, month)
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, "$label hidden from this app", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction("Undo") { viewModel.restoreMonth(year, month) }
            .show()
    }

    private fun observeViewModel() {
        viewModel.galleryItems.observe(this) { items ->
            // Search takes over the list area — don't let a gallery refresh overwrite results/prompt
            if (inSearchMode || viewModel.searchResults.value != null) return@observe
            adapter.submitList(items)
            // A month the user just opened → land on its header (top of the month), not mid/end.
            // Structural changes apply synchronously in the adapter, but the RecyclerView still needs
            // a layout pass before positions are valid — so expand the app bar, then run the scroll
            // in doOnPreDraw (fires after the next layout, not on a guessed delay) so it lands
            // correctly even on a slow frame.
            pendingScrollToTopMonth?.let { key ->
                if (items.any { it is GalleryItem.Header && it.monthKey == key }) {
                    binding.appBarLayout.setExpanded(true, false)
                    binding.recyclerView.doOnPreDraw {
                        val curr = adapter.currentList
                        val hp = curr.indexOfFirst { it is GalleryItem.Header && it.monthKey == key }
                        val fp = curr.indexOfFirst { it is GalleryItem.Footer && it.monthKey == key }
                        if (hp >= 0) {
                            (binding.recyclerView.layoutManager as? GridLayoutManager)
                                ?.scrollToPositionWithOffset(hp, 0)
                        }
                        // Start the walk fresh FROM THE TOP: header just scrolled into view (seen),
                        // footer not yet — so a long month must still be scrolled to the end. This
                        // discards any latch from the transient pre-scroll viewport.
                        if (hp >= 0 && fp >= 0) walk.onOpenedAtTop(key, fp - hp)
                        pendingScrollToTopMonth = null
                        updateHideBarWhenSettled()
                    }
                }
            }
            val isLoading = viewModel.isLoading.value ?: false
            binding.tvEmpty.isVisible = items.isEmpty() && !isLoading
            if (items.isEmpty() && !isLoading) binding.tvEmpty.text = resolveEmptyMessage()
            // Note: the "walked through" latch is NOT cleared here — updateEndReached() re-earns it
            // only when the open month or its rendered length actually changes, so frequent
            // background refreshes (indexing/hashing) don't wipe the user's scroll progress.
            binding.recyclerView.post { updateStickyHeader() }
            updateHideBarWhenSettled()
            tryShowOnboarding()

            // Home "Resume" deep-link: land on the target month once the tree is built. Resolution:
            //   1. the target month (last-viewed, if still visible) — scroll to it;
            //   2. else the topmost OPEN (expanded) month — scroll to it;
            //   3. else nothing — just show the tree from the top.
            // One-shot: cleared after the first real (non-empty) load so later refreshes don't yank.
            pendingResumeKey?.let { key ->
                if (items.none { it is GalleryItem.Header }) return@let   // tree not ready yet
                var pos = items.indexOfFirst { it is GalleryItem.Header && it.monthKey == key }
                if (pos < 0) pos = items.indexOfFirst { it is GalleryItem.Header && (it as GalleryItem.Header).isExpanded }
                if (pos >= 0) {
                    (binding.recyclerView.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(pos, 0)
                    binding.appBarLayout.setExpanded(true, false)
                }
                pendingResumeKey = null
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
                    binding.stickyHeader.visibility = android.view.View.GONE
                    viewModel.galleryItems.value?.let { adapter.submitList(it) }
                    binding.tvEmpty.isVisible = false
                }
            } else {
                // In search mode
                binding.settingsBar.isVisible = false
                binding.stickyHeader.visibility = android.view.View.GONE
                adapter.submitList(results)
                val isLoading = viewModel.isLoading.value ?: false
                binding.tvEmpty.isVisible = results.isEmpty() && !isLoading
                if (results.isEmpty() && !isLoading) {
                    binding.tvEmpty.text = "No results — try different keywords\nor check for typos"
                }
            }
            updateHideBar()
        }
        
        viewModel.isLoading.observe(this) { loading ->
            // Only spin on a genuine first/empty load. Sort, filter, refresh and the post-delete
            // background re-syncs all reload with data already on screen — showing the spinner
            // then would just flash it repeatedly as the view changes.
            binding.progressBar.isVisible = loading && adapter.currentList.isEmpty()
            if (loading) binding.tvEmpty.isVisible = false  // never show empty state while loading
            if (!loading) tryShowOnboarding()               // app is ready — maybe show first-run intro
        }
        
        viewModel.sortMode.observe(this) { _ ->
            invalidateOptionsMenu()
            // Don't overwrite the subtitle if the SearchView is currently expanded
            if (viewModel.searchResults.value == null) updateSortSubtitle()
        }

        // Soft-delete is silent now (no per-delete snackbar). Undo is the toolbar ↶ icon and the
        // overflow "Restore last deleted"; Trash is the catch-all. Keep them in sync below.

        // Validation finished — reveal the (now-confirmed) "Restore last deleted (N)" item.
        viewModel.lastBatchSize.observe(this) { batchValidating = false; invalidateOptionsMenu() }
        
        viewModel.deletePermissionRequest.observe(this) { intentSender ->
            intentSender?.let {
                deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
            }
        }
        // Unhiding now lives on the dedicated HiddenActivity (Home → "Hidden months"),
        // so the gallery no longer hosts an unhide panel.

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
        // Persistent quick-undo: only while the last delete batch is still recoverable.
        val n = viewModel.lastBatchSize.value ?: 0
        menu.findItem(R.id.action_restore_last)?.let {
            // Show only when there's a validated recoverable batch AND we're not multi-selecting
            // (during selection the user is choosing what to delete, not undoing).
            it.isVisible = n > 0 && !batchValidating && !inSelection
            it.title = "Restore last deleted ($n)"
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
        R.id.action_restore_last -> { viewModel.restoreLastBatch(); showToast("Restoring last deleted…"); true }
        R.id.action_stats_info -> { StatsDialog.present(this); true }
        R.id.action_help -> { startActivity(Intent(this, HelpActivity::class.java)); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Assemble device info + app state + the ring log, hand to a share sheet. */
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
            && !viewModel.prefs.wasAllFilesPromptShown()   // Home already asked up front
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
            return "All months are hidden!\nUse \"Hidden months\" on the Home screen to bring them back."
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

    // ── Move to album ─────────────────────────────────────────────────────────

    private fun showAlbumPicker(items: List<MediaItem>) {
        lifecycleScope.launch {
            val albums = fetchAlbums()
            if (albums.isEmpty()) { showToast("No albums found"); return@launch }
            val names = albums.keys.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
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
            "Switched $moved · $skipped already in $targetName"
        else
            "Switched $moved item${if (moved == 1) "" else "s"} to $targetName"
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

    /**
     * Latch "the open month has actually been walked through" once BOTH its Header and its Footer
     * have been on screen since the last list rebuild. A short month shows both at once (no scroll
     * needed); a long one requires scrolling from top to bottom. This deliberately ignores a footer
     * that is only *incidentally* visible after expanding a section above (the header won't have been
     * seen yet) — so the user can't be handed Hide without passing the newly-opened content. The
     * seen-flags reset on each rebuild (see the galleryItems observer).
     */
    /**
     * Refresh the Hide bar once any in-flight item animations (accordion expand/collapse, inserts)
     * have finished, so [updateEndReached] evaluates against the settled list — not a transient
     * where a long month's footer is momentarily near its header. Fires immediately if nothing is
     * animating (e.g. a month that genuinely fits still gets Hide right away).
     */
    private fun updateHideBarWhenSettled() {
        val rv = binding.recyclerView
        val animator = rv.itemAnimator
        if (animator != null) {
            animator.isRunning { rv.post { updateHideBar() } }
        } else {
            rv.post { updateHideBar() }
        }
    }

    private fun updateEndReached() {
        // A month was just opened and we're about to scroll its header to the top — don't evaluate
        // "reached end" against the transient pre-scroll viewport (its footer may be momentarily
        // visible). The scroll-to-top callback re-establishes a fresh walk once it lands.
        if (pendingScrollToTopMonth != null) return
        // Don't evaluate "reached end" against a list that's mid-rebuild or animating. During the
        // accordion expand the newly-opened month's items insert/animate in, and for a moment its
        // footer sits just below the header (only a few rows laid out) — evaluating then would latch
        // the footer as "seen" and wrongly offer Hide without any scroll. Re-evaluated once the
        // animations settle (see [updateHideBarWhenSettled], called after each rebuild).
        val rv = binding.recyclerView
        if (rv.isAnimating || rv.isComputingLayout) return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val list = adapter.currentList
        val openMonth = list.filterIsInstance<GalleryItem.Header>()
            .firstOrNull { it.isExpanded }?.monthKey ?: return
        val headerPos = list.indexOfFirst { it is GalleryItem.Header && it.monthKey == openMonth }
        val footerPos = list.indexOfFirst { it is GalleryItem.Footer && it.monthKey == openMonth }
        if (headerPos < 0 || footerPos < 0) return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < 0) return
        // Latch/reset logic (fresh walk on month or span change, header/footer edge detection) lives
        // in the pure [WalkLatch] — see its docs and CurationLogicTest.
        walk.onViewport(openMonth, headerPos, footerPos, first, last)
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

}
