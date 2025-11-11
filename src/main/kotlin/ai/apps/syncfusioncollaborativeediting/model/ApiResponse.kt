// ABOUTME: Generic API response wrapper for simple success/error responses.
// ABOUTME: Provides data field for success cases and error field for failures.
package ai.apps.syncfusioncollaborativeediting.model

data class ApiResponse<T>(
    val data: T? = null,
    val error: String? = null,
    val success: Boolean = error == null
)
