package com.example.piphotos.media

import androidx.fragment.app.FragmentManager

object DebugStatsLauncherImpl : DebugStatsLauncher {
    override fun show(fragmentManager: FragmentManager) {
        IndexStatsBottomSheet.newInstance().show(fragmentManager, IndexStatsBottomSheet.TAG)
    }
}

