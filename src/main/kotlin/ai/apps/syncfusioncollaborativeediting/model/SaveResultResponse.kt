// ABOUTME: Response model for SaveDocument endpoint operation results.
// ABOUTME: Contains success status, message, and optional skipped flag.
package ai.apps.syncfusioncollaborativeediting.model

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SaveResultResponse(
    val success: Boolean,
    val message: String,
    val skipped: Boolean? = null
)
