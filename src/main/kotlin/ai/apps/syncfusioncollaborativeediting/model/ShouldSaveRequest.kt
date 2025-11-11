// ABOUTME: Request model for checking if a document version should be saved
// ABOUTME: Lightweight pre-check before UI serializes SFDT and calls SaveDocument
package ai.apps.syncfusioncollaborativeediting.model

data class ShouldSaveRequest(
    var roomName: String = "",
    var latestAppliedVersion: Int = 0
)
