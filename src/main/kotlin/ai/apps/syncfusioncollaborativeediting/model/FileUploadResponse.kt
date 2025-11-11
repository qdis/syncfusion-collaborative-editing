// ABOUTME: Response model for file upload endpoint.
// ABOUTME: Contains success message, uploaded file name, and generated UUID.
package ai.apps.syncfusioncollaborativeediting.model

import java.util.UUID

data class FileUploadResponse(
    val message: String,
    val fileName: String,
    val fileId: UUID
)
