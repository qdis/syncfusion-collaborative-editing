// ABOUTME: Request model for document import operations
// ABOUTME: Contains file name and room name for collaborative editing sessions
package ai.apps.syncfusioncollaborativeediting.model

data class FilesPathInfo(
    var fileName: String = "",
    var roomName: String = ""
)
