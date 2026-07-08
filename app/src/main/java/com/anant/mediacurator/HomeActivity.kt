package com.anant.mediacurator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.anant.mediacurator.databinding.ActivityHomeBinding
import com.anant.mediacurator.databinding.ItemHomeCardBinding

/**
 * Intent-led home hub.
 *
 * Stage 2: live data via [HomeViewModel] + the shared [MediaCache] (hero state, summary,
 * card hints). Stage 3 adds the resume deep-link; Stage 4 a dedicated SearchActivity.
 *
 * NOT the launcher yet — opened from MainActivity's "Try new Home" menu item.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()

    private val prefs by lazy { PreferencesManager(this) }

    // Guards so the per-onStart bootstrap does its file work at most once per session.
    private var launchedAllFilesSettings = false
    private var restoreChecked = false
    private var restoreInFlight = false   // backup read running → don't let a re-entrant call show the demo early

    // Home is the launcher, so it owns BOTH permission requests: the READ_MEDIA gate below,
    // and (up front) the All-files-access prompt that the gallery used to ask lazily. Asking
    // here means the hidden-months list and Cleaned-up counter restore from Downloads BEFORE
    // the gallery is opened, so Home's stats are correct immediately after a reinstall.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.load(); afterMediaAccessSought() }

    // Place search is on by default → ask for photo-location access once (indexing checks it later).
    private val mediaLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        launchedAllFilesSettings = false
        maybeRestoreThenShowDemo()   // All-files decided — now the restore offer, then the demo
    }

    // The mandatory first-run demo plays AFTER every access request has been sought (media
    // permission + All-files access) AND after the restore offer has been shown/resolved.
    private val demoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.btnHeroAction.setOnClickListener { openResume() }
        binding.cardHero.setOnClickListener { openResume() }

        bindCard(binding.cardSpace, R.drawable.ic_home_space, "Free up space", "Biggest files first") {
            openGallery(sort = SortMode.SIZE_ABSOLUTE.name)
        }
        bindCard(binding.cardDuplicates, R.drawable.ic_home_duplicate, "Find duplicates", "…") {
            startActivity(Intent(this, DuplicatesActivity::class.java))
        }
        bindCard(binding.cardSearch, R.drawable.ic_home_search, "Search", "Name, content, PDF text") {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        // Place browse entry points (A = flat chips, B = drill-down).
        binding.chipSearchA.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java)
                .putExtra(SearchActivity.EXTRA_PLACE_BROWSE_MODE, "A"))
        }
        binding.chipSearchB.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java)
                .putExtra(SearchActivity.EXTRA_PLACE_BROWSE_MODE, "B"))
        }
        bindCard(binding.cardHidden, R.drawable.ic_home_hidden, "Hidden months", "…") {
            startActivity(Intent(this, HiddenActivity::class.java))
        }
        bindCard(binding.cardTrash, R.drawable.ic_home_trash, "Trash", "…") {
            startActivity(Intent(this, TrashActivity::class.java))
        }

        viewModel.state.observe(this) { s -> currentState = s; render(s) }
    }

    private var currentState: HomeState? = null

    override fun onStart() {
        super.onStart()
        // Media-access request comes FIRST. If it's already granted (returning launch), we skip
        // straight to afterMediaAccessSought(); otherwise the system dialog is shown and its
        // callback lands us there. Either way the demo plays only once access has been sought.
        if (hasMediaPermissions()) {
            viewModel.load()   // refresh on return (e.g. after hiding months / deleting)
            afterMediaAccessSought()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
        updatePlaceChips()
    }

    /**
     * Gate the place-browse chips: until at least one place has been indexed, browsing them just
     * shows an empty screen — so dim + disable them and explain, rather than confuse. Enables once
     * places exist (indexing runs as the user browses the gallery).
     */
    private fun updatePlaceChips() {
        val chips = listOf(binding.chipSearchA, binding.chipSearchB)
        if (!prefs.isPlaceSearchEnabled()) {
            chips.forEach { it.isEnabled = false; it.alpha = 0.5f }
            binding.tvPlaceLabel.text = "Place search is off — turn it on in Settings"
            return
        }
        Thread {
            val n = PlaceStore.getInstance(this).let { it.ensureLoaded(); it.locatedCount() }
            runOnUiThread {
                val ready = n > 0
                chips.forEach { it.isEnabled = ready; it.alpha = if (ready) 1f else 0.5f }
                binding.tvPlaceLabel.text =
                    if (ready) "Search by place" else "Places appear here as you browse your gallery"
            }
        }.start()
    }

    /**
     * Called once the media-permission request has been sought (granted or denied). The SECOND
     * access request — All-files access — comes next; only after BOTH have been sought does the
     * mandatory demo play (see [maybeShowDemoThenRestore]).
     */
    private fun afterMediaAccessSought() {
        maybeRequestMediaLocation()
        requestAllFilesAccess()
    }

    /** Ask for ACCESS_MEDIA_LOCATION once, up front, since place search is on by default. */
    private fun maybeRequestMediaLocation() {
        if (!prefs.isPlaceSearchEnabled() || prefs.wasMediaLocationAsked() || !hasMediaPermissions()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION)
            == PackageManager.PERMISSION_GRANTED) return
        prefs.setMediaLocationAsked()
        mediaLocationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    /**
     * The All-files access request (the second access prompt). On Android 11+ without the
     * permission, show the rationale ONCE and send the user to Settings. Once it's decided —
     * or if it isn't needed — we fall through to the restore offer, then the demo.
     */
    private fun requestAllFilesAccess() {
        val needsAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
        if (needsAllFiles && !prefs.wasAllFilesPromptShown()) {
            prefs.setAllFilesPromptShown()
            showAllFilesRationale()
        } else {
            maybeRestoreThenShowDemo()
        }
    }

    /**
     * With every access request sought, run the restores FIRST — the deletion counter (sync) and the
     * hidden-months backup, which may offer "Restore your progress?" — and only once that offer has
     * been shown/resolved play the mandatory demo. This puts the restore prompt BEFORE the demo.
     */
    private fun maybeRestoreThenShowDemo() {
        DeletionStatsStore.getInstance(this).ensureRestored()
        // Re-entrant: the all-files Settings return fires BOTH the launcher callback and onStart. If a
        // backup read is already running, don't let the second call race ahead and launch the demo
        // before the pending restore offer — the read's completion shows the demo once it's decided.
        if (restoreInFlight) return
        if (restoreChecked) { showDemoIfNeeded(); return }
        restoreChecked = true
        // Only look for a hidden-months backup if we haven't already offered, and there are no
        // hidden months locally (fresh install / reinstall). Read off the main thread.
        if (prefs.wasHiddenRestoreOffered() || prefs.getDoneMonths().isNotEmpty()) {
            showDemoIfNeeded(); return
        }
        restoreInFlight = true
        Thread {
            val months = HiddenMonthsBackup.read(this)
            // The durable "Don't show again" marker survives reinstall (SharedPreferences doesn't);
            // re-apply it here so a returning user who opted out isn't shown the demo again.
            val optedOutBefore = OnboardingMarker.exists(this)
            runOnUiThread {
                restoreInFlight = false
                if (optedOutBefore) prefs.setDemoDisabled()
                if (months != null) offerHiddenRestore(months) { showDemoIfNeeded() }
                else showDemoIfNeeded()
            }
        }.start()
    }

    /** Play the mandatory demo once per launch, unless the user opted out. */
    private fun showDemoIfNeeded() {
        if (!demoShownThisLaunch && !prefs.isDemoDisabled()) {
            demoShownThisLaunch = true
            demoLauncher.launch(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun showAllFilesRationale() {
        AlertDialog.Builder(this)
            .setTitle("Allow file access")
            .setMessage(
                "Media Curator works best with \"All files access\". It lets the app:\n\n" +
                "•  show your PDF files\n" +
                "•  keep your curation progress and history across reinstalls\n\n" +
                "Nothing leaves your device without your permission."
            )
            .setPositiveButton("Allow") { _, _ ->
                launchedAllFilesSettings = true
                allFilesLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
            .setNegativeButton("Not now", null)
            // Fires for Not-now / back / outside-tap. Allow also dismisses, but then the
            // Settings screen is handling it — allFilesLauncher continues on return.
            .setOnDismissListener {
                if (!launchedAllFilesSettings) maybeRestoreThenShowDemo()
            }
            .show()
    }

    private fun offerHiddenRestore(months: Set<String>, onDone: () -> Unit) {
        if (isFinishing || isDestroyed) { onDone(); return }
        if (prefs.getDoneMonths().isNotEmpty()) { onDone(); return }   // raced — already restored
        prefs.setHiddenRestoreOffered()
        AlertDialog.Builder(this)
            .setTitle("Restore your progress?")
            .setMessage(
                "Found a saved list of ${months.size} hidden month${if (months.size > 1) "s" else ""} " +
                "from a previous install. Hide them again so you can pick up where you left off?"
            )
            .setPositiveButton("Restore") { _, _ ->
                prefs.setDoneMonths(months)
                viewModel.load()   // recompute reviewed / hidden counts
            }
            .setNegativeButton("Not now", null)
            .setOnDismissListener { onDone() }
            .show()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasMediaPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        else
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun render(s: HomeState) {
        binding.tvLibrarySummary.text = s.summary
        binding.tvHeroTitle.text      = s.heroTitle
        binding.tvHeroResume.text     = s.resumeLabel
        binding.btnHeroAction.text    = s.heroButton
        binding.tvHeroCaption.text    = s.heroCaption
        binding.tvHeroCaption.isVisible = s.heroCaption.isNotEmpty()

        if (s.heroProgress >= 0) {
            binding.heroProgressRow.isVisible = true
            binding.progressHero.progress = s.heroProgress
            binding.tvHeroPct.text = s.heroProgressLabel
        } else {
            binding.heroProgressRow.isVisible = false
        }

        binding.cardDuplicates.tvCardSub.text = s.dupSub
        binding.cardHidden.tvCardSub.text     = s.hiddenSub
        binding.cardTrash.tvCardSub.text      = s.trashSub
        // Nothing to open when the trash is empty — dim and disable the card.
        binding.cardTrash.root.isEnabled   = !s.trashEmpty
        binding.cardTrash.root.isClickable = !s.trashEmpty
        binding.cardTrash.root.alpha       = if (s.trashEmpty) 0.5f else 1f
    }

    private fun bindCard(card: ItemHomeCardBinding, icon: Int, title: String, sub: String, onClick: () -> Unit) {
        card.ivCardIcon.setImageResource(icon)
        card.tvCardTitle.text = title
        card.tvCardSub.text = sub
        card.root.setOnClickListener { onClick() }
    }

    /** Hero action — resume into the next un-curated month if we know it. */
    private fun openResume() {
        openGallery(resumeKey = currentState?.resumeMonthKey)
    }

    private fun openGallery(
        resumeKey: String? = null, sort: String? = null,
        openSearch: Boolean = false, showStats: Boolean = false
    ) {
        val intent = Intent(this, MainActivity::class.java).putExtra(EXTRA_FROM_HOME, true)
        if (resumeKey != null) intent.putExtra(EXTRA_RESUME_MONTH_KEY, resumeKey)
        if (sort != null) intent.putExtra(EXTRA_SORT, sort)
        if (openSearch) intent.putExtra(EXTRA_OPEN_SEARCH, true)
        if (showStats) intent.putExtra(EXTRA_SHOW_STATS, true)
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_home, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_home_stats -> { StatsDialog.present(this); true }
        R.id.action_home_help  -> { startActivity(Intent(this, HelpActivity::class.java)); true }
        R.id.action_settings   -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        // Once per process: the mandatory demo auto-shows only on a genuine app launch, not every
        // time Home returns to the foreground.
        private var demoShownThisLaunch = false

        /** Set when the gallery is opened from the hub so it shows an Up arrow back to Home. */
        const val EXTRA_FROM_HOME = "extra_from_home"
        /** Month key ("YYYY-MM") to scroll to on open (hero "Resume"). */
        const val EXTRA_RESUME_MONTH_KEY = "extra_resume_month_key"
        /** SortMode name to apply on open (e.g. "Free up space" → SIZE_ABSOLUTE). */
        const val EXTRA_SORT = "extra_sort"
        /** Expand the gallery's search on open (interim until a dedicated SearchActivity). */
        const val EXTRA_OPEN_SEARCH = "extra_open_search"
        /** Show the stats dialog on open (Home "Hidden & stats" card). */
        const val EXTRA_SHOW_STATS = "extra_show_stats"
    }
}
