package com.example.piphotos

data class NodeUser(
    val id: String,
    var name: String,
    val username: String,
    val email: String,
    val initials: String,
    var role: String, // "Admin" or "User"
    var quotaGB: Double,
    var usedGB: Double,
    var isActive: Boolean = true,
    var lastActive: String = "Online now",
    var photosCount: Int = 0,
    var videosCount: Int = 38,
    var vaultCount: Int = 6
)

data class StoragePreset(
    val id: String,
    val title: String,
    val quotaGB: Double,
    val subtitle: String
)

data class PendingInvite(
    val id: String = java.util.UUID.randomUUID().toString(),
    val email: String,
    val displayName: String,
    val role: String,
    val quotaGB: Double,
    val token: String = java.util.UUID.randomUUID().toString().substring(0, 8),
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 48 * 3600 * 1000L, // 48-hour expiration
    var isRedeemed: Boolean = false
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt
}

data class RebalancePlan(
    val isFeasible: Boolean,
    val targetUserId: String,
    val requestedQuotaGB: Double,
    val unallocatedCoveredGB: Double,
    val cutoffRequiredGB: Double,
    val userCutoffs: Map<String, Double>, // otherUserId -> amount shaved off from unused buffer
    val newUserQuotas: Map<String, Double>, // userId -> new quota
    val isOverAllocated: Boolean,
    val overAllocatedByGB: Double,
    val summaryMessage: String
)

object NodeRepository {

    // Configurable Node Metadata (defaults empty in local-only mode)
    var nodeName: String = ""
    var deviceModel: String = ""
    var remoteNodeDomain: String = ""
    var remoteEndpoint: String = ""
    var cpuSpec: String = ""
    var ramSpec: String = ""
    var osSpec: String = ""
    var storagePath: String = ""

    // Backward compatibility getters for existing screens before G7
    val NODE_NAME: String get() = nodeName.ifEmpty { "Vault Node" }
    val DEVICE_MODEL: String get() = deviceModel.ifEmpty { "Storage Node" }
    val REMOTE_DOMAIN: String get() = remoteNodeDomain.ifEmpty { "vault.local" }
    val REMOTE_ENDPOINT: String get() = remoteEndpoint.ifEmpty { "https://vault.local" }

    // Storage Sizing (Dynamic: Defaults to 0 GB in local-only mode)
    var totalDiskGB: Double = 0.0
    var systemReserveGB: Double = 0.0
    val usableDiskGB: Double get() = maxOf(0.0, totalDiskGB - systemReserveGB)

    // Dynamic User List (Empty by default in local-only mode)
    private val users = mutableListOf<NodeUser>()

    fun setUsers(newUsers: List<NodeUser>) {
        users.clear()
        users.addAll(newUsers)
    }

    fun clearUsers() {
        users.clear()
    }

    fun getUsers(): List<NodeUser> = users

    fun getUser(id: String): NodeUser? = users.find { it.id == id }

    fun getTotalReservedGB(): Double = getPendingInvites().sumOf { it.quotaGB }

    fun getTotalAllocatedGB(): Double = users.sumOf { it.quotaGB } + getTotalReservedGB()

    fun getTotalUsedGB(): Double = users.sumOf { it.usedGB }

    fun getUnallocatedCapacityGB(): Double {
        val unallocated = usableDiskGB - getTotalAllocatedGB()
        return if (unallocated > 0) unallocated else 0.0
    }

    fun getFreePhysicalDiskGB(): Double = maxOf(0.0, usableDiskGB - getTotalUsedGB())

    fun getOverAllocatedGB(): Double = maxOf(0.0, getTotalAllocatedGB() - usableDiskGB)

    fun isOverAllocated(): Boolean = getTotalAllocatedGB() > usableDiskGB

    fun getOverAllocatedPercent(): Int {
        return if (usableDiskGB > 0) ((getTotalAllocatedGB() / usableDiskGB) * 100).toInt() else 0
    }

