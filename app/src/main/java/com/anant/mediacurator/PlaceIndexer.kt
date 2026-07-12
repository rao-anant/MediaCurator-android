package com.anant.mediacurator

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

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
     * Index every image in [images] not already recorded in [store]: read GPS → nearest city → save
     * its names. Photos with no GPS are saved empty so they aren't re-read next pass. The k-d tree is
     * built once and released when done. [shouldContinue] cancels; [onProgress] reports (done,total).
     */
    fun indexImages(
        context: Context,
        images: List<MediaItem>,
        store: PlaceStore,
        shouldContinue: () -> Boolean = { true },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        store.ensureLoaded()
        // Reinstall: filesDir was wiped, so seed from the Downloads mirror (remapped to current
        // ids by displayName+size) before scanning — this skips re-reading EXIF for known photos.
        if (store.isEmpty()) store.restoreFromDownloads(images)

        val todo = images.filter { it.type == MediaType.IMAGE && !store.hasEntry(it.id, it.size) }
        if (todo.isEmpty()) { onProgress(0, 0); return }

        val geo = loadGeoIndex(context)
        var done = 0
        for (item in todo) {
            if (!shouldContinue()) break
            val ll = readLatLon(context, item)
            val city = if (ll != null) geo.nearest(ll[0], ll[1]) else null
            store.save(item.id, item.size, city)   // null city → stored empty (scanned, no GPS)
            done++
            // Flush often (every 20) so progress survives the app being killed mid-scan — critical on
            // OEMs (e.g. Samsung) that kill backgrounded apps on lock. A killed run then resumes
            // instead of re-scanning from the top.
            if (done % 20 == 0) { store.flush(); onProgress(done, todo.size) }
        }
        store.flush()
        // Refresh the reinstall-survival mirror now that new photos are indexed.
        if (done > 0) store.backupToDownloads(images)
        onProgress(done, todo.size)
    }
}
