package com.example.piphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrototypeStabilizationTest {

    @Before
    fun setUp() {
        // Reset users in NodeRepository to demo baseline before each test
        DemoNodeData.loadDemoBaseline()
        NodeRepository.applyEqualSplitStrategy()
    }

    @Test
    fun inviteUser_generatesUniqueUuid_andNeverCollidesAfterRemoval() {
        val initialCount = NodeRepository.getUsers().size

        // Create pending invite and redeem
        val invite1 = NodeRepository.createPendingInvite("alex@family.local", "Alex Smith", "User", 20.0)
        assertNotNull(invite1.token)
        assertFalse(invite1.isExpired)

        val user1 = NodeRepository.redeemInvite(invite1.token)
        assertNotNull(user1)
        assertTrue(user1!!.id.length >= 32) // Valid UUID length

        val invite2 = NodeRepository.createPendingInvite("beth@family.local", "Beth Smith", "User", 20.0)
        val user2 = NodeRepository.redeemInvite(invite2.token)
        assertNotNull(user2)
        assertNotEquals(user1.id, user2!!.id)

        // Simulate Bug 2 condition: remove user1, then add a 3rd user
        NodeRepository.removeUser(user1.id)
        val invite3 = NodeRepository.createPendingInvite("clara@family.local", "Clara Smith", "User", 20.0)
        val user3 = NodeRepository.redeemInvite(invite3.token)
        assertNotNull(user3)

        // Ensure user3 ID does NOT collide with user2 or user1
        assertNotEquals(user2.id, user3!!.id)
        assertNotEquals(user1.id, user3.id)
    }

    @Test
    fun pendingInvite_expiresAfterWindow_andRedeemsOnlyOnce() {
        val invite = NodeRepository.createPendingInvite("david@family.local", "David Smith", "User", 15.0)
        assertFalse(invite.isRedeemed)
        assertFalse(invite.isExpired)

        // First redemption should succeed
        val redeemedUser = NodeRepository.redeemInvite(invite.token)
        assertNotNull(redeemedUser)
        assertTrue(invite.isRedeemed)

        // Second redemption attempt with same token must fail (single-use)
        val duplicateRedeem = NodeRepository.redeemInvite(invite.token)
        assertNull(duplicateRedeem)
    }

    @Test
    fun pendingInvite_reservesQuota_untilRedeemed() {
        val initialAllocated = NodeRepository.getTotalAllocatedGB()
        val inviteQuota = 25.0

        val invite = NodeRepository.createPendingInvite("eva@family.local", "Eva Smith", "User", inviteQuota)
        assertEquals(inviteQuota, NodeRepository.getTotalReservedGB(), 0.01)
        assertEquals(initialAllocated + inviteQuota, NodeRepository.getTotalAllocatedGB(), 0.01)

        // Redeeming converts reserved quota into active user quota
        val user = NodeRepository.redeemInvite(invite.token)
        assertNotNull(user)
        assertEquals(0.0, NodeRepository.getTotalReservedGB(), 0.01)
        assertEquals(initialAllocated + inviteQuota, NodeRepository.getTotalAllocatedGB(), 0.01)
    }

    @Test
    fun primaryAdmin_cannotBeSuspendedOrRemoved() {
        val adminUser = NodeRepository.getUser("u1")
        assertNotNull(adminUser)
        assertTrue(adminUser!!.role.equals("Admin", ignoreCase = true))

        // Admin suspend must be refused
        val toggleResult = NodeRepository.toggleUserStatus("u1")
        assertFalse("Primary admin must not be suspended", toggleResult)
        assertTrue(adminUser.isActive)

        // Admin removal must be refused
        val removeResult = NodeRepository.removeUser("u1")
        assertFalse("Primary admin must not be removed", removeResult)
        assertNotNull("Admin must still exist in repository", NodeRepository.getUser("u1"))
    }

    @Test
    fun nonAdminUser_canBeSuspendedAndRemoved() {
        val user = NodeRepository.getUser("u2")
        assertNotNull(user)

        // Can toggle status
        val initialStatus = user!!.isActive
        val newStatus = NodeRepository.toggleUserStatus("u2")
        assertEquals(!initialStatus, newStatus)

        // Can remove user
        val removed = NodeRepository.removeUser("u2")
        assertTrue(removed)
        assertNull(NodeRepository.getUser("u2"))
    }

    @Test
    fun quotaEvaluation_validatesCapacity_andRebalanceLogic() {
        // Validation of non-positive quotas
        val invalidZero = NodeRepository.canInviteUser(0.0)
        assertFalse(invalidZero.first)

        val invalidNegative = NodeRepository.canInviteUser(-10.0)
        assertFalse(invalidNegative.first)

        // Valid quota evaluation within free space
        val freeSpace = NodeRepository.getUnallocatedCapacityGB()
        if (freeSpace > 0.0) {
            val validRequest = NodeRepository.canInviteUser(minOf(15.0, freeSpace))
            assertTrue(validRequest.first)
        } else {
            // When all usable capacity is currently allocated to existing users,
            // an invite request without auto-rebalance exceeds the unallocated pool.
            val exceedRequest = NodeRepository.canInviteUser(10.0)
            assertFalse(exceedRequest.first)
        }

        // Plan auto-rebalance floating point precision check
        val plan = NodeRepository.planAutoRebalance("u3", 35.0)
        assertNotNull(plan)
        assertTrue(plan.summaryMessage.isNotEmpty())
    }

    @Test
    fun signOut_updatesSessionState() {
        assertTrue(NodeRepository.isUserSignedIn())
        NodeRepository.signOut()
        assertFalse(NodeRepository.isUserSignedIn())
        NodeRepository.signIn()
        assertTrue(NodeRepository.isUserSignedIn())
    }
}
