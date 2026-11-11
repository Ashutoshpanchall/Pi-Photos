package com.example.piphotos

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class VideoPlayerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Mountain Lake Reflection"
        val meta = intent.getStringExtra(EXTRA_VIDEO_META) ?: "1080p 60fps · Video"
        start(this, title, meta)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_VIDEO_META = "extra_video_meta"

        fun start(context: Context, title: String, meta: String) {
            val videoItem = MediaRepository.getMediaForCategory("videos").firstOrNull { it.title.contains(title, ignoreCase = true) }
                ?: MediaRepository.getMediaForCategory("recents").firstOrNull { it.isVideo }
            val videoId = videoItem?.id ?: "3"
            val res = videoItem?.drawableRes ?: 0
            val date = videoItem?.date ?: "Today · 10:42 AM"

            PhotoViewerActivity.start(
                context = context,
                photoId = videoId,
                drawableRes = res,
                title = title,
                date = date,
                category = "Recents",
                isVideo = true
            )
        }
    }
}
