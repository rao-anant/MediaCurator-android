package com.anant.mediacurator

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/**
 * Reads photo GPS from EXIF and reverse-geocodes it **offline** (v1.1 Place search). Requires
 * `ACCESS_MEDIA_LOCATION`; on Android Q+ the original (un-redacted) bytes are fetched via
 * [MediaStore.setRequireOriginal]. No network — the nearest city comes from the bundled GeoNames
 * dataset via [GeoIndex]. Run on a background thread.
 */
object PlaceIndexer {

    /** Load the bundled GeoNames dataset (assets/geo_cities.tsv) into a k-d tree. */
    fun loadGeoIndex(context: Context): GeoIndex =
        context.assets.open("geo_cities.tsv").bufferedReader(Charsets.UTF_8).useLines {
            GeoIndex.fromLines(it)
        }

    /** A photo's [lat, lon] from EXIF, or null if it has no location / can't be read. */
    fun readLatLon(context: Context, item: MediaItem): DoubleArray? = try {
        // Q+ redacts EXIF location unless we request the original bytes (needs ACCESS_MEDIA_LOCATION);
        // pre-Q the file isn't redacted, so read the uri directly.
        val base = Uri.parse(item.uri)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(base) else base
        context.contentResolver.openInputStream(uri)?.use { ins ->
            val out = FloatArray(2)
            @Suppress("DEPRECATION")
            if (ExifInterface(ins).getLatLong(out)) doubleArrayOf(out[0].toDouble(), out[1].toDouble())
            else null
        }
    } catch (e: Exception) { null }

    /**
     * Rank a photo by how likely it is to carry GPS, so real places surface fast: camera-roll
     * (DCIM) first; WhatsApp / screenshots / downloads (EXIF-stripped, never located) last.
     */
    private fun gpsLikelihoodRank(item: MediaItem): Int {
        if (item.isWhatsApp) return 2
        val p = item.relativePath.lowercase()
        return when {
            "dcim" in p || "camera" in p                              -> 0
            "screenshot" in p || "download" in p || "whatsapp" in p   -> 2
            else                                                      -> 1
        }
    }

    /**
     * Index every image in [images] not already recorded in [store]: read GPS → nearest city → save
     * its names. Photos with no GPS are saved empty so they aren't re-read next pass.
     *
     * Fast + kill-safe: EXIF header reads run on a small parallel worker pool (they're I/O-bound —
     * ~3-4x throughput), each result is journaled to disk immediately by [PlaceStore.save] (O(1)
     * append — survives OEM lock-kill at any point), and camera-roll photos go first so cities
     * appear right away. Cancellation is cooperative via the caller's coroutine scope.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun indexImages(
        context: Context,
        images: List<MediaItem>,
        store: PlaceStore,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        store.ensureLoaded()
        // Reinstall: filesDir was wiped, so seed from the Downloads mirror (remapped to current
        // ids by displayName+size) before scanning — this skips re-reading EXIF for known photos.
        if (store.isEmpty()) store.restoreFromDownloads(images)

        val todo = images.filter { it.type == MediaType.IMAGE && !store.hasEntry(it.id, it.size) }
            .sortedBy { gpsLikelihoodRank(it) }
        if (todo.isEmpty()) { onProgress(0, 0); return }

        val geo = loadGeoIndex(context)
        // Same worker sizing as photo hashing: parallel streams help on UFS flash, hurt on eMMC.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val workers = if (am.isLowRamDevice || Runtime.getRuntime().availableProcessors() <= 4) 2 else 4
        val dispatcher = Dispatchers.IO.limitedParallelism(workers)
        val counter = java.util.concurrent.atomic.AtomicInteger(0)

        try {
            coroutineScope {
                for (batch in todo.chunked(100)) {
                    ensureActive()   // cancellation checkpoint between batches
                    batch.map { item ->
                        async(dispatcher) {
                            val ll = readLatLon(context, item)
                            val city = if (ll != null) geo.nearest(ll[0], ll[1]) else null
                            store.save(item.id, item.size, city)   // journaled: survives kill
                            val n = counter.incrementAndGet()
                            if (n % 25 == 0) onProgress(n, todo.size)
                        }
                    }.awaitAll()
                }
            }
        } finally {
            // Runs on completion AND cancellation: compact the journal; refresh the reinstall
            // mirror only after new work (also on partial runs, so reinstalls keep the progress).
            store.flush()
            if (counter.get() > 0) store.backupToDownloads(images)
            onProgress(counter.get(), todo.size)
        }
    }
}
