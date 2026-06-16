package com.anant.mediacurator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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

    // Home is the launcher now, so it owns the media-permission request. The gallery's own
    // bootstrap (all-files prompt, auto-restore, background indexing/hashing) still runs when
    // the gallery opens — which is the immediate next step in the curation flow.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.load() }

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
            openGallery()   // Stage 4: dedicated SearchActivity
        }
        bindCard(binding.cardHidden, R.drawable.ic_home_hidden, "Hidden & stats", "…") {
            openGallery()   // Stage 3b: deep-link to unhide / Stats
        }

        viewModel.state.observe(this) { s -> currentState = s; render(s) }
    }

    private var currentState: HomeState? = null

    override fun onStart() {
        super.onStart()
        if (hasMediaPermissions()) {
            viewModel.load()   // refresh on return (e.g. after hiding months / deleting)
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
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

    private fun openGallery(resumeKey: String? = null, sort: String? = null) {
        val intent = Intent(this, MainActivity::class.java).putExtra(EXTRA_FROM_HOME, true)
        if (resumeKey != null) intent.putExtra(EXTRA_RESUME_MONTH_KEY, resumeKey)
        if (sort != null) intent.putExtra(EXTRA_SORT, sort)
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_home, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_home_search -> { openGallery(); true }   // Stage 4: SearchActivity
        R.id.action_home_help   -> { Toast.makeText(this, "Help (coming soon)", Toast.LENGTH_SHORT).show(); true }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        /** Set when the gallery is opened from the hub so it shows an Up arrow back to Home. */
        const val EXTRA_FROM_HOME = "extra_from_home"
        /** Month key ("YYYY-MM") to scroll to on open (hero "Resume"). */
        const val EXTRA_RESUME_MONTH_KEY = "extra_resume_month_key"
        /** SortMode name to apply on open (e.g. "Free up space" → SIZE_ABSOLUTE). */
        const val EXTRA_SORT = "extra_sort"
    }
}
