// ABOUTME: Request model for UI-initiated document save operations
// ABOUTME: Contains room name, SFDT content, and version for explicit saves from the client
package ai.apps.syncfusioncollaborativeediting.model

data class UpdateDocumentRequest(
    var roomName: String = "",
    var sfdt: String = "",
    var latestAppliedVersion: Int = 0
)
