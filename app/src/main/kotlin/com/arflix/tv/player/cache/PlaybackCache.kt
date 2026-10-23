@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.arflix.tv.player.cache

import android.content.Context
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.TreeSet

/**
 * ARVIO Plus: the on-disk playback cache. Everything the player streams is written here, so a
 * rewind to anything already watched (within the size limit) reads from local storage instead of
 * re-downloading. The limit is the user's choice in Settings → Downloads (default 10 GB) and is
 * always capped by the free space actually available.
 */
object PlaybackCache {
    private const val PREFS = "arvio_playback_cache"
    private const val KEY_LIMIT_GB = "limit_gb"
    const val DEFAULT_LIMIT_GB = 10
    val CHOICES_GB = intArrayOf(1, 2, 5, 10, 20, 50)
    /** Free space always left untouched on the device. */
    private const val RESERVE_BYTES = 1L shl 30
    private const val MIN_BYTES = 256L shl 20

    @Volatile private var instance: SimpleCache? = null
    @Volatile private var evictor: AdjustableLruCacheEvictor? = null

    fun dir(context: Context): File =
        File(context.applicationContext.cacheDir, "media3_playback_cache").apply { mkdirs() }

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: run {
            val e = AdjustableLruCacheEvictor(effectiveLimitBytes(context))
            evictor = e
            SimpleCache(dir(context), e, StandaloneDatabaseProvider(context.applicationContext)).also { instance = it }
        }
    }

    fun limitGb(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LIMIT_GB, DEFAULT_LIMIT_GB)

    fun usedBytes(): Long = runCatching { instance?.cacheSpace ?: 0L }.getOrDefault(0L)

    /** The most the cache may grow to right now: what it already holds plus free space, minus a reserve. */
    fun maxAllowedBytes(context: Context): Long {
        val free = runCatching { dir(context).usableSpace }.getOrDefault(0L)
        return (free + usedBytes() - RESERVE_BYTES).coerceAtLeast(MIN_BYTES)
    }

    fun effectiveLimitBytes(context: Context): Long =
        (limitGb(context).toLong() shl 30).coerceAtMost(maxAllowedBytes(context)).coerceAtLeast(MIN_BYTES)

    /** Saves the user's limit and applies it at once (shrinking evicts the oldest data). */
    fun setLimitGb(context: Context, gb: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LIMIT_GB, gb).apply()
        val cache = instance ?: return
        val e = evictor ?: return
        e.maxBytes = effectiveLimitBytes(context)
        runCatching { synchronized(cache) { e.trim(cache) } }
    }

    /** Deletes everything cached. Data a playing video is reading is simply fetched again. */
    fun clear(context: Context) {
        val cache = get(context)
        runCatching {
            for (key in cache.keys.toList()) cache.removeResource(key)
        }
    }
}

/** LRU eviction like media3's LeastRecentlyUsedCacheEvictor, but with a limit that can change. */
class AdjustableLruCacheEvictor(@Volatile var maxBytes: Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan> { a, b ->
        val d = a.lastTouchTimestamp - b.lastTouchTimestamp
        if (d == 0L) a.compareTo(b) else if (d < 0) -1 else 1
    }
    private var currentSize = 0L

    override fun requiresCacheSpanTouches(): Boolean = true
    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        currentSize += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    fun trim(cache: Cache) = evict(cache, 0)

    private fun evict(cache: Cache, requiredSpace: Long) {
        while (currentSize + requiredSpace > maxBytes && spans.isNotEmpty()) {
            cache.removeSpan(spans.first())
        }
    }
}
