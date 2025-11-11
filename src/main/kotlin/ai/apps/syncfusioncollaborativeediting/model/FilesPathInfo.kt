// ABOUTME: Request model for document import operations
// ABOUTME: Contains file UUID identifier for collaborative editing sessions
package ai.apps.syncfusioncollaborativeediting.model

import java.util.UUID

data class FilesPathInfo(
    var fileId: UUID
)
