package com.anant.mediacurator

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DuplicatesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo          = MediaRepository(app)
    private val photoHashStore = PhotoHashStore.getInstance(app)

    private val _groups = MutableLiveData<List<DuplicateGroup>>(emptyList())
    val groups: LiveData<List<DuplicateGroup>> = _groups

    private val _isLoading = MutableLiveData<Boolean>(true)
    val isLoading: LiveData<Boolean> = _isLoading

    // How many items are in the hash cache — shown in the toolbar subtitle so the user
    // knows if results are partial (hashing still running in GalleryViewModel).
    private val _indexedCount = MutableLiveData<Int>(0)
    val indexedCount: LiveData<Int> = _indexedCount

    // Non-null once a deletion run completes: number of files deleted successfully.
    private val _deletionResult = MutableLiveData<Int?>(null)
    val deletionResult: LiveData<Int?> = _deletionResult

    // Non-null when we need the user to approve deletion of some files via system dialog.
    private val _deletePermissionRequest = MutableLiveData<IntentSender?>(null)
    val deletePermissionRequest: LiveData<IntentSender?> = _deletePermissionRequest

    private var pendingDeleteItems: List<MediaItem> = emptyList()
    private var directlyDeletedCount = 0
    private var directlyDeletedBytes = 0L

    // ── Loading ───────────────────────────────────────────────────────────────

    /**
     * Load duplicate groups from [PhotoHashStore], then hydrate with MediaItem data
     * from MediaStore.  Groups whose files have been deleted since the last hashing
     * run are silently dropped.
     */
    fun loadDuplicates() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.postValue(true)
            // Singleton store: hashing writes into the same in-memory cache we read,
            // so the latest hashes are always visible — no disk reload needed.
            // (reload() would actually LOSE unflushed in-flight hashes.)
            photoHashStore.ensureLoaded()

            _indexedCount.postValue(photoHashStore.countEntries())

            val dupeMap = photoHashStore.findDuplicateGroups()  // md5 → list of media IDs
            val built   = mutableListOf<DuplicateGroup>()

            for ((md5, ids) in dupeMap) {
                // Fetch from Images collection first; any IDs not found there come from Videos
                val images  = repo.fetchImagesByIds(ids)
                val foundIds = images.map { it.id }.toSet()
                val videoIds = ids.filter { it !in foundIds }
                val videos  = if (videoIds.isNotEmpty()) repo.fetchVideosByIds(videoIds) else emptyList()
                val items   = images + videos

                // Fewer than 2 items means some copies were already deleted — clean up cache
                if (items.size < 2) {
                    ids.forEach { id -> if (items.none { it.id == id }) photoHashStore.deleteEntry(id) }
                    photoHashStore.flush()
                    continue
                }
                built.add(DuplicateGroup(md5, items, keepIndex = pickCanonical(items)))
            }

            // Sort by most space recoverable first
            built.sortByDescending { it.reclaimableBytes }

            _groups.postValue(built)
            _isLoading.postValue(false)
        }
    }

    /**
     * Pick the "best" copy to keep automatically:
     *  1. Prefer copies in DCIM/Camera (original shot, not a re-share)
     *  2. Then prefer the oldest by dateTaken (the original, not a copy)
     *  3. Fallback: index 0
     */
    private fun pickCanonical(items: List<MediaItem>): Int {
        val dcimIdx = items.indexOfFirst {
            it.relativePath.contains("DCIM", ignoreCase = true) ||
            it.relativePath.contains("Camera", ignoreCase = true)
        }
        if (dcimIdx >= 0) return dcimIdx

        val minDate = items.minOf { it.dateTaken }
        val oldestIdx = items.indexOfFirst { it.dateTaken == minDate }
        return oldestIdx.takeIf { it >= 0 } ?: 0
    }

    // ── Keep / delete toggling ────────────────────────────────────────────────

    /** Change which copy in [groupIndex] is kept; all others become "to delete". */
    fun setKeepIndex(groupIndex: Int, itemIndex: Int) {
        val current = _groups.value?.toMutableList() ?: return
        val group = current.getOrNull(groupIndex) ?: return
        group.keepIndex = itemIndex
        _groups.value = current   // trigger observer (same list reference but state changed)
    }

    // ── Deletion ──────────────────────────────────────────────────────────────

    /**
     * Delete all items NOT at [keepIndex] in every group.
     *
     * Strategy: try direct ContentResolver.delete() first (works with MANAGE_EXTERNAL_STORAGE).
     * Any photos that fail direct deletion are batched into a system createDeleteRequest dialog.
     */
    fun deleteMarked() {
        val toDelete = _groups.value?.flatMap { group ->
            group.items.filterIndexed { idx, _ -> idx != group.keepIndex }
        } ?: return

        if (toDelete.isEmpty()) { _deletionResult.value = 0; return }

        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            var deleted = 0
            var deletedBytes = 0L
            val needPermission = mutableListOf<MediaItem>()

            for (item in toDelete) {
                try {
                    val rows = resolver.delete(Uri.parse(item.uri), null, null)
                    if (rows > 0) {
                        photoHashStore.deleteEntry(item.id)
                        deleted++
                        deletedBytes += item.size
                    } else {
                        needPermission.add(item)
                    }
                } catch (e: Exception) {
                    Log.w("DuplicatesViewModel", "Direct delete failed for ${item.displayName}", e)
                    needPermission.add(item)
                }
            }
            photoHashStore.flush()

            if (needPermission.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Some photos need a system permission dialog (Android 11+ without MANAGE_MEDIA,
                // or on devices where direct delete is blocked).
                val intentSender = repo.createDeleteRequest(needPermission)
                if (intentSender != null) {
                    pendingDeleteItems = needPermission
                    directlyDeletedCount = deleted
                    directlyDeletedBytes = deletedBytes
                    _deletePermissionRequest.postValue(intentSender)
                    return@launch
                }
            }

            withContext(Dispatchers.Main) { finishDeletion(deleted, deletedBytes) }
        }
    }

    /** Called from DuplicatesActivity after the system delete-permission dialog resolves. */
    fun onDeletePermissionResult(granted: Boolean) {
        val items = pendingDeleteItems
        val alreadyDeleted = directlyDeletedCount
        val alreadyBytes   = directlyDeletedBytes
        pendingDeleteItems = emptyList()
        directlyDeletedCount = 0
        directlyDeletedBytes = 0L
        _deletePermissionRequest.value = null

        if (granted && items.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                items.forEach { photoHashStore.deleteEntry(it.id) }
                photoHashStore.flush()
                val bytes = alreadyBytes + items.sumOf { it.size }
                withContext(Dispatchers.Main) { finishDeletion(alreadyDeleted + items.size, bytes) }
            }
        } else {
            finishDeletion(alreadyDeleted, alreadyBytes)
        }
    }

    private fun finishDeletion(totalDeleted: Int, totalBytes: Long) {
        _deletionResult.value = totalDeleted
        // Lifetime cumulative-deletion counter (shown in the gallery Stats dialog).
        DeletionStatsStore.getInstance(getApplication()).record(totalDeleted, totalBytes)
        // Reload to reflect the new state (deleted items gone, orphaned groups removed)
        loadDuplicates()
    }

    fun clearDeletionResult() { _deletionResult.value = null }
}
