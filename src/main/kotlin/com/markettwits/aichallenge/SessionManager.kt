package com.markettwits.aichallenge

import java.util.concurrent.ConcurrentHashMap

class SessionManager {
    private val sessions = ConcurrentHashMap<String, Agent>()

    fun getOrCreateSession(sessionId: String, clientFactory: () -> AnthropicClient): Agent {
        return sessions.getOrPut(sessionId) {
            Agent(clientFactory())
        }
    }

    fun getSession(sessionId: String): Agent? {
        return sessions[sessionId]
    }

    fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun getAllSessions(): Map<String, Agent> {
        return sessions.toMap()
    }
}
