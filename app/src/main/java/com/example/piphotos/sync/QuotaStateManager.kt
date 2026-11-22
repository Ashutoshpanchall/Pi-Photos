package com.example.piphotos.sync

import com.example.piphotos.net.HttpException
import com.example.piphotos.net.MeResponse
import com.example.piphotos.net.ServerStatus
import java.util.Locale

object QuotaStateManager {

    fun computeStatus(usedBytes: Long, quotaBytes: Long): QuotaStatus {
        if (quotaBytes <= 0L) {
            return QuotaStatus.Unlimited
        }
        val pct = if (quotaBytes > 0) ((usedBytes.toDouble() / quotaBytes.toDouble()) * 100).toInt() else 0
        return when {
            pct >= 100 || usedBytes >= quotaBytes -> QuotaStatus.Full(pct, isHardLimit = true)
            pct >= 90 -> QuotaStatus.Warn90(pct)
            pct >= 80 -> QuotaStatus.Warn80(pct)
            else -> QuotaStatus.Ok(pct)
        }
    }

    fun updateFromStatus(
        status: ServerStatus,
        nowMs: Long = System.currentTimeMillis()
    ): QuotaState {
        val qStatus = computeStatus(status.userUsed, status.userQuota)
        val freeBytes = if (status.availableBytes > 0L) status.availableBytes else status.userFree
        return QuotaState(
            status = qStatus,
            usedBytes = status.userUsed,
            quotaBytes = status.userQuota,
            freeBytes = freeBytes,
            lastRefreshedAt = nowMs,
            isStale = false,
            errorMessage = null,
            availableBytes = freeBytes,
            allocationBytes = status.allocationBytes,
            allocationUsedBytes = status.allocationUsedBytes
        )
    }

    fun updateFromMe(
        me: MeResponse,
        nowMs: Long = System.currentTimeMillis()
    ): QuotaState {
        val freeBytes = if (me.availableBytes > 0L) {
            me.availableBytes
        } else if (me.quotaBytes > 0) {
            (me.quotaBytes - me.usedBytes).coerceAtLeast(0L)
        } else 0L
        val status = computeStatus(me.usedBytes, me.quotaBytes)
        return QuotaState(
            status = status,
            usedBytes = me.usedBytes,
            quotaBytes = me.quotaBytes,
            freeBytes = freeBytes,
            lastRefreshedAt = nowMs,
            isStale = false,
            errorMessage = null,
            availableBytes = freeBytes,
            allocationBytes = me.allocationBytes,
            allocationUsedBytes = me.allocationUsedBytes
        )
    }

    fun markError(
        previousState: QuotaState,
        error: Throwable,
        nowMs: Long = System.currentTimeMillis()
    ): QuotaState {
        if (error is HttpException) {
            when (error.code) {
                401 -> return previousState.copy(
                    status = QuotaStatus.DeviceRevoked,
                    errorMessage = "This device has been removed from the server.",
                    lastRefreshedAt = nowMs,
                    isStale = false
                )
                403 -> return previousState.copy(
                    status = QuotaStatus.AccountDisabled,
                    errorMessage = "Your account has been turned off by the vault owner.",
                    lastRefreshedAt = nowMs,
                    isStale = false
                )
                507 -> {
                    val pct = if (previousState.quotaBytes > 0) {
                        ((previousState.usedBytes.toDouble() / previousState.quotaBytes.toDouble()) * 100).toInt().coerceAtLeast(100)
                    } else 100
                    return previousState.copy(
                        status = QuotaStatus.Full(pct, isHardLimit = true),
                        errorMessage = "Storage is full. Backups are paused.",
                        freeBytes = 0L,
                        lastRefreshedAt = nowMs,
                        isStale = false
                    )
                }
            }
        }
        // Unreachable / network disconnect: retain last state, mark stale
        return previousState.copy(
            isStale = true,
            errorMessage = error.localizedMessage ?: "Server unreachable"
        )
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) {
            return String.format(Locale.US, "%.1f GB", gb)
        }
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        if (mb >= 1.0) {
            return String.format(Locale.US, "%.1f MB", mb)
        }
        val kb = bytes.toDouble() / 1024.0
        if (kb >= 1.0) {
            return String.format(Locale.US, "%.1f KB", kb)
        }
        return "$bytes B"
    }

    fun formatUsageSummary(state: QuotaState): String {
        val usedStr = formatBytes(state.usedBytes)
        return if (state.quotaBytes <= 0L) {
            if (state.availableBytes > 0L) {
                "$usedStr used · ${formatBytes(state.availableBytes)} available"
            } else {
                "$usedStr used"
            }
        } else {
            val quotaStr = formatBytes(state.quotaBytes)
            "$usedStr of $quotaStr used"
        }
    }

    fun formatFreeSubtitle(state: QuotaState): String {
        return if (state.quotaBytes <= 0L) {
            if (state.availableBytes > 0L) {
                "${formatBytes(state.availableBytes)} available"
            } else {
                "Unlimited storage"
            }
        } else {
            val freeBytes = if (state.availableBytes > 0L) state.availableBytes else state.freeBytes
            val freeStr = formatBytes(freeBytes)
            if (state.availableBytes > 0L) "$freeStr available" else "$freeStr free"
        }
    }

    fun formatShareMessage(
        state: QuotaState,
        serverName: String,
        displayName: String
    ): String {
        val usedStr = formatBytes(state.usedBytes)
        val quotaStr = formatBytes(state.quotaBytes)
        val pct = when (val s = state.status) {
            is QuotaStatus.Warn80 -> s.usagePercent
            is QuotaStatus.Warn90 -> s.usagePercent
            is QuotaStatus.Full -> s.usagePercent
            is QuotaStatus.Ok -> s.usagePercent
            is QuotaStatus.Unlimited -> 0
            else -> 0
        }
        val sName = if (serverName.isBlank()) "our PiPhotos vault" else serverName
        val dName = if (displayName.isBlank()) "My" else "$displayName's"
        return "Hi! $dName PiPhotos backup has reached $pct% of allocated storage ($usedStr / $quotaStr) on $sName. Could you please increase my storage quota?"
    }

    fun formatLastUpdated(lastRefreshedAt: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (lastRefreshedAt <= 0L) return "Never updated"
        val elapsedSec = (nowMs - lastRefreshedAt) / 1000
        return when {
            elapsedSec < 60 -> "Updated just now"
            elapsedSec < 3600 -> "Updated ${elapsedSec / 60}m ago"
            elapsedSec < 86400 -> "Updated ${elapsedSec / 3600}h ago"
            else -> "Updated ${elapsedSec / 86400}d ago"
        }
    }
}
