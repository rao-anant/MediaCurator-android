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

    // Home is the launcher, so it owns BOTH permission requests: the READ_MEDIA gate below,
    // and (up front) the All-files-access prompt that the gallery used to ask lazily. Asking
    // here means the hidden-months list and Cleaned-up counter restore from Downloads BEFORE
    // the gallery is opened, so Home's stats are correct immediately after a reinstall.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.load(); bootstrapStorage() }

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        launchedAllFilesSettings = false
        runRestores()   // permission decided — restore stats + hidden months with file access
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        bindCard(binding.cardHidden, R.drawable.ic_home_hidden, "Hidden months", "…") {
            openGallery()    // gallery hosts the unhide UI; stats is separate now
        }

        viewModel.state.observe(this) { s -> currentState = s; render(s) }
    }

    private var currentState: HomeState? = null

    override fun onStart() {
        super.onStart()
        if (hasMediaPermissions()) {
            viewModel.load()   // refresh on return (e.g. after hiding months / deleting)
            bootstrapStorage()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    /**
     * Up-front storage bootstrap (runs once media permission is granted):
     *  1. On Android 11+ without All-files access, show the rationale ONCE and send the user
     *     to Settings. PDFs and cross-reinstall restore both need this permission.
     *  2. Either way, restore the lifetime Cleaned-up counter and the hidden-months list from
     *     their Downloads backups, so Home's stats are correct before the gallery is opened.
     */
    private fun bootstrapStorage() {
        val needsAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
        if (needsAllFiles && !prefs.wasAllFilesPromptShown()) {
            prefs.setAllFilesPromptShown()
            showAllFilesRationale()
        } else {
            runRestores()
        }
    }

    private fun showAllFilesRationale() {
        AlertDialog.Builder(this)
            .setTitle("Allow file access")
            .setMessage(
                "Media Curator works best with \"All files access\". It lets the app:\n\n" +
                "•  show your PDF files\n" +
                "•  keep your curation progress and Curated history across reinstalls\n\n" +
                "Nothing is ever uploaded — everything stays on your device."
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
            // Settings screen is handling it — allFilesLauncher runs the restore on return.
            .setOnDismissListener {
                if (!launchedAllFilesSettings) runRestores()
            }
            .show()
    }

    /** Restore the deletion counter (sync, internally guarded) and the hidden-months list. */
    private fun runRestores() {
        DeletionStatsStore.getInstance(this).ensureRestored()
        if (restoreChecked) return
        restoreChecked = true
        // Only look for a hidden-months backup if we haven't already offered, and there are
        // no hidden months locally (fresh install / reinstall). Read off the main thread.
        if (prefs.wasHiddenRestoreOffered() || prefs.getDoneMonths().isNotEmpty()) return
        Thread {
            val months = HiddenMonthsBackup.read(this)
            if (months != null) runOnUiThread { offerHiddenRestore(months) }
        }.start()
    }

    private fun offerHiddenRestore(months: Set<String>) {
        if (isFinishing || isDestroyed) return
        if (prefs.getDoneMonths().isNotEmpty()) return   // raced with the gallery — already restored
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
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
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
