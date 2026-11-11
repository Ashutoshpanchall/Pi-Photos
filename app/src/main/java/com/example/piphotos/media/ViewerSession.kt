package com.example.piphotos.media

import java.util.UUID

object ViewerSession {

    private val sessions = mutableMapOf<String, List<LocalMediaItem>>()

    fun createSession(items: List<LocalMediaItem>): String {
        val id = UUID.randomUUID().toString()
        sessions[id] = items
        return id
    }

    fun getSession(id: String?): List<LocalMediaItem>? {
        if (id == null) return null
        return sessions[id]
    }

    fun clearSession(id: String?) {
        if (id != null) sessions.remove(id)
    }

    fun resolveStartingPosition(items: List<LocalMediaItem>, startingId: Long): Int {
        val index = items.indexOfFirst { it.id == startingId }
        return if (index >= 0) index else 0
    }
}

