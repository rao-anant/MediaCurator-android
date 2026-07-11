package com.anant.mediacurator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standalone search for the dedicated [SearchActivity]. Reuses [SearchEngine] over the
 * shared [MediaCache] and the PDF BM25 index — file names + PDF content only.
 *
 * results == null  → no query yet (show the prompt)
 * results == []    → searched, nothing matched
 * results.isNotEmpty() → matches
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val repo          = MediaRepository(app)
    private val prefs         = PreferencesManager(app)
    private val pdfIndexStore = PdfIndexStore(app)

    private var media: List<MediaItem> = emptyList()
    private var bm25: PdfBm25Index? = null
    private var placeIndex: Map<Long, List<String>>? = null
    // Exact place name (normalized) → its photos. Lets a chip tap skip the full fuzzy scan.
    private var placeExact: Map<String, List<MediaItem>> = emptyMap()
    private var ready = false

    // Ids deleted this session. A freshly-trashed item can still come back from a MediaStore re-query
    // for a moment, so we filter these out of every result/count until the VM is recreated — same
    // idea as the gallery's session-delete guard, so deletes actually leave the screen.
    private val sessionDeletedIds = HashSet<Long>()

    private var searchJob: Job? = null

    private val _results = MutableLiveData<List<GalleryItem.Media>?>(null)
    val results: LiveData<List<GalleryItem.Media>?> = _results

    // Per-photo place records for the browse experiments; the Activity aggregates via PlaceBrowse.
    private val _placeRecords = MutableLiveData<List<PlaceRecord>>(emptyList())
    val placeRecords: LiveData<List<PlaceRecord>> = _placeRecords

    fun loadPlaces() {
        if (!prefs.isPlaceSearchEnabled()) { _placeRecords.postValue(emptyList()); return }
        viewModelScope.launch(Dispatchers.IO) {
            val liveIds = MediaCache.get(repo).mapTo(HashSet()) { it.id }   // only count photos that still exist
            liveIds.removeAll(sessionDeletedIds)                            // …and not the just-deleted ones
            val store = PlaceStore.getInstance(getApplication<android.app.Application>()).also { it.ensureLoaded() }
            _placeRecords.postValue(store.records(liveIds))
        }
    }

    private var lastQuery = ""

    fun search(query: String) {
        searchJob?.cancel()
        lastQuery = query
        if (query.isBlank()) { _results.value = null; return }   // null → prompt
        searchJob = viewModelScope.launch {
            ensureData()
            val res = withContext(Dispatchers.Default) {
                // Fast path: an exact place name (chip tap / breadcrumb) is a direct lookup —
                // no need to fuzzy-scan the whole library to re-derive "photos in this place".
                val exact = placeExact[SearchEngine.norm(query.trim())]
                val base = if (exact != null) {
                    exact.distinctBy { it.id }
                        .sortedByDescending { it.dateTaken }
                        .mapIndexed { i, item -> GalleryItem.Media(item, "", i, null, 0) }
                } else {
                    SearchEngine.search(query, media, bm25, placeIndex).mapIndexed { i, r ->
                        GalleryItem.Media(r.item, "", i, r.matchReason.ifBlank { null }, 0)
                    }
                }
                // Never resurrect a just-deleted item (MediaStore may still return it briefly).
                if (sessionDeletedIds.isEmpty()) base
                else base.filter { it.mediaItem.id !in sessionDeletedIds }
            }
            _results.postValue(res)
        }
    }

    /** Pre-load media + indexes in the background so the first place tap is instant. */
    fun warmUp() { viewModelScope.launch { ensureData() } }

    /**
     * Soft-delete the selected results (same recycle-bin path as the gallery): move to Trash,
     * record the batch for Home's quick-undo, update the deletion stats. Drops them from the
     * current results immediately, then invalidates the shared cache and refreshes so the
     * counts/browse chips reflect the removal.
     */
    fun deleteMedia(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val ids = items.mapTo(HashSet()) { it.id }
        sessionDeletedIds.addAll(ids)                                                            // keep them gone
        _results.value?.let { cur -> _results.value = cur.filter { it.mediaItem.id !in ids } }  // instant feedback
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = TrashManager.get(getApplication()).trash(items)
                prefs.setLastDeletedBatch(result.entries)
                DeletionStatsStore.getInstance(getApplication()).onTrashed(result.count, result.bytes)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            MediaCache.invalidate()   // trashed items are excluded from fresh MediaStore queries
            ready = false             // force ensureData() to re-fetch media on the next search
            withContext(Dispatchers.Main) {
                loadPlaces()                                          // refresh browse counts
                if (lastQuery.isNotBlank()) search(lastQuery)        // refresh header count / results
            }
        }
    }

    /** Rename a single result on disk (dialog-free with All-files access), then refresh. */
    fun renameMedia(item: MediaItem, newDisplayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try { repo.renameMedia(item, newDisplayName) } catch (e: Exception) { false }
            if (ok) withContext(Dispatchers.Main) { refreshAfterExternalChange() }
        }
    }

    /**
     * Re-sync after a change made outside the search query (rename, album move): drop the shared
     * cache + the lazy-load guard so the next query re-reads MediaStore, and refresh browse counts
     * and the current results.
     */
    fun refreshAfterExternalChange() {
        MediaCache.invalidate()
        ready = false
        loadPlaces()
        if (lastQuery.isNotBlank()) search(lastQuery)
    }

    /** Load media + PDF index once, lazily, off the main thread. */
    private suspend fun ensureData() {
        if (ready) return
        withContext(Dispatchers.IO) {
            media  = MediaCache.get(repo)
            bm25   = if (prefs.isPdfContentSearchEnabled())
                         try { PdfBm25Index(pdfIndexStore.loadAll()) } catch (e: Exception) { null }
                     else null
            placeIndex = if (prefs.isPlaceSearchEnabled())
                             PlaceStore.getInstance(getApplication()).also { it.ensureLoaded() }.toSearchIndex()
                         else null
            placeExact = buildPlaceExact(placeIndex, media)
        }
        ready = true
    }

    /** Invert the per-photo place index into normalized-name → photos, for the exact-tap fast path. */
    private fun buildPlaceExact(
        index: Map<Long, List<String>>?,
        media: List<MediaItem>
    ): Map<String, List<MediaItem>> {
        if (index.isNullOrEmpty()) return emptyMap()
        val byId = media.associateBy { it.id }
        val out = HashMap<String, MutableList<MediaItem>>()
        for ((id, names) in index) {
            val item = byId[id] ?: continue
            for (n in names) {
                val key = SearchEngine.norm(n)
                if (key.length >= 2) out.getOrPut(key) { mutableListOf() }.add(item)
            }
        }
        return out
    }
}
