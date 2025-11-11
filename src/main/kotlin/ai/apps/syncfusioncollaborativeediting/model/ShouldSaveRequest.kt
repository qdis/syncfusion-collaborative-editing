// ABOUTME: Request model for checking if a document version should be saved
// ABOUTME: Lightweight pre-check before UI serializes SFDT and calls SaveDocument
package ai.apps.syncfusioncollaborativeediting.model

import java.util.UUID

data class ShouldSaveRequest(
    var fileId: UUID,
    var latestAppliedVersion: Int = 0
)