    /**
     * Dynamically generates intelligent storage presets for modifying an existing user's quota.
     * Computes optimal targets based on N users and total storage size.
     */
    fun getDynamicPresetsForUser(userId: String): List<StoragePreset> {
        val user = getUser(userId) ?: return emptyList()
        val n = maxOf(1, users.size)
        val equalSplit = Math.round((usableDiskGB / n) * 10.0) / 10.0
        val fairShare = Math.round((user.usedGB + (getFreePhysicalDiskGB() / n)) * 10.0) / 10.0
        val maxSafe = Math.round((user.quotaGB + getUnallocatedCapacityGB()) * 10.0) / 10.0
        val tierShare = if (user.role.equals("Admin", ignoreCase = true)) {
            Math.round((usableDiskGB * 0.40) * 10.0) / 10.0
        } else {
            Math.round(((usableDiskGB * 0.60) / maxOf(1, n - 1)) * 10.0) / 10.0
        }

        return listOf(
            StoragePreset("equal", "Equal (${equalSplit.toInt()}G)", equalSplit, "Equal 1/$n of storage pool"),
            StoragePreset("fair", "Fair (${fairShare.toInt()}G)", fairShare, "Usage + proportional free pool"),
            StoragePreset("tier", if (user.role.equals("Admin", true)) "Admin Tier" else "Member Tier", tierShare, "Role weighted headroom"),
            StoragePreset("max", "Max Free", maxSafe, "All unallocated capacity")
        )
    }

    /**
     * Dynamically generates intelligent storage presets for inviting a new user (N+1 users).
     */
    fun getDynamicPresetsForNewUser(): List<StoragePreset> {
        val newCount = users.size + 1
        val equalShare = Math.round((usableDiskGB / newCount) * 10.0) / 10.0
        val unallocated = Math.round(getUnallocatedCapacityGB() * 10.0) / 10.0
        val starter = 15.0
        val balanced = Math.min(equalShare, maxOf(10.0, unallocated / 2))

        return listOf(
            StoragePreset("p1", "Starter (15G)", starter, "Light snapshot backup"),
            StoragePreset("p2", "Balanced (${balanced.toInt()}G)", balanced, "Recommended headroom"),
            StoragePreset("p3", "Equal (${equalShare.toInt()}G)", equalShare, "Equal 1/$newCount share"),
            StoragePreset("p4", "Max Free", maxOf(5.0, unallocated), "Available unallocated")
        )
    }

    /**
     * Dynamic Auto-Rebalance and Cutoff Engine:
     * Calculates whether an increase can be satisfied by unallocated pool, or by proportionally
     * shaving unused buffer from other N-1 users without impacting existing files.
     */
    fun planAutoRebalance(targetUserId: String, requestedQuotaGB: Double): RebalancePlan {
        val target = getUser(targetUserId) ?: return RebalancePlan(
            isFeasible = false,
            targetUserId = targetUserId,
            requestedQuotaGB = requestedQuotaGB,
            unallocatedCoveredGB = 0.0,
            cutoffRequiredGB = 0.0,
            userCutoffs = emptyMap(),
            newUserQuotas = emptyMap(),
            isOverAllocated = false,
            overAllocatedByGB = 0.0,
            summaryMessage = "User not found."
        )

        if (requestedQuotaGB < target.usedGB) {
            return RebalancePlan(
                isFeasible = false,
                targetUserId = targetUserId,
                requestedQuotaGB = requestedQuotaGB,
                unallocatedCoveredGB = 0.0,
                cutoffRequiredGB = 0.0,
                userCutoffs = emptyMap(),
                newUserQuotas = emptyMap(),
                isOverAllocated = false,
                overAllocatedByGB = 0.0,
                summaryMessage = "Cannot reduce quota below existing usage (${String.format("%.1f", target.usedGB)} GB used)."
            )
        }

        val delta = requestedQuotaGB - target.quotaGB
        val unallocated = getUnallocatedCapacityGB()

        // Case A: Fully covered by unallocated space
        if (delta <= unallocated) {
            val newQuotas = users.associate { it.id to (if (it.id == targetUserId) requestedQuotaGB else it.quotaGB) }
            val remainingUnallocated = maxOf(0.0, unallocated - maxOf(0.0, delta))
            return RebalancePlan(
                isFeasible = true,
                targetUserId = targetUserId,
                requestedQuotaGB = requestedQuotaGB,
                unallocatedCoveredGB = maxOf(0.0, delta),
                cutoffRequiredGB = 0.0,
                userCutoffs = emptyMap(),
                newUserQuotas = newQuotas,
                isOverAllocated = false,
                overAllocatedByGB = 0.0,
                summaryMessage = "Fully covered by unallocated node capacity (${String.format("%.1f", remainingUnallocated)} GB free pool remaining)."
            )
        }

        // Case B: Needs cutoff from other users' unused buffers
        val excess = delta - unallocated
        val otherUsers = users.filter { it.id != targetUserId }
        val unusedBuffers = otherUsers.associate { it.id to maxOf(0.0, it.quotaGB - it.usedGB) }
        val totalUnusedAcrossOthers = unusedBuffers.values.sum()

        if (totalUnusedAcrossOthers >= excess) {
            val cutoffs = mutableMapOf<String, Double>()
            val newQuotas = mutableMapOf<String, Double>()
            newQuotas[targetUserId] = requestedQuotaGB

            for (other in otherUsers) {
                val userUnused = unusedBuffers[other.id] ?: 0.0
                val cut = if (totalUnusedAcrossOthers > 0) {
                    excess * (userUnused / totalUnusedAcrossOthers)
                } else 0.0
                val roundedCut = Math.round(cut * 10.0) / 10.0
                cutoffs[other.id] = roundedCut
                newQuotas[other.id] = Math.max(other.usedGB, Math.round((other.quotaGB - roundedCut) * 10.0) / 10.0)
            }

            val userDeltasStr = otherUsers.filter { (cutoffs[it.id] ?: 0.0) > 0 }
                .joinToString(", ") { "${it.name.substringBefore(" ")} (-${String.format("%.1f", cutoffs[it.id])} GB)" }

            return RebalancePlan(
                isFeasible = true,
                targetUserId = targetUserId,
                requestedQuotaGB = requestedQuotaGB,
                unallocatedCoveredGB = unallocated,
                cutoffRequiredGB = excess,
                userCutoffs = cutoffs,
                newUserQuotas = newQuotas,
                isOverAllocated = false,
                overAllocatedByGB = 0.0,
                summaryMessage = "Rebalanced from unused headroom: $userDeltasStr. Stored files remain untouched."
            )
        } else {
            // Case C: Exceeds total storage capacity even after taking all other unused buffers (Over-commit)
            val overCommit = excess - totalUnusedAcrossOthers
            val cutoffs = mutableMapOf<String, Double>()
            val newQuotas = mutableMapOf<String, Double>()
            newQuotas[targetUserId] = requestedQuotaGB

            for (other in otherUsers) {
                val cut = unusedBuffers[other.id] ?: 0.0
                cutoffs[other.id] = Math.round(cut * 10.0) / 10.0
                newQuotas[other.id] = other.usedGB
            }

            return RebalancePlan(
                isFeasible = true,
                targetUserId = targetUserId,
                requestedQuotaGB = requestedQuotaGB,
                unallocatedCoveredGB = unallocated,
                cutoffRequiredGB = totalUnusedAcrossOthers,
                userCutoffs = cutoffs,
                newUserQuotas = newQuotas,
                isOverAllocated = true,
                overAllocatedByGB = overCommit,
                summaryMessage = "⚠️ Over-allocates node by ${String.format("%.1f", overCommit)} GB. Storage writes will be soft-capped if users upload concurrently."
            )
        }
    }

