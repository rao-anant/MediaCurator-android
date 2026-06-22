package com.anant.mediacurator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the [TrashActivity] — lists everything currently in the recycle bin and performs
 * Restore / Delete-forever / Empty, keeping the stats and the gallery quick-undo in sync.
 */
class TrashViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PreferencesManager(app)
    private val stats = DeletionStatsStore.getInstance(app)

    private val _items = MutableLiveData<List<MediaItem>>(emptyList())
    val items: LiveData<List<MediaItem>> = _items

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    fun load() {
        _loading.value = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { TrashManager.get(getApplication()).listTrashed() }
            _items.value = list
            _loading.value = false
        }
    }

    /** Restore [selected] back to the library. */
    fun restore(selected: List<MediaItem>) {
        if (selected.isEmpty()) return
        val uris = selected.map { it.uri }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { TrashManager.get(getApplication()).restore(uris) }
            stats.onRestored(r.count, r.bytes)
            dropFromLastBatch(uris)
            load()
        }
    }

    /** Permanently delete [selected] from trash. */
    fun deleteForever(selected: List<MediaItem>) {
        if (selected.isEmpty()) return
        val uris = selected.map { it.uri }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { TrashManager.get(getApplication()).purge(uris) }
            stats.onPurged(r.count, r.bytes)
            dropFromLastBatch(uris)
            load()
        }
    }

    /** Empty the entire trash. */
    fun empty() = deleteForever(_items.value ?: emptyList())

    /** Keep the gallery quick-undo honest: remove any of [uris] from the saved last batch. */
    private fun dropFromLastBatch(uris: List<String>) {
        val set = uris.toSet()
        val remaining = prefs.getLastDeletedBatch().filter { it.first !in set }
        prefs.setLastDeletedBatch(remaining)
    }
}
