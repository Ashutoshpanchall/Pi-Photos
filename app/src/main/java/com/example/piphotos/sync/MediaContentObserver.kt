package com.example.piphotos.sync

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log

/**
 * MediaContentObserver listens for MediaStore additions (camera photos & videos)
 * and opportunistically wakes MediaSyncWorker after a debounce delay (Improvement 4).
 *
 * Runs entirely on a background HandlerThread to satisfy the 8.3ms frame budget:
 * zero disk/network IO or WorkManager enqueueing on the main thread.
 */
class MediaContentObserver(
    private val context: Context,
    private val handler: Handler
) : ContentObserver(handler) {

    private var pendingSyncRunnable: Runnable? = null

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        scheduleDebouncedSync()
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        scheduleDebouncedSync()
    }

    private fun scheduleDebouncedSync() {
        // Runs on background HandlerThread, off the main looper
        val paired = SyncNodePreferences.getPairedNode(context) ?: return

        pendingSyncRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            Log.d(TAG, "New photo/video detected via MediaStore observer. Enqueuing opportunistic sync.")
            MediaSyncWorker.enqueueImmediateSync(context, force = false)
            MediaSyncWorker.scheduleMediaContentTrigger(context)
        }
        pendingSyncRunnable = runnable
        // 5-second debounce gives camera app time to finish writing EXIF and closing media file
        handler.postDelayed(runnable, DEBOUNCE_DELAY_MS)
    }

    fun cancelPending() {
        pendingSyncRunnable?.let { handler.removeCallbacks(it) }
        pendingSyncRunnable = null
    }

    companion object {
        private const val TAG = "MediaContentObserver"
        private const val DEBOUNCE_DELAY_MS = 5000L

        @Volatile
        private var instance: MediaContentObserver? = null

        @Volatile
        private var backgroundThread: HandlerThread? = null

        fun register(context: Context) {
            if (instance != null) return
            synchronized(this) {
                if (instance != null) return
                val appContext = context.applicationContext
                val thread = HandlerThread("MediaContentObserverThread").apply { start() }
                backgroundThread = thread
                val handler = Handler(thread.looper)
                val observer = MediaContentObserver(appContext, handler)
                try {
                    val resolver = appContext.contentResolver
                    resolver.registerContentObserver(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        true,
                        observer
                    )
                    resolver.registerContentObserver(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        true,
                        observer
                    )
                    instance = observer
                    Log.i(TAG, "Registered MediaContentObserver on background HandlerThread")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not register MediaContentObserver: ${e.message}")
                    thread.quitSafely()
                    backgroundThread = null
                }
            }
        }

        fun unregister(context: Context) {
            synchronized(this) {
                instance?.let { observer ->
                    observer.cancelPending()
                    try {
                        context.applicationContext.contentResolver.unregisterContentObserver(observer)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error unregistering MediaContentObserver: ${e.message}")
                    }
                    instance = null
                }
                backgroundThread?.let { thread ->
                    thread.quitSafely()
                    backgroundThread = null
                }
            }
        }
    }
}
