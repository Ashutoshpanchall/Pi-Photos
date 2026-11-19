package com.example.piphotos.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Prepares the runtime permission handling for ACCESS_LOCAL_NETWORK introduced in upcoming
 * Android releases (API 37 / Android 16).
 *
 * Kept disabled / no-op on targetSdk 36, ready for seamless activation when targetSdk is upgraded.
 */
object LocalNetworkPermissionHelper {

    // Permission string constant for upcoming Android API 37
    const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /**
     * Checks whether local network permission is required by the running OS and target SDK.
     */
    fun isPermissionRequired(): Boolean {
        // Only active when running on API 37+ and compiled for targetSdk 37+
        return Build.VERSION.SDK_INT >= 37
    }

    /**
     * Checks if the app currently holds the local network permission.
     */
    fun hasPermission(context: Context): Boolean {
        if (!isPermissionRequired()) return true
        return ContextCompat.checkSelfPermission(
            context,
            PERMISSION_ACCESS_LOCAL_NETWORK
        ) == PackageManager.PERMISSION_GRANTED
    }
}
