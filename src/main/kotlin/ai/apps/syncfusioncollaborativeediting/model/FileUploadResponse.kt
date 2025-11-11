// ABOUTME: Response model for file upload endpoint.
// ABOUTME: Contains success message and uploaded file name.
package ai.apps.syncfusioncollaborativeediting.model

data class FileUploadResponse(
    val message: String,
    val fileName: String
)
