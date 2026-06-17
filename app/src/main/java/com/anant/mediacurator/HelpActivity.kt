package com.anant.mediacurator

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.anant.mediacurator.databinding.ActivityHelpBinding
import com.anant.mediacurator.databinding.ItemHelpCardBinding

/**
 * Visual, scannable help — a card per feature instead of one wall of text.
 * Opened from Home's overflow and the gallery's Help menu item.
 */
class HelpActivity : AppCompatActivity() {

    private data class Feature(val icon: Int, val title: String, val desc: String)

    private val features = listOf(
        Feature(R.drawable.ic_sort, "Browse, filter & sort",
            "Photos, videos, audio and PDFs are grouped by year and month. Tap the type cards to filter; tap the sort chip to reorder (oldest, newest, largest…)."),
        Feature(R.drawable.ic_home_hidden, "Hide a month",
            "Done reviewing a month? Hide it — it leaves the app but stays in your phone's gallery. Unhide it anytime from the Hidden months screen."),
        Feature(R.drawable.ic_home_duplicate, "Find duplicates",
            "Finds exact duplicate photos and videos so you can delete the copies and reclaim space. The best copy in each group is pre-selected to keep."),
        Feature(R.drawable.ic_home_search, "Search",
            "Find items by file name or text inside PDFs. Typos are handled, and multi-word queries match all terms."),
        Feature(R.drawable.ic_check, "Select & act",
            "Long-press any item to start selecting, tap more to add them, then Share, Move, or Delete the selection. Press Back to cancel."),
        Feature(R.drawable.ic_info, "Stats anywhere",
            "Tap the ⓘ icon on any screen for counts and sizes per type, plus your lifetime Cleaned Up total — items deleted and space freed."),
        Feature(R.drawable.ic_lock, "Private by design",
            "Everything runs on your device — labels, search and PDF text are all computed locally. No internet, no accounts, no ads.")
    )

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "How it works"

        binding.tvVersion.text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        features.forEach { f ->
            val card = ItemHelpCardBinding.inflate(layoutInflater, binding.helpContainer, false)
            card.ivIcon.setImageResource(f.icon)
            card.tvTitle.text = f.title
            card.tvDesc.text = f.desc
            binding.helpContainer.addView(card.root)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
