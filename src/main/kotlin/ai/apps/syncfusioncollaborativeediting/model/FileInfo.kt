// ABOUTME: Response DTO for file listing
// ABOUTME: Contains UUID identifier and display fileName
package ai.apps.syncfusioncollaborativeediting.model

import java.util.UUID

data class FileInfo(
    val fileId: UUID,
    val fileName: String
)
