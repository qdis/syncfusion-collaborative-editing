// ABOUTME: Request model for UI-initiated document save operations
// ABOUTME: Contains file UUID, SFDT content, and version for explicit saves from the client
package ai.apps.syncfusioncollaborativeediting.model

import java.util.UUID

data class UpdateDocumentRequest(
    var fileId: UUID,
    var sfdt: String = "",
    var latestAppliedVersion: Int = 0
)
