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
    private var ready = false

    private var searchJob: Job? = null

    private val _results = MutableLiveData<List<GalleryItem.Media>?>(null)
    val results: LiveData<List<GalleryItem.Media>?> = _results

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) { _results.value = null; return }   // null → prompt
        searchJob = viewModelScope.launch {
            ensureData()
            val res = withContext(Dispatchers.Default) {
                SearchEngine.search(query, media, bm25)
            }
            _results.postValue(res.mapIndexed { i, r ->
                GalleryItem.Media(r.item, "", i, r.matchReason.ifBlank { null }, 0)
            })
        }
    }

    /** Load media + PDF index once, lazily, off the main thread. */
    private suspend fun ensureData() {
        if (ready) return
        withContext(Dispatchers.IO) {
            media  = MediaCache.get(repo)
            bm25   = if (prefs.isPdfContentSearchEnabled())
                         try { PdfBm25Index(pdfIndexStore.loadAll()) } catch (e: Exception) { null }
                     else null
        }
        ready = true
    }
}
