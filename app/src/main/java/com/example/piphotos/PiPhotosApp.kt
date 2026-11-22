package com.example.piphotos

import android.app.Application
import android.os.StrictMode
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.example.piphotos.media.ThumbnailLoader
import com.example.piphotos.sync.MediaContentObserver
import com.example.piphotos.sync.MediaSyncWorker

class PiPhotosApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        // Improvement 4: MediaContentObserver registers for camera photo/video additions
        MediaContentObserver.register(this)
        MediaSyncWorker.scheduleMediaContentTrigger(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ThumbnailLoader.onTrimMemory(level)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
