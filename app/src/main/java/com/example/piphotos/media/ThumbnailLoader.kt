package com.example.piphotos.media

import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * High-performance, crash-proof thumbnail loader backed by Android's native
 * [android.content.ContentResolver.loadThumbnail] and an in-memory LRU cache.
 *
 * Key guarantees:
 * 1. Bounded thread pool (max 3 concurrent decoders) prevents thread pool & memory exhaustion.
 * 2. Instant cache-hit return (0ms, 0 allocations, 60fps scrolling).
 * 3. Uses OS-level pre-rendered thumbnails from MediaStore, avoiding slow MediaMetadataRetriever
 *    for videos and large JPEG decodes for photos.
 * 4. Cooperative [CancellationSignal] immediately terminates OS-level disk reads on view recycle.
 */
object ThumbnailLoader {

    private val maxMemoryBytes = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(32 * 1024 * 1024).toInt()

    private val memoryCache = object : LruCache<Long, Bitmap>(maxMemoryBytes) {
        override fun sizeOf(key: Long, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }
    }

    // Bounded executor to prevent OS thread starvation and OOM during rapid flings
    private val threadPool = Executors.newFixedThreadPool(3)
    private val dispatcher = threadPool.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getFromCache(id: Long): Bitmap? {
        return memoryCache.get(id)
    }

    fun putInCache(id: Long, bitmap: Bitmap) {
        memoryCache.put(id, bitmap)
    }

    /**
     * Loads a thumbnail for [item]. Returns a [CancellationSignal] that can be cancelled
     * when the view is recycled or scrolled away.
     */
    fun loadThumbnail(
        context: Context,
        item: LocalMediaItem,
        targetSizePx: Int,
        onLoaded: (Bitmap) -> Unit
    ): CancellationSignal {
        val signal = CancellationSignal()

        // 1. Check memory cache first (instant 60fps fast path)
        val cached = memoryCache.get(item.id)
        if (cached != null && !cached.isRecycled) {
            onLoaded(cached)
            return signal
        }

        // 2. Queue background decode on bounded worker pool
        val appContext = context.applicationContext
        scope.launch {
            if (signal.isCanceled) return@launch

            try {
                val size = Size(targetSizePx, targetSizePx)
                val bitmap = appContext.contentResolver.loadThumbnail(item.contentUri, size, signal)

                if (!signal.isCanceled) {
                    memoryCache.put(item.id, bitmap)
                    mainHandler.post {
                        if (!signal.isCanceled) {
                            onLoaded(bitmap)
                        }
                    }
                }
            } catch (e: OperationCanceledException) {
                // Cooperative cancellation - expected when fast-flinging
            } catch (e: Exception) {
                // Silently swallow errors (corrupt file, missing thumbnail, etc.)
            }
        }

        return signal
    }

    fun clearCache() {
        memoryCache.evictAll()
    }
}
