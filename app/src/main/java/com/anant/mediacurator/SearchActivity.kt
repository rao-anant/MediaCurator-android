package com.anant.mediacurator

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
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

    private var showingPrompt = true            // no query yet → browse chips may show
    private var records: List<PlaceRecord> = emptyList()
    private var mode = "A"                       // "A" flat cities · "B" country→state→city drill
    private var drillCountry: String? = null     // Option B drill state
    private var drillState: String? = null
    private var drillCity: String? = null        // set when a drilled city's photos are shown
    private var currentQuery = ""
    private var placeSort = PlaceSort.COUNT
    private val countryFlags: Map<String, String> by lazy { loadCountryFlags() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                currentQuery = newText.orEmpty()
                viewModel.search(currentQuery); return true
            }
        })

        mode = intent.getStringExtra(EXTRA_PLACE_BROWSE_MODE) ?: "A"
        binding.chipSort.setOnClickListener {
            placeSort = if (placeSort == PlaceSort.COUNT) PlaceSort.NAME else PlaceSort.COUNT
            binding.chipSort.text = if (placeSort == PlaceSort.COUNT) "Sort: most photos" else "Sort: A–Z"
            renderBrowse()
        }
        // Phone Back goes UP one level in every mode (search → browse → up the drill / collapse the
        // tree), only exiting at the top. hideKeyboard() so the keyboard can't swallow the press.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    currentQuery.isNotEmpty() -> { drillCity = null; binding.searchView.setQuery("", false); hideKeyboard() }
                    drillState != null        -> { drillState = null; renderBrowse() }
                    drillCountry != null      -> { drillCountry = null; renderBrowse() }
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() } // exit to Home
                }
            }
        })

        viewModel.results.observe(this) { res ->
            when {
                res == null -> {                       // no query yet → prompt
                    adapter.submitList(emptyList())
                    binding.tvEmpty.text =
                        if (PreferencesManager(this).isPlaceSearchEnabled())
                            "Search file names, PDF text,\nand places"
                        else "Search file names\nand text inside PDFs"
                    binding.tvEmpty.isVisible = true
                    binding.tvResultsHeader.isVisible = false
                }
                res.isEmpty() -> {                     // searched, nothing matched
                    adapter.submitList(emptyList())
                    binding.tvEmpty.text = "No results — try different keywords\nor check for typos"
                    binding.tvEmpty.isVisible = true
                    binding.tvResultsHeader.isVisible = false
                }
                else -> {
                    binding.tvEmpty.isVisible = false
                    adapter.submitList(res)
                    if (mode == "B" && drillCity != null) {          // drilled city → clickable path
                        binding.tvResultsHeader.isVisible = false
                        binding.tvPlacesLabel.isVisible = false
                        binding.chipSort.isVisible = false
                        buildBreadcrumb(includeCity = true, countSuffix = "   ·   ${res.size} photos")
                        binding.browseHeader.isVisible = true
                    } else {
                        val place = placeLabelFor(currentQuery)     // Option A / typed → simple header
                        if (place != null) {
                            binding.tvResultsHeader.text = "📍 $place · ${res.size} photos"
                            binding.tvResultsHeader.isVisible = true
                        } else binding.tvResultsHeader.isVisible = false
                    }
                }
            }
            showingPrompt = res == null
            renderBrowse()
        }

        // Per-photo place records → browse chips (Option A cities / Option B drill-down).
        viewModel.placeRecords.observe(this) { records = it; renderBrowse() }
        viewModel.loadPlaces()

        binding.btnStats.setOnClickListener { StatsDialog.present(this) }

        // Auto-focus + keyboard only for plain search; place-browse launches start on the chips/tree
        // (otherwise the keyboard swallows the first Back press instead of walking up a level).
        if (!intent.hasExtra(EXTRA_PLACE_BROWSE_MODE)) {
            binding.searchView.isIconified = false
            binding.searchView.requestFocus()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Place browse (Option A cities · Option B country → state → city drill) ──────

    private fun renderBrowse() {
        if (!showingPrompt) {                    // searching → hide browse UI (results own tvEmpty)
            // …except keep the breadcrumb visible over a drilled city's photos (set by the observer).
            binding.browseHeader.isVisible = (mode == "B" && drillCity != null)
            binding.chipScroll.isVisible = false
            return
        }
        val active = records.isNotEmpty()
        binding.tvEmpty.isVisible = !active      // prompt only when there's nothing to browse
        binding.browseHeader.isVisible = active
        binding.chipScroll.isVisible = active
        if (!active) return
        binding.tvPlacesLabel.isVisible = true
        binding.chipSort.isVisible = true
        binding.chipSort.text = if (placeSort == PlaceSort.COUNT) "Sort: most photos" else "Sort: A–Z"
        if (mode == "B") {
            binding.tvPlacesLabel.text = "Drill down by location"
            binding.chipPlaces.removeAllViews()
            renderDrill(binding.chipPlaces)
        } else {
            binding.tvPlacesLabel.text = "Browse by location"
            binding.tvBreadcrumb.isVisible = false
            binding.chipPlaces.removeAllViews()
            for (p in PlaceBrowse.cities(records, placeSort).take(300))
                addPlaceChip(binding.chipPlaces, p, prefix = "🏙️ ") { runSearch(p.name) }
        }
        // Move focus OFF the SearchView so phone Back reaches our handler. A focused SearchView eats
        // Back to clear its own focus, and clearFocus() alone re-focuses it (it's the only focusable)
        // — so hand focus to the (focusable) browse header instead.
        if (currentQuery.isEmpty()) { binding.searchView.clearFocus(); binding.browseHeader.requestFocus() }
    }

    private fun renderDrill(group: com.google.android.material.chip.ChipGroup) {
        val country = drillCountry
        val state = drillState
        buildBreadcrumb(includeCity = false, countSuffix = "")
        when {
            country == null -> {                    // level 1: countries
                val countries = PlaceBrowse.countries(records, placeSort)
                for (p in countries.take(60)) {
                    val flag = countryFlags[p.name]
                    addPlaceChip(group, p, prefix = if (flag.isNullOrEmpty()) "" else "$flag ", colored = false) {
                        drillCountry = p.name; drillState = null; drillCity = null; renderBrowse()
                    }
                }
            }
            state == null -> {                      // level 2: states — or cities if it's a city-state
                // NB: no "auto-descend when only one" — it would re-descend on Back and trap the user.
                val states = PlaceBrowse.states(records, country, placeSort)
                if (states.isEmpty()) {
                    for (p in PlaceBrowse.citiesInCountry(records, country, placeSort).take(60))
                        addPlaceChip(group, p, prefix = "🏙️ ") { drillCity = p.name; runSearch(p.name) }
                } else {
                    for (p in states.take(60)) addPlaceChip(group, p, prefix = "🚩 ") { drillState = p.name; drillCity = null; renderBrowse() }
                }
            }
            else -> {                               // level 3: cities → tap shows photos
                for (p in PlaceBrowse.citiesIn(records, country, state, placeSort).take(60))
                    addPlaceChip(group, p, prefix = "🏙️ ") { drillCity = p.name; runSearch(p.name) }
            }
        }
    }

    /**
     * Teal clickable path — e.g. "Germany › Hesse › Kelsterbach". Shown while drilling and over the
     * photos. Each segment navigates: country → its states, state → its cities, city → its photos.
     */
    private fun buildBreadcrumb(includeCity: Boolean, countSuffix: String) {
        val c = drillCountry
        if (c == null) { binding.tvBreadcrumb.isVisible = false; return }
        val sb = android.text.SpannableStringBuilder()
        fun seg(text: String, onClick: () -> Unit) {
            val start = sb.length
            sb.append(text)
            sb.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(w: android.view.View) { onClick() }
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = getColor(R.color.primary); ds.isUnderlineText = false
                }
            }, start, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        seg(c) { drillState = null; drillCity = null; clearToBrowse() }
        drillState?.let { s -> sb.append("   ›   "); seg(s) { drillCity = null; clearToBrowse() } }
        if (includeCity) drillCity?.let { ci -> sb.append("   ›   "); seg(ci) { runSearch(ci) } }
        if (countSuffix.isNotEmpty()) sb.append(countSuffix)
        binding.tvBreadcrumb.text = sb
        binding.tvBreadcrumb.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        binding.tvBreadcrumb.isVisible = true
    }

    private fun clearToBrowse() {
        if (currentQuery.isNotEmpty()) { binding.searchView.setQuery("", false); hideKeyboard() }
        else renderBrowse()
    }

    private fun addPlaceChip(
        group: com.google.android.material.chip.ChipGroup,
        p: PlaceCount,
        prefix: String = "",
        colored: Boolean = true,   // country chips are left neutral so the flag emoji stays crisp
        onClick: () -> Unit
    ) {
        if (p.name.isBlank()) return
        group.addView(com.google.android.material.chip.Chip(this).apply {
            text = "$prefix${p.name} · ${p.count}"
            isClickable = true; isCheckable = false
            if (colored) {
                chipBackgroundColor =
                    android.content.res.ColorStateList.valueOf(PALETTE[Math.floorMod(p.name.hashCode(), PALETTE.size)])
                setTextColor(android.graphics.Color.WHITE)
            }
            setOnClickListener { onClick() }
        })
    }

    // ── Flags + colour ──────────────────────────────────────────────────────────────
    private fun loadCountryFlags(): Map<String, String> = try {
        assets.open("country_codes.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull {
                val t = it.split('\t'); if (t.size >= 2) t[0] to flagEmoji(t[1]) else null
            }.toMap()
        }
    } catch (e: Exception) { emptyMap() }

    private fun flagEmoji(cc: String): String {
        if (cc.length != 2 || !cc[0].isLetter() || !cc[1].isLetter()) return ""
        val a = 0x1F1E6 + (cc[0].uppercaseChar() - 'A')
        val b = 0x1F1E6 + (cc[1].uppercaseChar() - 'A')
        return String(Character.toChars(a)) + String(Character.toChars(b))
    }

    /** Canonical place name if [q] exactly names a city/state/country in the library, else null. */
    private fun placeLabelFor(q: String): String? {
        val query = q.trim()
        if (query.isEmpty()) return null
        for (r in records) {
            if (r.city.equals(query, true))    return r.city
            if (r.state.equals(query, true))   return r.state
            if (r.country.equals(query, true)) return r.country
        }
        return null
    }

    private fun runSearch(place: String) { binding.searchView.setQuery(place, true); hideKeyboard() }

    private fun hideKeyboard() {
        binding.searchView.clearFocus()
        (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }


    companion object {
        const val EXTRA_PLACE_BROWSE_MODE = "place_browse_mode"   // "A" | "B"

        // Chip colours, assigned per place name (stable per place). White text sits on all of these.
        private val PALETTE = intArrayOf(
            0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFF4511E.toInt(), 0xFF8E24AA.toInt(),
            0xFF00897B.toInt(), 0xFFD81B60.toInt(), 0xFF3949AB.toInt(), 0xFFF9A825.toInt(),
            0xFF6D4C41.toInt(), 0xFF00ACC1.toInt(),
        )
    }

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
        // Page through the current result set, in result order.
        val ids = (viewModel.results.value ?: emptyList()).map { it.mediaItem.id }.toLongArray()
        startActivity(Intent(this, MediaViewerActivity::class.java)
            .putExtra(MediaViewerActivity.EXTRA_START_ID, item.id)
            .putExtra(MediaViewerActivity.EXTRA_ID_LIST, ids))
    }
}
