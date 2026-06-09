package com.anant.mediacurator

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.IntentSender
import android.database.ContentObserver
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)
    val prefs = PreferencesManager(app)

    private val _galleryItems = MutableLiveData<List<GalleryItem>>()
    val galleryItems: LiveData<List<GalleryItem>> = _galleryItems

    // Flat list of every visible MediaItem (type-filtered, deletion-filtered) with no
    // tree-view expansion gate.  MediaViewerActivity uses this so it can swipe through
    // all items regardless of which years/months are currently expanded in the grid.
    private val _flatMediaItems = MutableLiveData<List<MediaItem>>()
    val flatMediaItems: LiveData<List<MediaItem>> = _flatMediaItems

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _sortMode = MutableLiveData<SortMode>()
    val sortMode: LiveData<SortMode> = _sortMode

    private val _storageSavedEvent = MutableLiveData<Long>()
    val storageSavedEvent: LiveData<Long> = _storageSavedEvent

    private val _includePhoto = MutableLiveData<Boolean>()
    val includePhoto: LiveData<Boolean> = _includePhoto

    private val _includeVideo = MutableLiveData<Boolean>()
    val includeVideo: LiveData<Boolean> = _includeVideo

    private val _includePdf = MutableLiveData<Boolean>()
    val includePdf: LiveData<Boolean> = _includePdf

    private val _includeAudio = MutableLiveData<Boolean>()
    val includeAudio: LiveData<Boolean> = _includeAudio

    private val _mediaStats = MutableLiveData<MediaStats?>()
    val mediaStats: LiveData<MediaStats?> = _mediaStats

    private val _scrollToTopEvent = MutableLiveData<Unit>()
    val scrollToTopEvent: LiveData<Unit> = _scrollToTopEvent

    private val _scrollToMonthKey = MutableLiveData<String?>()
    val scrollToMonthKey: LiveData<String?> = _scrollToMonthKey

    private val _deletePermissionRequest = MutableLiveData<IntentSender?>()
    val deletePermissionRequest: LiveData<IntentSender?> = _deletePermissionRequest

    private val _deletionCompletedEvent = MutableLiveData<Boolean>()
    val deletionCompletedEvent: LiveData<Boolean> = _deletionCompletedEvent

    // Rename flow — mirrors the delete permission pattern
    private val _renamePermissionRequest = MutableLiveData<IntentSender?>()
    val renamePermissionRequest: LiveData<IntentSender?> = _renamePermissionRequest

    // Non-null non-empty = success (new name); empty string = failure; null = idle
    private val _renameResult = MutableLiveData<String?>()
    val renameResult: LiveData<String?> = _renameResult

    private var pendingRenameItem: MediaItem? = null
    private var pendingRenameNewName: String? = null

    private val _doneMonthsAvailable = MutableLiveData<List<MonthGroup>>()
    val doneMonthsAvailable: LiveData<List<MonthGroup>> = _doneMonthsAvailable

    private val _totalPhotos = MutableLiveData<Int>(0)
    val totalPhotos: LiveData<Int> = _totalPhotos

    private val _totalVideos = MutableLiveData<Int>(0)
    val totalVideos: LiveData<Int> = _totalVideos

    private val _totalPdfs = MutableLiveData<Int>(0)
    val totalPdfs: LiveData<Int> = _totalPdfs

    private val _totalAudios = MutableLiveData<Int>(0)
    val totalAudios: LiveData<Int> = _totalAudios

    // Non-null while a "backup found — import?" prompt is waiting for user response.
    // Set to null after the user confirms or skips to prevent re-showing on rotation.
    private val _autoRestorePrompt = MutableLiveData<Set<String>?>()
    val autoRestorePrompt: LiveData<Set<String>?> = _autoRestorePrompt

    // ── Image labels ──────────────────────────────────────────────────────────
    // Map of MediaItem.id → list of ML Kit label strings (e.g. ["Beach","Sky"]).
    // Populated lazily in the background after each media load; surviving labels from
    // the LabelCache are merged in immediately on first load so the UI shows them
    // without waiting for the labeler to finish.
    // Map of MediaItem.id → (label → confidence), e.g. {42L: {"Dog": 0.89, "Beach": 0.76}}
    private val _photoLabels = MutableLiveData<Map<Long, Map<String, Float>>>(emptyMap())
    val photoLabels: LiveData<Map<Long, Map<String, Float>>> = _photoLabels

    val labelCache = LabelCache(app)
    private var labelingJob: Job? = null

    val pdfIndexStore = PdfIndexStore(app)
    private var pdfIndexingJob: Job? = null

    // In-memory BM25 index — built lazily on first search, invalidated when index changes.
    @Volatile private var bm25IndexCache: PdfBm25Index? = null

    // Progress of the background PDF indexing run.
    // Null = no run needed (all already indexed or no PDFs).
    private val _pdfIndexProgress = MutableLiveData<PdfIndexProgress?>(null)
    val pdfIndexProgress: LiveData<PdfIndexProgress?> = _pdfIndexProgress

    // Fired (once) when the indexing job is killed by an OutOfMemoryError.
    // MainActivity observes this to show a Snackbar and offer the settings toggle.
    private val _pdfIndexOomEvent = MutableLiveData<Unit>()
    val pdfIndexOomEvent: LiveData<Unit> = _pdfIndexOomEvent

    // ── Search ────────────────────────────────────────────────────────────────
    // Null = not in search mode (gallery shows normally).
    // Non-null = active search; list may be empty if nothing matched.
    private val _searchResults = MutableLiveData<List<GalleryItem>?>(null)
    val searchResults: LiveData<List<GalleryItem>?> = _searchResults

    private var searchJob: Job? = null

    companion object {
        // Shared across Activity instances (MainActivity and ViewerActivity)
        // to ensure consistent UI filtering during the lag between file deletion
        // and the system MediaStore index updating.
        // Fingerprints (name + size) are more stable than IDs across different query collection views.
        private val deletedFingerprintsInFlight = Collections.synchronizedSet(mutableSetOf<String>())

        fun getFingerprint(item: MediaItem): String = "${item.displayName}_${item.size}"

        // Fixed filename so we can always overwrite / locate the single live backup.
        const val AUTO_BACKUP_FILENAME     = "mediacurator_hidden.json"
        const val PDF_INDEX_BACKUP_FILENAME = "mediacurator_pdf_index.json.gz"
    }

    private var pendingItemsToDelete: List<MediaItem>? = null
    private var pendingBytesToFree: Long = 0L
    private var loadJob: Job? = null

    // True while a rename is in progress (and for 5 s after, while MediaStore re-indexes).
    // Suppresses the observer's debounced forceRefresh — the same guard pattern used for
    // deletedFingerprintsInFlight — so the renamed file never briefly vanishes from the list.
    @Volatile private var renameInFlight = false

    private var mediaObserver: ContentObserver? = null

    private var cachedRawMedia: List<MediaItem>? = null
    // Saved copy of the last gallery flat list so clearSearch() can restore it
    private var galleryFlatItems: List<MediaItem> = emptyList()
    private var structuralVersion = 0

    // Items deleted during this session — NEVER cleared while the app is running.
    // This is the definitive guard: once a user deletes something it must not reappear
    // regardless of how slowly (or incompletely) MediaStore updates its index.
    // Unlike deletedFingerprintsInFlight (static, shared, time-limited), this set is
    // per-instance and lives for the entire ViewModel lifetime.
    private val sessionDeletedFingerprints = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile private var pendingScrollToTop = false
    @Volatile private var pendingScrollToMonthKey: String? = null

    // Which years / months / sub-groups are currently expanded in the tree view.
    // Thread-safe: reads happen on IO thread (via snapshot), writes on main thread.
    private val expandedYears     = Collections.synchronizedSet(mutableSetOf<Int>())
    private val expandedMonths    = Collections.synchronizedSet(mutableSetOf<String>())
    private val expandedSubGroups = Collections.synchronizedSet(mutableSetOf<String>())

    init {
        _sortMode.value = prefs.getSortMode()
        _includePhoto.value = prefs.isIncludePhoto()
        _includeVideo.value = prefs.isIncludeVideo()
        _includePdf.value = prefs.isIncludePdf()
        _includeAudio.value = prefs.isIncludeAudio()
        expandedYears.addAll(prefs.getExpandedYears())
        expandedMonths.addAll(prefs.getExpandedMonths())
        expandedSubGroups.addAll(prefs.getExpandedSubGroups())
        // Silently restore hidden-month state from the auto-backup if prefs are empty
        // (covers fresh install, app-data clear, reinstall after uninstall).
        checkAndAutoRestore()
        registerMediaObserver()
    }

    fun setIncludePhoto(include: Boolean) {
        if (_includePhoto.value != include) {
            _includePhoto.value = include
            prefs.saveIncludePhoto(include)
            pendingScrollToTop = true
            loadMedia(forceRefresh = false)
        }
    }

    fun setIncludeVideo(include: Boolean) {
        if (_includeVideo.value != include) {
            _includeVideo.value = include
            prefs.saveIncludeVideo(include)
            pendingScrollToTop = true
            loadMedia(forceRefresh = false)
        }
    }

    fun setIncludePdf(include: Boolean) {
        if (_includePdf.value != include) {
            _includePdf.value = include
            prefs.saveIncludePdf(include)
            pendingScrollToTop = true
            loadMedia(forceRefresh = false)
        }
    }

    fun setIncludeAudio(include: Boolean) {
        if (_includeAudio.value != include) {
            _includeAudio.value = include
            prefs.saveIncludeAudio(include)
            pendingScrollToTop = true
            loadMedia(forceRefresh = false)
        }
    }

    fun clearScrollToMonth() {
        _scrollToMonthKey.value = null
    }

    fun toggleYearExpansion(year: Int) {
        if (expandedYears.contains(year)) expandedYears.remove(year) else expandedYears.add(year)
        prefs.saveExpandedYears(expandedYears.toSet())
        structuralVersion++
        loadMedia(forceRefresh = false)
    }

    fun toggleMonthExpansion(monthKey: String) {
        if (expandedMonths.contains(monthKey)) expandedMonths.remove(monthKey) else expandedMonths.add(monthKey)
        prefs.saveExpandedMonths(expandedMonths.toSet())
        structuralVersion++
        loadMedia(forceRefresh = false)
    }

    fun toggleSubGroupExpansion(subKey: String) {
        if (expandedSubGroups.contains(subKey)) expandedSubGroups.remove(subKey) else expandedSubGroups.add(subKey)
        prefs.saveExpandedSubGroups(expandedSubGroups.toSet())
        structuralVersion++
        loadMedia(forceRefresh = false)
    }

    fun setSortMode(mode: SortMode) {
        if (_sortMode.value != mode) {
            _sortMode.value = mode
            prefs.saveSortMode(mode)
            structuralVersion++
            loadMedia(forceRefresh = false)
        }
    }

    fun loadMedia(forceRefresh: Boolean = false) {
        val sortMode = _sortMode.value ?: SortMode.DATE_OLDEST
        val photoOn = _includePhoto.value ?: true
        val videoOn = _includeVideo.value ?: true
        val pdfOn   = _includePdf.value   ?: true
        val audioOn = _includeAudio.value ?: true
        val doneMonthKeys = prefs.getDoneMonths()
        val currentVersion = structuralVersion

        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoading.postValue(true)

            // 1. Get raw media from repository or local cache.
            val rawMedia = if (forceRefresh || cachedRawMedia == null) {
                val fetched = repo.fetchAllMedia()

                // Data-driven cleanup for the cross-Activity shared set: clear a fingerprint
                // only when MediaStore confirms the item is actually gone.
                if (deletedFingerprintsInFlight.isNotEmpty()) {
                    val presentFps    = fetched.mapTo(HashSet()) { getFingerprint(it) }
                    val snapshot      = deletedFingerprintsInFlight.toSet()
                    val confirmedGone = snapshot.filter { !presentFps.contains(it) }
                    if (confirmedGone.isNotEmpty()) deletedFingerprintsInFlight.removeAll(confirmedGone)
                }

                // Cache without ANY deleted items — both the shared set and the
                // session set (which is NEVER cleared, so items can't sneak back
                // no matter how slowly MediaStore updates).
                cachedRawMedia = fetched.filter { item ->
                    val fp = getFingerprint(item)
                    !deletedFingerprintsInFlight.contains(fp) && !sessionDeletedFingerprints.contains(fp)
                }
                fetched
            } else {
                cachedRawMedia!!
            }

            // 2. Filter display list — check both sets
            val filteredMedia = rawMedia.filter { item ->
                val fp = getFingerprint(item)
                !deletedFingerprintsInFlight.contains(fp) && !sessionDeletedFingerprints.contains(fp)
            }

            // 3. Update stats from full filtered list (not display-filtered)
            _totalPhotos.postValue(filteredMedia.count { it.type == MediaType.IMAGE })
            _totalVideos.postValue(filteredMedia.count { it.type == MediaType.VIDEO })
            _totalPdfs.postValue(filteredMedia.count { it.type == MediaType.PDF })
            _totalAudios.postValue(filteredMedia.count { it.type == MediaType.AUDIO })

            // 4. Apply type filters
            val displayMedia = filteredMedia.filter { item ->
                when (item.type) {
                    MediaType.IMAGE -> photoOn
                    MediaType.VIDEO -> videoOn
                    MediaType.PDF   -> pdfOn
                    MediaType.AUDIO -> audioOn
                }
            }

            // 5. Process into groups (always needed for stats and done-months panel)
            val (visibleGroups, _) = repo.processAndGroupMedia(displayMedia, sortMode, doneMonthKeys)
            val (allVisibleFull, doneGroups) = repo.processAndGroupMedia(filteredMedia, sortMode, doneMonthKeys)

            // 6. Build MediaStats (counts + sizes per type, integrity check)
            fun countOf(groups: List<MonthGroup>, t: MediaType) = groups.sumOf { g -> g.items.count { it.type == t } }
            fun bytesOf(groups: List<MonthGroup>, t: MediaType) = groups.sumOf { g -> g.items.filter { it.type == t }.sumOf { it.size } }

            val vPhotos = countOf(allVisibleFull, MediaType.IMAGE); val hPhotos = countOf(doneGroups, MediaType.IMAGE)
            val vVideos = countOf(allVisibleFull, MediaType.VIDEO); val hVideos = countOf(doneGroups, MediaType.VIDEO)
            val vPdfs   = countOf(allVisibleFull, MediaType.PDF);   val hPdfs   = countOf(doneGroups, MediaType.PDF)
            val vAudios = countOf(allVisibleFull, MediaType.AUDIO); val hAudios = countOf(doneGroups, MediaType.AUDIO)

            val checkVisible = allVisibleFull.sumOf { it.items.size }
            val checkHidden  = doneGroups.sumOf { it.items.size }
            val checkTotal   = filteredMedia.size
            val integrityOk  = checkVisible + checkHidden == checkTotal
            val integrityDetail = if (integrityOk) "✓ All counts match"
                else "⚠ visible($checkVisible) + hidden($checkHidden) = ${checkVisible + checkHidden} ≠ total($checkTotal)"

            _mediaStats.postValue(MediaStats(
                vPhotos, hPhotos, filteredMedia.count { it.type == MediaType.IMAGE },
                vVideos, hVideos, filteredMedia.count { it.type == MediaType.VIDEO },
                vPdfs,   hPdfs,   filteredMedia.count { it.type == MediaType.PDF   },
                vAudios, hAudios, filteredMedia.count { it.type == MediaType.AUDIO },
                bytesOf(allVisibleFull, MediaType.IMAGE), bytesOf(doneGroups, MediaType.IMAGE),
                bytesOf(allVisibleFull, MediaType.VIDEO), bytesOf(doneGroups, MediaType.VIDEO),
                bytesOf(allVisibleFull, MediaType.PDF),   bytesOf(doneGroups, MediaType.PDF),
                bytesOf(allVisibleFull, MediaType.AUDIO), bytesOf(doneGroups, MediaType.AUDIO),
                integrityOk, integrityDetail
            ))

            // 7. Build gallery item list — flat for SIZE_ABSOLUTE, tree for everything else
            val galleryItems: List<GalleryItem>
            val flatForViewer: List<MediaItem>

            if (sortMode == SortMode.SIZE_ABSOLUTE) {
                // Pure size-descending flat list: no year/month headers at all.
                // A date badge (e.g. "Jan 2024") is embedded in each Media item so
                // the user knows the month without needing tree structure.
                val dateFmt  = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
                val calendar = java.util.Calendar.getInstance()
                val flatSorted = displayMedia.sortedWith(
                    compareByDescending<MediaItem> { it.size }.thenByDescending { it.dateTaken }
                )
                galleryItems = flatSorted.mapIndexed { idx, mediaItem ->
                    calendar.timeInMillis = mediaItem.dateTaken
                    GalleryItem.Media(mediaItem, "", idx, dateFmt.format(calendar.time), currentVersion)
                }
                flatForViewer = flatSorted
            } else {
                // Normal tree view grouped by year → month.
                // Header counts/sizes reflect the chip-filtered set (visibleGroups / group.items)
                // so they stay consistent with the thumbnails shown.  The chip labels in the
                // toolbar already carry the total-per-type counts, so users can see the full
                // library breakdown without the headers needing to show filtered-out types.
                val yearToMonths = LinkedHashMap<Int, MutableList<MonthGroup>>()
                for (group in visibleGroups) {
                    yearToMonths.getOrPut(group.year) { mutableListOf() }.add(group)
                }

                val expandedYearsSnapshot     = expandedYears.toSet()
                val expandedMonthsSnapshot    = expandedMonths.toSet()
                val expandedSubGroupsSnapshot = expandedSubGroups.toSet()

                val treeItems = ArrayList<GalleryItem>()
                for ((year, months) in yearToMonths) {
                    val yearItemCount = months.sumOf { it.items.size }
                    val yearBytes     = months.sumOf { g -> g.items.sumOf { it.size } }
                    val yearPhotos    = months.sumOf { g -> g.items.count { it.type == MediaType.IMAGE } }
                    val yearVideos    = months.sumOf { g -> g.items.count { it.type == MediaType.VIDEO } }
                    val yearPdfs      = months.sumOf { g -> g.items.count { it.type == MediaType.PDF   } }
                    val yearAudios    = months.sumOf { g -> g.items.count { it.type == MediaType.AUDIO } }
                    val isYearExpanded = expandedYearsSnapshot.contains(year)
                    val visualMonths = months.filter { g -> g.items.any { it.type == MediaType.IMAGE || it.type == MediaType.VIDEO } }
                    val pickIndices = when (visualMonths.size) {
                        0    -> emptyList()
                        1    -> listOf(0)
                        else -> listOf(0, visualMonths.lastIndex)
                    }
                    val previewUris = pickIndices
                        .mapNotNull { i -> visualMonths[i].items.firstOrNull { it.type == MediaType.IMAGE || it.type == MediaType.VIDEO }?.uri }
                    val doneInYear  = doneMonthKeys.count { it.startsWith("$year-") }
                    val totalMonths = months.size + doneInYear   // visible + hidden months
                    val curatedPct  = if (totalMonths > 0) (doneInYear * 100) / totalMonths else 0
                    treeItems.add(GalleryItem.YearHeader(year, yearItemCount, yearBytes, isYearExpanded, yearPhotos, yearVideos, yearPdfs, currentVersion, previewUris, curatedPct, yearAudios))
                    if (isYearExpanded) {
                        for (group in months) {
                            val monthItemCount  = group.items.size
                            val monthBytes      = group.items.sumOf { it.size }
                            val monthPhotos     = group.items.count { it.type == MediaType.IMAGE }
                            val monthVideos     = group.items.count { it.type == MediaType.VIDEO }
                            val monthPdfs       = group.items.count { it.type == MediaType.PDF   }
                            val monthAudios     = group.items.count { it.type == MediaType.AUDIO }
                            val isMonthExpanded = expandedMonthsSnapshot.contains(group.key)
                            treeItems.add(GalleryItem.Header(group.key, group.label, monthItemCount, monthBytes, isMonthExpanded, monthPhotos, monthVideos, monthPdfs, currentVersion, monthAudios))
                            if (isMonthExpanded) {
                                val waItems  = group.items.filter { it.isWhatsApp }
                                val camItems = group.items.filter { !it.isWhatsApp }

                                if (waItems.isEmpty()) {
                                    // No WhatsApp items — flat layout (current behavior)
                                    group.items.forEachIndexed { index, mediaItem ->
                                        treeItems.add(GalleryItem.Media(mediaItem, group.key, index, null, currentVersion))
                                    }
                                } else {
                                    val camKey = "${group.key}:cam"
                                    val waKey  = "${group.key}:wa"
                                    val isCamExpanded = expandedSubGroupsSnapshot.contains(camKey)
                                    val isWaExpanded  = expandedSubGroupsSnapshot.contains(waKey)

                                    if (camItems.isNotEmpty()) {
                                        treeItems.add(GalleryItem.SubHeader(
                                            subKey = camKey, monthKey = group.key,
                                            label = "Camera & Others",
                                            count = camItems.size,
                                            totalBytes = camItems.sumOf { it.size },
                                            isExpanded = isCamExpanded,
                                            photoCount = camItems.count { it.type == MediaType.IMAGE },
                                            videoCount = camItems.count { it.type == MediaType.VIDEO },
                                            pdfCount   = camItems.count { it.type == MediaType.PDF },
                                            structuralVersion = currentVersion,
                                            audioCount = camItems.count { it.type == MediaType.AUDIO }
                                        ))
                                        if (isCamExpanded) {
                                            camItems.forEachIndexed { index, mediaItem ->
                                                treeItems.add(GalleryItem.Media(mediaItem, group.key, index, null, currentVersion))
                                            }
                                        }
                                    }

                                    treeItems.add(GalleryItem.SubHeader(
                                        subKey = waKey, monthKey = group.key,
                                        label = "WhatsApp",
                                        count = waItems.size,
                                        totalBytes = waItems.sumOf { it.size },
                                        isExpanded = isWaExpanded,
                                        photoCount = waItems.count { it.type == MediaType.IMAGE },
                                        videoCount = waItems.count { it.type == MediaType.VIDEO },
                                        pdfCount   = waItems.count { it.type == MediaType.PDF },
                                        structuralVersion = currentVersion,
                                        audioCount = waItems.count { it.type == MediaType.AUDIO }
                                    ))
                                    if (isWaExpanded) {
                                        waItems.forEachIndexed { index, mediaItem ->
                                            treeItems.add(GalleryItem.Media(mediaItem, group.key, index, null, currentVersion))
                                        }
                                    }
                                }
                                treeItems.add(GalleryItem.Footer(group.key, currentVersion))
                            }
                        }
                    }
                }
                galleryItems = treeItems
                flatForViewer = visibleGroups.flatMap { it.items }
            }

            // Guard: if this job was cancelled (e.g. a newer loadMedia call superseded it),
            // do NOT post stale results that would overwrite the newer job's correct output.
            // This is the key fix for the import-first-time bug: onResume() launches job1
            // with old prefs; the import updates prefs and launches job2 which cancels job1.
            // Without this check, job1 (which has no suspension points to honour the
            // cancellation early) runs to completion and overwrites job2's correct results.
            if (!isActive) return@launch

            galleryFlatItems = flatForViewer   // saved so clearSearch() can restore it
            _flatMediaItems.postValue(flatForViewer)
            _galleryItems.postValue(galleryItems)
            _doneMonthsAvailable.postValue(doneGroups)
            _isLoading.postValue(false)

            // Kick off background indexing whenever raw media is freshly fetched.
            if (forceRefresh || cachedRawMedia == null) {
                val images = filteredMedia.filter { it.type == MediaType.IMAGE }
                val pdfs   = filteredMedia.filter { it.type == MediaType.PDF }
                withContext(Dispatchers.Main) {
                    startLabelingInBackground(images)
                    startPdfIndexingInBackground(pdfs)
                }
            }

            // Fire scroll events after list is posted (both deliver on main thread in order)
            if (pendingScrollToTop) {
                pendingScrollToTop = false
                _scrollToTopEvent.postValue(Unit)
            }
            val monthKey = pendingScrollToMonthKey
            if (monthKey != null) {
                pendingScrollToMonthKey = null
                _scrollToMonthKey.postValue(monthKey)
            }
        }
    }

    fun deleteMedia(items: List<MediaItem>) {
        if (items.isEmpty()) return

        val totalBytes = items.sumOf { it.size }
        val fingerprints = items.map { getFingerprint(it) }
        deletedFingerprintsInFlight.addAll(fingerprints)
        sessionDeletedFingerprints.addAll(fingerprints)   // permanent for this session

        // Instant UI feedback for the current instance: Update observers immediately
        val currentList = _galleryItems.value?.toMutableList() ?: mutableListOf()
        currentList.removeAll { it is GalleryItem.Media && fingerprints.contains(getFingerprint(it.mediaItem)) }
        _galleryItems.value = currentList
        
        // Also update local cache so a quick reload doesn't bring them back while still in flight
        cachedRawMedia = cachedRawMedia?.filter { !fingerprints.contains(getFingerprint(it)) }
        
        // Update stats immediately for the top bar
        cachedRawMedia?.let { media ->
            _totalPhotos.postValue(media.count { it.type == MediaType.IMAGE })
            _totalVideos.postValue(media.count { it.type == MediaType.VIDEO })
            _totalPdfs.postValue(media.count { it.type == MediaType.PDF })
            _totalAudios.postValue(media.count { it.type == MediaType.AUDIO })
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Clean up PDF index for any deleted PDFs
                val deletedPdfs = items.filter { it.type == MediaType.PDF }
                if (deletedPdfs.isNotEmpty()) {
                    deletedPdfs.forEach { pdf -> pdfIndexStore.deleteEntry(pdf.id) }
                    bm25IndexCache = null
                }

                val resolver = getApplication<Application>().contentResolver

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // PDFs are accessed via MANAGE_EXTERNAL_STORAGE and may live in
                    // MediaStore.Files or MediaStore.Downloads collections.
                    // createDeleteRequest can throw for those URIs ("not owned by calling app"),
                    // which would roll back sessionDeletedFingerprints and make the PDF reappear.
                    // Delete PDFs directly via resolver.delete() — MANAGE_EXTERNAL_STORAGE covers this.
                    val pdfItems   = items.filter { it.type == MediaType.PDF }
                    val mediaItems = items.filter { it.type != MediaType.PDF }

                    pdfItems.forEach { item ->
                        try {
                            resolver.delete(android.net.Uri.parse(item.uri), null, null)
                        } catch (e: Exception) {
                            Log.e("GalleryViewModel", "PDF direct delete failed: ${item.displayName}", e)
                        }
                    }

                    if (mediaItems.isNotEmpty()) {
                        // Android 11+: use createDeleteRequest for images/videos.
                        // On Android 12+ with MANAGE_MEDIA granted this runs silently (no dialog).
                        // On Android 11 or without MANAGE_MEDIA a system confirmation dialog appears.
                        val intentSender = repo.createDeleteRequest(mediaItems)
                        if (intentSender != null) {
                            pendingItemsToDelete = mediaItems
                            pendingBytesToFree = mediaItems.sumOf { it.size }
                            _deletePermissionRequest.postValue(intentSender)
                            // PDF deletions already done above; the media deletions will
                            // trigger updateUiAfterDeletion via onDeletePermissionResult.
                        } else {
                            // createDeleteRequest failed — roll back media items only.
                            val mediaFps = mediaItems.map { getFingerprint(it) }
                            deletedFingerprintsInFlight.removeAll(mediaFps.toSet())
                            sessionDeletedFingerprints.removeAll(mediaFps.toSet())
                            cachedRawMedia = null
                            loadMedia(forceRefresh = true)
                        }
                    } else {
                        // Only PDFs — no createDeleteRequest needed, fire completion now.
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            updateUiAfterDeletion(fingerprints, totalBytes)
                        }
                    }
                } else {
                    // Pre-R: direct deletion, no dialog needed.
                    items.forEach { item ->
                        try {
                            resolver.delete(android.net.Uri.parse(item.uri), null, null)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        updateUiAfterDeletion(fingerprints, totalBytes)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                deletedFingerprintsInFlight.removeAll(fingerprints.toSet())
                sessionDeletedFingerprints.removeAll(fingerprints.toSet())
                cachedRawMedia = null
                loadMedia(forceRefresh = true)
            }
        }
    }

    fun onDeletePermissionResult(success: Boolean) {
        val items = pendingItemsToDelete
        val bytes = pendingBytesToFree
        pendingItemsToDelete = null
        pendingBytesToFree = 0L
        if (success && items != null) {
            updateUiAfterDeletion(items.map { getFingerprint(it) }, bytes)
        } else if (items != null) {
            val fps = items.map { getFingerprint(it) }
            deletedFingerprintsInFlight.removeAll(fps)
            sessionDeletedFingerprints.removeAll(fps)   // roll back — user cancelled
            loadMedia(forceRefresh = true)
        }
        _deletePermissionRequest.value = null
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    /**
     * Rename [item] to [newDisplayName].
     *
     * Strategy (tries cheapest path first):
     *   1. Direct ContentResolver.update() — works immediately when MANAGE_EXTERNAL_STORAGE
     *      is granted or the file was created by this app.
     *   2. If that fails on Android 11+, request write permission via createWriteRequest
     *      (shows a one-tap system dialog).  [onRenamePermissionResult] completes the rename
     *      after the user approves.
     *   3. Posts a non-null [renameResult]: new name on success, "" on failure.
     */
    fun initiateRename(item: MediaItem, newDisplayName: String) {
        renameInFlight = true   // raise guard BEFORE the IO work triggers MediaStore notifications
        viewModelScope.launch(Dispatchers.IO) {
            // Attempt direct rename first
            if (repo.renameMedia(item, newDisplayName)) {
                applyCachedRename(item, newDisplayName)   // schedules renameInFlight = false after 5 s
                _renameResult.postValue(newDisplayName)
                return@launch
            }
            // Direct failed — on Android 11+ request write permission and retry after approval
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intentSender = repo.createRenameWriteRequest(item)
                if (intentSender != null) {
                    pendingRenameItem = item
                    pendingRenameNewName = newDisplayName
                    _renamePermissionRequest.postValue(intentSender)
                    return@launch   // guard stays up; cleared in onRenamePermissionResult
                }
            }
            renameInFlight = false   // all paths failed — nothing to protect
            _renameResult.postValue("")
        }
    }

    fun onRenamePermissionResult(granted: Boolean) {
        val item = pendingRenameItem
        val name = pendingRenameNewName
        pendingRenameItem = null
        pendingRenameNewName = null
        _renamePermissionRequest.postValue(null)

        if (!granted || item == null || name == null) {
            renameInFlight = false   // user cancelled — nothing to protect
            _renameResult.postValue("")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val success = repo.renameMedia(item, name)
            if (success) applyCachedRename(item, name)   // schedules renameInFlight = false after 5 s
            else renameInFlight = false
            _renameResult.postValue(if (success) name else "")
        }
    }

    /**
     * Patch the renamed item's displayName in the in-memory cache, then schedule
     * a forced refresh after the rename guard expires.
     *
     * Two-phase approach:
     *   1. Immediately update cachedRawMedia so the item shows its new name without
     *      waiting for MediaStore to finish re-indexing.
     *   2. After 6 s (≥ observer's 3 s debounce + MediaScannerConnection latency),
     *      drop the renameInFlight guard and do one forceRefresh — this replaces any
     *      stale MediaStore ID (devices that delete+reinsert the row on rename) with
     *      the fresh entry the scanner just created.
     */
    private fun applyCachedRename(item: MediaItem, newDisplayName: String) {
        cachedRawMedia = cachedRawMedia?.map { m ->
            if (m.id == item.id && m.type == item.type) m.copy(displayName = newDisplayName) else m
        }
        viewModelScope.launch {
            delay(6_000)
            renameInFlight = false
            // Now that the guard is down, pull fresh data from MediaStore so any new
            // MediaStore entry (possibly with a new ID) replaces the cache-patched one.
            loadMedia(forceRefresh = true)
        }
    }

    fun clearRenameResult() { _renameResult.value = null }

    private fun updateUiAfterDeletion(fingerprints: List<String>, bytesFreed: Long = 0L) {
        if (bytesFreed > 0L) _storageSavedEvent.postValue(bytesFreed)

        // Immediately remove deleted items from the flat list so MediaViewerActivity
        // can advance to the next item without waiting for the first MediaStore refresh.
        val flatNow = _flatMediaItems.value
        if (!flatNow.isNullOrEmpty()) {
            _flatMediaItems.postValue(flatNow.filter { !fingerprints.contains(getFingerprint(it)) })
        }

        _deletionCompletedEvent.postValue(true)

        viewModelScope.launch {
            // Progressive refreshes to sync with MediaStore.
            // sessionDeletedFingerprints guarantees items never reappear in this session
            // regardless of how slowly (or incompletely) MediaStore updates its index.
            // deletedFingerprintsInFlight auto-clears once MediaStore confirms deletion.
            // NO timer-based cleanup — timers caused race conditions with overlapping deletions.
            delay(500);   loadMedia(forceRefresh = true)
            delay(2000);  loadMedia(forceRefresh = true)
            delay(5000);  loadMedia(forceRefresh = true)
            delay(15000); loadMedia(forceRefresh = true)
        }
    }

    fun markMonthDone(year: Int, month: Int) {
        prefs.markMonthDone(year, month)
        autoSaveBackup()
        loadMedia(forceRefresh = false)
    }

    fun restoreMonth(year: Int, month: Int) {
        prefs.unmarkMonthDone(year, month)
        autoSaveBackup()
        val key = prefs.monthKey(year, month)
        expandedYears.add(year)   // ensure year is open so we can scroll to the month
        expandedMonths.add(key)   // ensure month is open so items are visible
        pendingScrollToMonthKey = key
        structuralVersion++
        loadMedia(forceRefresh = false)
    }

    /** Unhide all months belonging to [year] in a single batch load. */
    fun restoreYear(year: Int) {
        val monthsInYear = _doneMonthsAvailable.value?.filter { it.year == year } ?: return
        if (monthsInYear.isEmpty()) return
        monthsInYear.forEach { group ->
            prefs.unmarkMonthDone(group.year, group.month)
            expandedYears.add(group.year)
            expandedMonths.add(prefs.monthKey(group.year, group.month))
        }
        autoSaveBackup()
        pendingScrollToMonthKey = prefs.monthKey(year, monthsInYear.first().month)
        structuralVersion++
        loadMedia(forceRefresh = false)
    }

    // ── MediaStore observer ───────────────────────────────────────────────────

    /**
     * Watches MediaStore for new images, videos, and downloads.  When any change
     * arrives (e.g. WhatsApp saves a received video) we schedule a debounced
     * forceRefresh so the item appears automatically without the user having to
     * tap the refresh button.
     *
     * 3-second debounce: a burst of incoming files (e.g. bulk WhatsApp gallery
     * download) coalesces into a single reload rather than one per file.
     *
     * Skipped while the app's own deletion is in flight — deletedFingerprintsInFlight
     * being non-empty means we caused the MediaStore change ourselves, and
     * updateUiAfterDeletion already schedules its own progressive refreshes.
     */
    private fun registerMediaObserver() {
        val handler = Handler(Looper.getMainLooper())
        val refresh = Runnable { loadMedia(forceRefresh = true) }

        mediaObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // Skip if we triggered this change via our own deletion or rename.
                if (deletedFingerprintsInFlight.isNotEmpty()) return
                if (renameInFlight) return
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, 3_000L)
            }
        }

        val resolver = getApplication<Application>().contentResolver
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver!!
        )
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver!!
        )
        resolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver!!
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.registerContentObserver(
                MediaStore.Downloads.getContentUri("external"), true, mediaObserver!!
            )
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Run a fuzzy search across ML labels and filenames for [query].
     * Results are posted to [searchResults]; the gallery stays hidden while
     * results is non-null.  Cancels any in-flight search before starting a new one.
     */
    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) { clearSearch(); return }

        searchJob = viewModelScope.launch {
            val allMedia = cachedRawMedia ?: return@launch
            val labels   = _photoLabels.value ?: emptyMap()

            // Build/reuse the BM25 index (loads from ~N small files the first time).
            // Skip entirely when PDF content search is disabled by the user.
            val bm25: PdfBm25Index? = withContext(Dispatchers.IO) {
                if (prefs.isPdfContentSearchEnabled() && allMedia.any { it.type == MediaType.PDF })
                    getOrBuildBm25Index()   // returns null on OOM (auto-disables + notifies user)
                else null
            }

            val results = withContext(Dispatchers.Default) {
                SearchEngine.search(query, allMedia, labels, bm25)
            }

            val items = results.mapIndexed { idx, r ->
                GalleryItem.Media(
                    mediaItem         = r.item,
                    monthKey          = "",
                    indexInMonth      = idx,
                    dateLabel         = r.matchReason.ifBlank { null },
                    structuralVersion = 0
                )
            }
            _searchResults.postValue(items)
        }
    }

    /**
     * Point [flatMediaItems] at [items] so [MediaViewerActivity] swipes through
     * search results instead of the full gallery list.  Called from MainActivity
     * just before opening the viewer from a search result tap.
     */
    fun setSearchFlatItems(items: List<MediaItem>) {
        _flatMediaItems.value = items
    }

    /** Exit search mode and restore normal gallery. */
    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = null
        _flatMediaItems.value = galleryFlatItems  // restore gallery swipe list
    }

    // ── Labeling pipeline ─────────────────────────────────────────────────────

    /**
     * Kick off background labeling for any IMAGE items that aren't yet in the cache.
     *
     * Strategy:
     *  1. Merge currently-cached labels into [_photoLabels] immediately (zero-latency
     *     for items the user has seen before).
     *  2. Collect unlabeled image IDs and run ML Kit inference on them in batches of 10,
     *     posting incremental updates to [_photoLabels] as each batch finishes.
     *
     * The job is cancelled and restarted whenever a new media load runs, so stale
     * labeling work from a previous load never stomps on fresh results.
     */
    fun startLabelingInBackground(images: List<MediaItem>) {
        labelingJob?.cancel()
        labelingJob = viewModelScope.launch(Dispatchers.IO) {
            // 1. Seed LiveData with whatever is already cached
            val cached = labelCache.loadAll().toMutableMap()
            if (cached.isNotEmpty()) _photoLabels.postValue(cached.toMap())

            // 2. Identify images we haven't labeled yet
            val unlabeled = images.filter { it.type == MediaType.IMAGE && !labelCache.hasEntry(it.id) }
            if (unlabeled.isEmpty()) return@launch

            Log.d("GalleryViewModel", "Labeling ${unlabeled.size} new images in background")

            // 3. Process in batches of 10; yield between batches to stay responsive
            val BATCH = 10
            for (batch in unlabeled.chunked(BATCH)) {
                if (!isActive) break
                for (item in batch) {
                    if (!isActive) break
                    val labels = repo.labelImage(item)   // now Map<String, Float>
                    labelCache.saveLabels(item.id, labels)
                    if (labels.isNotEmpty()) cached[item.id] = labels
                }
                _photoLabels.postValue(cached.toMap())
                kotlinx.coroutines.delay(50)
            }
            Log.d("GalleryViewModel", "Labeling complete. ${cached.size} images labeled.")
        }
    }

    /**
     * Build a BM25 word-count index for any PDFs not yet in [pdfIndexStore].
     *
     * Steps:
     *  1. If the local index is empty, attempt a restore from the Downloads backup
     *     (covers fresh-install / app-data-clear / reinstall scenarios).
     *  2. Filter to PDFs that still lack an index entry.
     *  3. Extract word counts via PDFBox (first 5 pages) and persist each result.
     *  4. Post progress updates to [pdfIndexProgress] as each PDF completes.
     *  5. When finished, save a gzip-compressed backup to Downloads and invalidate
     *     the in-memory BM25 index cache so the next search picks up the new data.
     *
     * Progress is null while idle and non-null during an active run.
     */
    fun startPdfIndexingInBackground(pdfs: List<MediaItem>) {
        pdfIndexingJob?.cancel()
        if (pdfs.isEmpty() || !prefs.isPdfContentSearchEnabled()) {
            _pdfIndexProgress.postValue(null)
            return
        }

        pdfIndexingJob = viewModelScope.launch(Dispatchers.IO) {
            // Step 1: restore from Downloads backup if local index is empty
            // countEntries() is cheap (filesystem metadata only — no file contents loaded)
            val existingCount = pdfIndexStore.countEntries()
            if (existingCount == 0 && pdfs.isNotEmpty()) {
                restorePdfIndexFromBackup(pdfs)
            }

            // Step 2: find what still needs indexing
            val unindexed = pdfs.filter { !pdfIndexStore.hasEntry(it.id) }
            if (unindexed.isEmpty()) {
                _pdfIndexProgress.postValue(null)
                return@launch
            }

            val total = unindexed.size
            var indexed = 0
            Log.d("GalleryViewModel", "PDF indexing: $total unindexed PDFs")
            _pdfIndexProgress.postValue(PdfIndexProgress(0, total, false))

            // Step 3 & 4: extract and persist, with progress updates.
            //
            // Two-level OOM defence:
            //   a) extractPdfWords() catches Throwable per-PDF → returns empty map, continues.
            //   b) The outer try/catch here catches OOM that strikes the loop infrastructure
            //      itself (writing a words file, allocating progress objects, etc.).
            //      On OOM: PDF content search is auto-disabled and the user is notified.
            //
            // Batching: PDFBox accumulates font/image objects across calls faster than GC
            // can collect without a suspension point.  10 PDFs + 200 ms gives GC time.
            try {
                for (batch in unindexed.chunked(10)) {
                    if (!isActive) break
                    for (item in batch) {
                        if (!isActive) break
                        val words = repo.extractPdfWords(item)   // safe — catches OOM internally
                        if (words.isEmpty()) pdfIndexStore.markEmpty(item.id)
                        else pdfIndexStore.saveWordCounts(item.id, words)
                        bm25IndexCache = null
                        indexed++
                        _pdfIndexProgress.postValue(PdfIndexProgress(indexed, total, false))
                    }
                    delay(200)   // suspension point — lets GC reclaim PDFBox objects
                }
            } catch (oom: OutOfMemoryError) {
                Log.e("GalleryViewModel", "PDF indexing OOM after $indexed/$total — auto-disabling", oom)
                prefs.setPdfContentSearchEnabled(false)
                bm25IndexCache = null
                _pdfIndexProgress.postValue(null)
                _pdfIndexOomEvent.postValue(Unit)
                return@launch
            }

            Log.d("GalleryViewModel", "PDF indexing complete. $indexed / $total processed.")
            _pdfIndexProgress.postValue(PdfIndexProgress(indexed, total, true))

            // Step 5: backup after a successful complete run
            if (isActive) savePdfIndexBackup(pdfs)
        }
    }

    // ── BM25 index cache ──────────────────────────────────────────────────────

    private fun getOrBuildBm25Index(): PdfBm25Index? {
        bm25IndexCache?.let { return it }
        return try {
            val entries = pdfIndexStore.loadAll()
            PdfBm25Index(entries).also { bm25IndexCache = it }
        } catch (oom: OutOfMemoryError) {
            Log.e("GalleryViewModel", "BM25 index build OOM — auto-disabling PDF content search", oom)
            prefs.setPdfContentSearchEnabled(false)
            bm25IndexCache = null
            _pdfIndexOomEvent.postValue(Unit)
            null
        }
    }

    // ── PDF index backup / restore ────────────────────────────────────────────

    /**
     * Serialise the PDF word-count index as gzip-compressed JSON and write it to
     * [PDF_INDEX_BACKUP_FILENAME] in the user's Downloads folder.
     *
     * **Memory-efficient streaming design:**
     * Uses Gson's [JsonWriter] to stream JSON directly into a [GZIPOutputStream]
     * piped to a [ContentResolver] output stream.  Each PDF's word counts are
     * fetched one at a time via [PdfIndexStore.getWordCounts] and written
     * immediately — the entire dataset is never held in memory at once.
     * Peak extra memory ≈ one PDF's word counts (~6 KB) + I/O buffers (~64 KB).
     *
     * JSON format:
     * {
     *   "version": 1,
     *   "exportedAt": "2026-06-09T10:30:00",
     *   "pageLimit": 5,
     *   "pdfs": {
     *     "<displayName>_<size>": {"contract": 5, "invoice": 3},
     *     "<displayName>_<size>": {}   ← empty = no-text sentinel
     *   }
     * }
     */
    private fun savePdfIndexBackup(allPdfs: List<MediaItem>) {
        try {
            val app      = getApplication<Application>()
            val resolver = app.contentResolver
            val ts       = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

            /** Stream JSON → GZIP → [out]. */
            fun writeTo(out: java.io.OutputStream) {
                GZIPOutputStream(out).use { gzip ->
                    com.google.gson.stream.JsonWriter(
                        java.io.OutputStreamWriter(gzip, Charsets.UTF_8)
                    ).use { jw ->
                        jw.beginObject()
                        jw.name("version").value(1L)
                        jw.name("exportedAt").value(ts)
                        jw.name("pageLimit").value(5L)
                        jw.name("pdfs")
                        jw.beginObject()
                        var written = 0
                        for (item in allPdfs) {
                            // getWordCounts reads one small file — never the whole set
                            val counts = pdfIndexStore.getWordCounts(item.id) ?: continue
                            jw.name(getFingerprint(item))
                            jw.beginObject()
                            for ((word, count) in counts) jw.name(word).value(count.toLong())
                            jw.endObject()
                            written++
                        }
                        jw.endObject()  // pdfs
                        jw.endObject()  // root
                        Log.i("GalleryViewModel", "PDF backup: streamed $written entries")
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.getContentUri("external")
                try {
                    resolver.delete(collection,
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                        arrayOf(PDF_INDEX_BACKUP_FILENAME))
                    val base = PDF_INDEX_BACKUP_FILENAME.removeSuffix(".json.gz")
                    resolver.delete(collection,
                        "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                        arrayOf("$base (%).json.gz"))
                } catch (_: Exception) {}

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, PDF_INDEX_BACKUP_FILENAME)
                    put(MediaStore.Downloads.MIME_TYPE, "application/gzip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(collection, values) ?: return
                resolver.openOutputStream(uri)?.use { writeTo(it) }
            } else {
                @Suppress("DEPRECATION")
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    PDF_INDEX_BACKUP_FILENAME
                )
                file.parentFile?.mkdirs()
                file.outputStream().use { writeTo(it) }
            }
        } catch (e: Exception) {
            Log.e("GalleryViewModel", "PDF index backup failed", e)
        }
    }

    /**
     * Attempt to restore the PDF word-count index from the Downloads backup.
     * Called at the start of the first indexing run if the local index is empty.
     *
     * **Memory-efficient streaming design:**
     * Opens an [InputStream] directly from MediaStore (or the file) and parses
     * JSON via Gson's [JsonReader] without ever loading the full JSON string or
     * building a JSONObject tree.  Each PDF's data is saved to [PdfIndexStore]
     * immediately; only one PDF's word counts are in memory at a time.
     */
    private fun restorePdfIndexFromBackup(currentPdfs: List<MediaItem>) {
        try {
            val app      = getApplication<Application>()
            val resolver = app.contentResolver

            // Build fingerprint → MediaItem lookup (cheap: ~130 KB for 1300 PDFs)
            val fingerprintToItem = currentPdfs.associateBy { getFingerprint(it) }

            /** Parse the GZIP JSON stream from [inputStream] and restore entries. */
            fun restoreFrom(inputStream: java.io.InputStream): Int {
                var restored = 0
                GZIPInputStream(inputStream).use { gzip ->
                    com.google.gson.stream.JsonReader(
                        java.io.InputStreamReader(gzip, Charsets.UTF_8)
                    ).use { jr ->
                        jr.beginObject()
                        while (jr.hasNext()) {
                            if (jr.nextName() == "pdfs") {
                                jr.beginObject()
                                while (jr.hasNext()) {
                                    val fingerprint = jr.nextName()
                                    val wordCounts  = HashMap<String, Int>()
                                    jr.beginObject()
                                    while (jr.hasNext()) {
                                        wordCounts[jr.nextName()] = jr.nextInt()
                                    }
                                    jr.endObject()

                                    // Save immediately — wordCounts goes out of scope after this block
                                    val item = fingerprintToItem[fingerprint] ?: continue
                                    if (wordCounts.isEmpty()) pdfIndexStore.markEmpty(item.id)
                                    else pdfIndexStore.saveWordCounts(item.id, wordCounts)
                                    restored++
                                }
                                jr.endObject()
                            } else {
                                jr.skipValue()
                            }
                        }
                        jr.endObject()
                    }
                }
                return restored
            }

            val restored: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.getContentUri("external")
                val uri = resolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf(PDF_INDEX_BACKUP_FILENAME),
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    android.content.ContentUris.withAppendedId(collection, id)
                } ?: run {
                    // Fallback: direct file path (needs MANAGE_EXTERNAL_STORAGE)
                    @Suppress("DEPRECATION")
                    val file = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        PDF_INDEX_BACKUP_FILENAME
                    )
                    if (!file.exists()) return
                    restored = restoreFrom(file.inputStream())
                    Log.i("GalleryViewModel", "PDF index restored (file path): $restored / ${currentPdfs.size}")
                    return
                }
                resolver.openInputStream(uri)?.use { restored = restoreFrom(it) } ?: return
            } else {
                @Suppress("DEPRECATION")
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    PDF_INDEX_BACKUP_FILENAME
                )
                if (!file.exists()) return
                restored = restoreFrom(file.inputStream())
            }

            Log.i("GalleryViewModel", "PDF index restored: $restored / ${currentPdfs.size} matched")
        } catch (e: Exception) {
            Log.e("GalleryViewModel", "PDF index restore failed", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        labelingJob?.cancel()
        pdfIndexingJob?.cancel()
        repo.closeLabeler()
        mediaObserver?.let {
            getApplication<Application>().contentResolver.unregisterContentObserver(it)
        }
    }

    /** Discard the cached BM25 index so the next search rebuilds it. */
    fun invalidateBm25Index() {
        bm25IndexCache = null
    }

    fun isPdfContentSearchEnabled(): Boolean = prefs.isPdfContentSearchEnabled()

    /**
     * Enable or disable PDF content indexing and BM25 search.
     * When disabled: background indexing stops immediately, BM25 is never built,
     * and PDF results come from filename matching only.
     * When re-enabled: indexing resumes on the next loadMedia() call.
     */
    fun setPdfContentSearchEnabled(enabled: Boolean) {
        prefs.setPdfContentSearchEnabled(enabled)
        if (!enabled) {
            pdfIndexingJob?.cancel()
            bm25IndexCache = null
            _pdfIndexProgress.postValue(null)
        } else {
            // Trigger a fresh indexing pass with the current media list
            cachedRawMedia?.filter { it.type == MediaType.PDF }?.let {
                startPdfIndexingInBackground(it)
            }
        }
    }

    // ── Auto-backup ───────────────────────────────────────────────────────────

    /**
     * Silently write the current hidden-month set to mediacurator_hidden.json in Downloads.
     * Called after every hide / unhide.  If the set is empty the file is deleted.
     * Runs entirely on IO — no UI impact.
     */
    private fun autoSaveBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val months = prefs.getDoneMonths()
                val app    = getApplication<Application>()
                val resolver = app.contentResolver

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val collection = MediaStore.Downloads.getContentUri("external")

                    // Delete ALL MediaStore rows whose display name starts with our base name.
                    // After an app-ID change the original file is owned by the old package and
                    // the delete below silently fails, causing Android to create "(1)", "(2)"…
                    // variants on each subsequent write.  Querying by LIKE catches those too.
                    try {
                        // Exact name (own files after first reinstall)
                        resolver.delete(
                            collection,
                            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                            arrayOf(AUTO_BACKUP_FILENAME)
                        )
                        // Numbered variants we own: "mediacurator_hidden (1).json" etc.
                        val baseName = AUTO_BACKUP_FILENAME.removeSuffix(".json")
                        resolver.delete(
                            collection,
                            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                            arrayOf("$baseName (%).json")
                        )
                    } catch (_: Exception) {}

                    if (months.isEmpty()) return@launch  // nothing to write; deletion is the "backup"

                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, AUTO_BACKUP_FILENAME)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = resolver.insert(collection, values) ?: return@launch
                    resolver.openOutputStream(uri)?.use { it.write(buildBackupJson(months).toByteArray(Charsets.UTF_8)) }
                } else {
                    @Suppress("DEPRECATION")
                    val file = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        AUTO_BACKUP_FILENAME
                    )
                    if (months.isEmpty()) { file.delete(); return@launch }
                    file.parentFile?.mkdirs()
                    file.writeText(buildBackupJson(months), Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Auto-backup failed", e)
            }
        }
    }

    /**
     * On first launch (or after app-data clear / reinstall / app-ID change), silently
     * restore from the auto-backup file in Downloads if local prefs are empty.
     *
     * Two-pass strategy for Android 10+:
     *   1. MediaStore query  — works for files owned by this package.
     *   2. Direct file path  — fallback for files written by a previous package name
     *      (e.g. com.com.anant.com.anant.mediacurator → com.anant.com.anant.mediacurator). The file is physically
     *      present but MediaStore ownership has changed, so pass 1 returns nothing.
     *
     * Race-condition fix: loadMedia() is called after a successful restore so the
     * gallery immediately reflects the restored state (the init-block call to loadMedia
     * from requestPermissionsIfNeeded fires before this async restore completes).
     */
    /**
     * Look for the auto-backup file in Downloads.
     * If found and prefs are still empty, post a prompt LiveData so the Activity
     * can show a confirmation dialog before importing.
     *
     * Called automatically at init and again from MainActivity after
     * MANAGE_EXTERNAL_STORAGE is granted (the direct-file fallback needs it).
     */
    internal fun checkAndAutoRestore() {
        if (prefs.getDoneMonths().isNotEmpty()) return  // prefs already populated — nothing to do
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app      = getApplication<Application>()
                val resolver = app.contentResolver

                val json: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Pass 1: MediaStore (own-package files)
                    val collection = MediaStore.Downloads.getContentUri("external")
                    val fromMediaStore = resolver.query(
                        collection,
                        arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                        arrayOf(AUTO_BACKUP_FILENAME),
                        "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        val id  = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        val uri = ContentUris.withAppendedId(collection, id)
                        resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    }

                    // Pass 2: direct path fallback (needs MANAGE_EXTERNAL_STORAGE;
                    // covers reinstall, data-clear, or a file written by a different package)
                    fromMediaStore ?: run {
                        @Suppress("DEPRECATION")
                        val file = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            AUTO_BACKUP_FILENAME
                        )
                        if (file.exists()) try { file.readText(Charsets.UTF_8) } catch (_: Exception) { null }
                        else null
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val file = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        AUTO_BACKUP_FILENAME
                    )
                    if (file.exists()) file.readText(Charsets.UTF_8) else null
                }

                if (json != null) {
                    val obj    = org.json.JSONObject(json)
                    val arr    = obj.getJSONArray("hiddenMonths")
                    val months = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    if (months.isNotEmpty()) {
                        Log.i("GalleryViewModel", "Auto-restore: found ${months.size} hidden months, prompting user")
                        // Hand off to the Activity — don't import silently.
                        _autoRestorePrompt.postValue(months)
                    }
                }
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Auto-restore check failed", e)
            }
        }
    }

    /** User tapped "Import" in the auto-restore confirmation dialog. */
    fun confirmAutoRestore(months: Set<String>) {
        _autoRestorePrompt.value = null
        prefs.setDoneMonths(months)
        loadMedia(forceRefresh = true)
    }

    /** User tapped "Skip" in the auto-restore confirmation dialog. */
    fun dismissAutoRestore() {
        _autoRestorePrompt.value = null
    }

    private fun buildBackupJson(months: Set<String>): String {
        val ts  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val arr = months.sorted().joinToString(",\n    ") { "\"$it\"" }
        return "{\n  \"version\": 1,\n  \"exportedAt\": \"$ts\",\n  \"hiddenMonths\": [\n    $arr\n  ]\n}"
    }
}
