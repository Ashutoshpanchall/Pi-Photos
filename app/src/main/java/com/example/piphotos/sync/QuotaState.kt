package com.example.piphotos.sync

sealed interface QuotaStatus {
    object Unlimited : QuotaStatus
    data class Ok(val usagePercent: Int) : QuotaStatus
    data class Warn80(val usagePercent: Int) : QuotaStatus
    data class Warn90(val usagePercent: Int) : QuotaStatus
    data class Full(val usagePercent: Int, val isHardLimit: Boolean) : QuotaStatus
    object AccountDisabled : QuotaStatus
    object DeviceRevoked : QuotaStatus
}

data class QuotaState(
    val status: QuotaStatus = QuotaStatus.Unlimited,
    val usedBytes: Long = 0L,
    val quotaBytes: Long = 0L, // 0 = unlimited
    val freeBytes: Long = 0L,
    val lastRefreshedAt: Long = 0L,
    val isStale: Boolean = false,
    val errorMessage: String? = null,
    val availableBytes: Long = 0L,
    val allocationBytes: Long = 0L,
    val allocationUsedBytes: Long = 0L
) {
    val isFullOrExceeded: Boolean get() = status is QuotaStatus.Full
    val isBlocked: Boolean get() = status is QuotaStatus.Full || status is QuotaStatus.AccountDisabled || status is QuotaStatus.DeviceRevoked
}
