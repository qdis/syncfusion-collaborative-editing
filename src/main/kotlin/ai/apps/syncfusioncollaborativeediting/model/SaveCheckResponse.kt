// ABOUTME: Response model for ShouldSave endpoint version checking.
// ABOUTME: Indicates whether document needs saving and current persisted version.
package ai.apps.syncfusioncollaborativeediting.model

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SaveCheckResponse(
    val shouldSave: Boolean,
    val currentPersistedVersion: Int? = null,
    val error: String? = null
)
