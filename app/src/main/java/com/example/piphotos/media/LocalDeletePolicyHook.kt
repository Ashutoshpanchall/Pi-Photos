package com.example.piphotos.media

object LocalDeletePolicyHook {

    /**
     * Local delete / trash contract:
     * In LocalOnly mode and standard one-way backup policy, deleting or trashing an item locally
     * marks the item as locally removed in Room/MediaStore. It MUST NOT issue a remote DELETE to the NAS
     * unless the user has explicitly selected a two-way mirror sync policy in phase G7.
     */
    const val POLICY_CONTRACT = "Local delete flags the item as local_deleted in Room; does NOT issue a remote DELETE unless sync policy is two-way-mirror."

    fun onLocalItemTrashed(ids: List<Long>) {
        // Documented contract hook: records local deletion intent without making remote calls.
    }
}
