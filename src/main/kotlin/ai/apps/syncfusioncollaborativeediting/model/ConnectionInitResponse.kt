// ABOUTME: Response model for WebSocket connection initialization.
// ABOUTME: Contains connection ID and list of current room users.
package ai.apps.syncfusioncollaborativeediting.model

data class ConnectionInitResponse(
    val connectionId: String,
    val users: List<UserSessionInfo>
)
