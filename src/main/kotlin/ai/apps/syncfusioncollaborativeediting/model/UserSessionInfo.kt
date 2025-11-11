// ABOUTME: Data class representing user session information in a collaborative editing room
// ABOUTME: Tracks connection details and activity timestamps for each connected user
package ai.apps.syncfusioncollaborativeediting.model

import java.time.Instant

data class UserSessionInfo(
    val userName: String? = null,
    val userId: String,
    val sessionId: String,
    val lastHeartbeat: Instant? = null,
    val lastAction: Instant? = null,
    val lastSave: Instant? = null
)
