package com.example.piphotos

object DemoNodeData {

    const val DEMO_NODE_NAME = "pizerow-vault"
    const val DEMO_DEVICE_MODEL = "Raspberry Pi Zero 2 W"
    const val DEMO_CPU_SPEC = "Broadcom BCM2710A1 (Quad-Core Cortex-A53 @ 1.0 GHz)"
    const val DEMO_RAM_SPEC = "512 MB LPDDR2 (ZRAM pool 256 MB active)"
    const val DEMO_OS_SPEC = "Raspberry Pi OS Bookworm (64-bit Lite)"
    const val DEMO_STORAGE_DEVICE = "Kingston A400 SSD 250GB (Powered USB 2.0 OTG)"
    const val DEMO_TAILSCALE_DOMAIN = "pizerow-vault.ts.net"
    const val DEMO_REMOTE_ENDPOINT = "https://pizerow-vault.ts.net:8443"

    fun createDemoUsers(): List<NodeUser> = listOf(
        NodeUser(
            id = "u1",
            name = "Elena Rostova",
            username = "@elena",
            email = "elena.rostova@privacy.vault",
            initials = "ER",
            role = "Admin",
            quotaGB = 100.0,
            usedGB = 24.6,
            isActive = true,
            lastActive = "Active now",
            photosCount = 1204,
            videosCount = 38,
            vaultCount = 6
        ),
        NodeUser(
            id = "u2",
            name = "Marcus Chen",
            username = "@marcus",
            email = "marcus.chen@privacy.vault",
            initials = "MC",
            role = "User",
            quotaGB = 50.0,
            usedGB = 14.2,
            isActive = true,
            lastActive = "2 hours ago",
            photosCount = 682,
            videosCount = 14,
            vaultCount = 2
        ),
        NodeUser(
            id = "u3",
            name = "Sarah Lin",
            username = "@sarah",
            email = "sarah.lin@privacy.vault",
            initials = "SL",
            role = "User",
            quotaGB = 40.0,
            usedGB = 8.1,
            isActive = true,
            lastActive = "Yesterday",
            photosCount = 412,
            videosCount = 9,
            vaultCount = 0
        ),
        NodeUser(
            id = "u4",
            name = "David Miller",
            username = "@david",
            email = "david.miller@privacy.vault",
            initials = "DM",
            role = "User",
            quotaGB = 35.0,
            usedGB = 5.4,
            isActive = true,
            lastActive = "3 days ago",
            photosCount = 284,
            videosCount = 5,
            vaultCount = 1
        )
    )

    fun loadDemoBaseline() {
        NodeRepository.totalDiskGB = 250.0
        NodeRepository.systemReserveGB = 10.0
        NodeRepository.nodeName = DEMO_NODE_NAME
        NodeRepository.deviceModel = DEMO_DEVICE_MODEL
        NodeRepository.tailscaleDomain = DEMO_TAILSCALE_DOMAIN
        NodeRepository.remoteEndpoint = DEMO_REMOTE_ENDPOINT
        NodeRepository.clearPendingInvites()
        NodeRepository.setUsers(createDemoUsers())
    }
}