    /**
     * Applies the atomic rebalance across all affected users.
     */
    fun applyRebalancePlan(plan: RebalancePlan): Boolean {
        if (!plan.isFeasible) return false
        for ((userId, newQuota) in plan.newUserQuotas) {
            val u = getUser(userId)
            if (u != null) {
                u.quotaGB = Math.round(newQuota * 10.0) / 10.0
            }
        }
        return true
    }

    /**
     * 1-Click Global Node Strategy: Equal Split across N users
     */
    fun applyEqualSplitStrategy() {
        val n = maxOf(1, users.size)
        val perUser = Math.round((usableDiskGB / n) * 10.0) / 10.0
        val deficitUsers = users.filter { it.usedGB > perUser }

        if (deficitUsers.isEmpty()) {
            users.forEach { it.quotaGB = perUser }
        } else {
            val deficitUsed = deficitUsers.sumOf { it.usedGB }
            val remainingPool = maxOf(0.0, usableDiskGB - deficitUsed)
            val regularUsers = users.filter { it.usedGB <= perUser }
            val regularPerUser = if (regularUsers.isNotEmpty()) {
                Math.round((remainingPool / regularUsers.size) * 10.0) / 10.0
            } else perUser

            users.forEach {
                it.quotaGB = if (it.usedGB > perUser) it.usedGB else regularPerUser
            }
        }
    }

    /**
     * 1-Click Global Node Strategy: Admin Tiered Allocation across N users
     */
    fun applyAdminTierStrategy() {
        val n = users.size
        if (n <= 1) {
            users.firstOrNull()?.quotaGB = usableDiskGB
            return
        }
        val admin = users.find { it.role.equals("Admin", true) } ?: users.first()
        val adminQuota = Math.max(admin.usedGB, Math.round((usableDiskGB * 0.40) * 10.0) / 10.0)
        admin.quotaGB = adminQuota

        val remainingPool = maxOf(0.0, usableDiskGB - adminQuota)
        val regularUsers = users.filter { it.id != admin.id }
        val perRegular = Math.round((remainingPool / regularUsers.size) * 10.0) / 10.0

        regularUsers.forEach {
            it.quotaGB = Math.max(it.usedGB, perRegular)
        }
    }

