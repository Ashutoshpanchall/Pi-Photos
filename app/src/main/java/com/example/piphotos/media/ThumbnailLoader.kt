package com.example.piphotos.media

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.os.Trace
import android.util.LruCache
import android.util.Size
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Composite key incorporating id, modification generation/timestamp, and pixel target size.
 * Guarantees that:
 * 1. Modified/edited media instantly refreshes rather than returning stale cached bitmaps.
 * 2. 120px filmstrip, 280px grid, and 640px scrub thumbnails are never conflated or reused at the wrong resolution.
 */
data class ThumbnailKey(
    val id: Long,
    val generationModified: Long,
    val size: Int
)

/**
 * High-performance, crash-proof thumbnail loader backed by Android's native
 * [android.content.ContentResolver.loadThumbnail] with resilient [ImageDecoder] fallback,
 * LIFO priority scheduling, automatic prefetching, retry logic, and memory trim handling.
 */
object ThumbnailLoader {

    private val maxMemoryBytes = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(32 * 1024 * 1024).toInt()

    private val memoryCache = object : LruCache<ThumbnailKey, Bitmap>(maxMemoryBytes) {
        override fun sizeOf(key: ThumbnailKey, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }
    }

    private val sequenceGenerator = AtomicLong(0L)

    // Bounded 3-worker thread pool backed by a PriorityBlockingQueue for LIFO visible loading
    private val workQueue = PriorityBlockingQueue<Runnable>()
    private val executor = ThreadPoolExecutor(
        3,
        3,
        0L,
        TimeUnit.MILLISECONDS,
        workQueue
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentPrefetchSignal: CancellationSignal? = null

    fun makeKey(item: LocalMediaItem, size: Int): ThumbnailKey {
        val gen = if (item.generationModified > 0L) item.generationModified else item.dateModifiedSec
        return ThumbnailKey(item.id, gen, size)
    }

    fun getFromCache(item: LocalMediaItem, size: Int): Bitmap? {
        return memoryCache.get(makeKey(item, size))
    }

    fun putInCache(item: LocalMediaItem, size: Int, bitmap: Bitmap) {
        memoryCache.put(makeKey(item, size), bitmap)
    }

    /**
     * Loads a thumbnail for [item] at [targetSizePx].
     *
     * Features:
     * - Immediate 0ms cache-hit return.
     * - LIFO scheduling: newly visible tiles jump to the front of the queue ahead of older tiles.
     * - Dropped requests: when the returned [CancellationSignal] is cancelled, the task is immediately
     *   removed from the executor and aborted.
     * - Retry once: if decoding fails initially, retries once before invoking [onError].
     */
    fun loadThumbnail(
        context: Context,
        item: LocalMediaItem,
        targetSizePx: Int,
        onError: (() -> Unit)? = null,
        onLoaded: (Bitmap) -> Unit
    ): CancellationSignal {
        val signal = CancellationSignal()
        val key = makeKey(item, targetSizePx)

        // 1. Check memory cache first (instant 120fps fast path)
        val cached = memoryCache.get(key)
        if (cached != null && !cached.isRecycled) {
            onLoaded(cached)
            return signal
        }

        // 2. Queue LIFO background decode
        val appContext = context.applicationContext
        val seq = sequenceGenerator.incrementAndGet()
        val task = ThumbnailTask(
            appContext = appContext,
            key = key,
            item = item,
            targetSizePx = targetSizePx,
            signal = signal,
            isPrefetch = false,
            sequenceNumber = seq,
            onError = onError,
            onLoaded = onLoaded
        )

        signal.setOnCancelListener {
            executor.remove(task)
        }

        executor.execute(task)
        return signal
    }

    /**
     * Prefetches up to [items] just outside the viewport in the scroll direction.
     * A newer prefetch immediately cancels any pending older prefetch batch.
     */
    fun prefetch(
        context: Context,
        items: List<LocalMediaItem>,
        targetSizePx: Int
    ) {
        if (items.isEmpty()) return

        // A newer prefetch cancels the older prefetch batch
        currentPrefetchSignal?.cancel()
        val signal = CancellationSignal()
        currentPrefetchSignal = signal

        val appContext = context.applicationContext
        for (item in items) {
            if (signal.isCanceled) break
            val key = makeKey(item, targetSizePx)
            if (memoryCache.get(key) != null) continue

            val seq = sequenceGenerator.incrementAndGet()
            val task = ThumbnailTask(
                appContext = appContext,
                key = key,
                item = item,
                targetSizePx = targetSizePx,
                signal = signal,
                isPrefetch = true,
                sequenceNumber = seq,
                onError = null,
                onLoaded = null
            )

            signal.setOnCancelListener {
                executor.remove(task)
            }

            executor.execute(task)
        }
    }

    internal fun executeTask(task: ThumbnailTask) {
        if (task.signal.isCanceled) return

        Trace.beginSection(if (task.isPrefetch) "ThumbnailLoader.prefetch" else "ThumbnailLoader.decode")
        try {
            // Check memory cache once more (might have been populated while waiting in queue)
            val cached = memoryCache.get(task.key)
            if (cached != null && !cached.isRecycled) {
                if (!task.isPrefetch && task.onLoaded != null) {
                    mainHandler.post {
                        if (!task.signal.isCanceled) {
                            task.onLoaded.invoke(cached)
                        }
                    }
                }
                return
            }

            // Decode with single retry
            val bitmap = decodeWithRetry(task.appContext, task.item, task.targetSizePx, task.signal)

            if (task.signal.isCanceled) return

            if (bitmap != null) {
                memoryCache.put(task.key, bitmap)
                if (!task.isPrefetch && task.onLoaded != null) {
                    mainHandler.post {
                        if (!task.signal.isCanceled) {
                            task.onLoaded.invoke(bitmap)
                        }
                    }
                }
            } else {
                // Failed after retry
                if (!task.isPrefetch && task.onError != null) {
                    mainHandler.post {
                        if (!task.signal.isCanceled) {
                            task.onError.invoke()
                        }
                    }
                }
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun decodeWithRetry(
        context: Context,
        item: LocalMediaItem,
        targetSizePx: Int,
        signal: CancellationSignal
    ): Bitmap? {
        if (signal.isCanceled) return null

        // Attempt 1
        var bitmap = tryDecode(context, item, targetSizePx, signal)
        if (bitmap != null) return bitmap

        if (signal.isCanceled) return null

        // Attempt 2 (Retry once)
        bitmap = tryDecode(context, item, targetSizePx, signal)
        return bitmap
    }

    private fun tryDecode(
        context: Context,
        item: LocalMediaItem,
        targetSizePx: Int,
        signal: CancellationSignal
    ): Bitmap? {
        if (signal.isCanceled) return null
        return try {
            val size = Size(targetSizePx, targetSizePx)
            try {
                context.contentResolver.loadThumbnail(item.contentUri, size, signal)
            } catch (e: Exception) {
                if (signal.isCanceled || e is OperationCanceledException) throw e
                decodeFallback(context, item, targetSizePx, signal)
            }
        } catch (e: OperationCanceledException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeFallback(
        context: Context,
        item: LocalMediaItem,
        targetSizePx: Int,
        signal: CancellationSignal
    ): Bitmap? {
        if (signal.isCanceled) return null
        return try {
            val source = ImageDecoder.createSource {
                context.contentResolver.openAssetFileDescriptor(item.contentUri, "r", signal)
                    ?: throw java.io.FileNotFoundException("Cannot open AFD for ${item.contentUri}")
            }
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxDim = maxOf(info.size.width, info.size.height)
                if (maxDim > targetSizePx) {
                    val sample = (maxDim / targetSizePx).coerceAtLeast(1)
                    decoder.setTargetSampleSize(sample)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                memoryCache.evictAll()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                memoryCache.trimToSize(memoryCache.size() / 2)
            }
        }
    }

    fun clearCache() {
        memoryCache.evictAll()
    }
}

internal class ThumbnailTask(
    val appContext: Context,
    val key: ThumbnailKey,
    val item: LocalMediaItem,
    val targetSizePx: Int,
    val signal: CancellationSignal,
    val isPrefetch: Boolean,
    val sequenceNumber: Long,
    val onError: (() -> Unit)?,
    val onLoaded: ((Bitmap) -> Unit)?
) : Runnable, Comparable<ThumbnailTask> {

    override fun run() {
        ThumbnailLoader.executeTask(this)
    }

    override fun compareTo(other: ThumbnailTask): Int {
        // 1. Visible requests always take precedence over prefetch requests
        if (this.isPrefetch != other.isPrefetch) {
            return if (this.isPrefetch) 1 else -1
        }
        // 2. LIFO: Higher sequence number (newer request) comes first
        return other.sequenceNumber.compareTo(this.sequenceNumber)
    }
}