    /**
     * 1-Click Global Node Strategy: Usage Fair-Share across N users
     */
    fun applyFairShareStrategy() {
        val totalUsed = getTotalUsedGB()
        val freePhysical = Math.max(0.0, usableDiskGB - totalUsed)
        val n = maxOf(1, users.size)
        val headroom = Math.round((freePhysical / n) * 10.0) / 10.0

        users.forEach {
            it.quotaGB = Math.round((it.usedGB + headroom) * 10.0) / 10.0
        }
    }

    fun canModifyQuota(userId: String, newQuotaGB: Double): Pair<Boolean, String> {
        val plan = planAutoRebalance(userId, newQuotaGB)
        if (!plan.isFeasible) {
            return Pair(false, plan.summaryMessage)
        }
        return Pair(true, plan.summaryMessage)
    }

    fun modifyQuota(userId: String, newQuotaGB: Double, allowAutoRebalance: Boolean = true): Boolean {
        val plan = planAutoRebalance(userId, newQuotaGB)
        if (!plan.isFeasible) return false
        if (plan.cutoffRequiredGB > 0 && !allowAutoRebalance) {
            return false
        }
        return applyRebalancePlan(plan)
    }

    fun toggleUserStatus(userId: String): Boolean {
        val user = getUser(userId) ?: return false
        // Primary node admin can never be disabled
        if (user.role.equals("Admin", ignoreCase = true) || user.id == "u1") {
            return false
        }
        user.isActive = !user.isActive
        return user.isActive
    }

    fun removeUser(userId: String): Boolean {
        val user = getUser(userId) ?: return false
        // Primary node admin cannot be removed from the node
        if (user.role.equals("Admin", ignoreCase = true) || user.id == "u1") {
            return false
        }
        return users.removeAll { it.id == userId }
    }

    private val pendingInvites = mutableListOf<PendingInvite>()
    private var isNodeSignedIn: Boolean = true

    fun isUserSignedIn(): Boolean = isNodeSignedIn

    fun signOut() {
        isNodeSignedIn = false
    }

    fun signIn() {
        isNodeSignedIn = true
    }

    fun getPendingInvites(): List<PendingInvite> = pendingInvites.filter { !it.isRedeemed && !it.isExpired }

    fun clearPendingInvites() {
        pendingInvites.clear()
    }

    fun canInviteUser(quotaGB: Double): Pair<Boolean, String> {
        if (quotaGB <= 0) return Pair(false, "Quota must be greater than 0 GB.")
        val unallocated = getUnallocatedCapacityGB()
        if (quotaGB > unallocated) {
            return Pair(
                false,
                "Requested quota of ${String.format("%.1f", quotaGB)} GB exceeds remaining unallocated space (${String.format("%.1f", unallocated)} GB free pool)."
            )
        }
        return Pair(true, "Valid")
    }

    fun createPendingInvite(email: String, displayName: String, role: String, quotaGB: Double): PendingInvite {
        val invite = PendingInvite(
            email = email,
            displayName = displayName.ifEmpty { email.substringBefore("@") },
            role = role,
            quotaGB = Math.round(quotaGB * 10.0) / 10.0
        )
        pendingInvites.add(invite)
        return invite
    }

    fun redeemInvite(token: String): NodeUser? {
        val invite = pendingInvites.firstOrNull { it.token == token && !it.isRedeemed && !it.isExpired } ?: return null
        invite.isRedeemed = true

        val id = java.util.UUID.randomUUID().toString()
        val initials = invite.displayName.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercase() }
            .joinToString("")
            .ifEmpty { "NU" }

        val username = "@" + invite.email.substringBefore("@").lowercase()
        val newUser = NodeUser(
            id = id,
            name = invite.displayName,
            username = username,
            email = invite.email,
            initials = initials,
            role = invite.role,
            quotaGB = invite.quotaGB,
            usedGB = 0.0,
            isActive = true,
            lastActive = "Online now",
            photosCount = 0,
            videosCount = 0,
            vaultCount = 0
        )
        users.add(newUser)
        return newUser
    }

    fun inviteUser(email: String, displayName: String, role: String, quotaGB: Double): NodeUser {
        val invite = createPendingInvite(email, displayName, role, quotaGB)
        return redeemInvite(invite.token)!!
    }
}
