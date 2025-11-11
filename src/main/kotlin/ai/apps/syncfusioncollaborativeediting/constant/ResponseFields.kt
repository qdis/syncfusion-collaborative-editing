// ABOUTME: Constants for JSON response field names and HTTP response messages
// ABOUTME: Centralizes all API response strings to ensure consistency across endpoints
package ai.apps.syncfusioncollaborativeediting.constant

object ResponseFields {
    // JSON field names
    const val VERSION = "version"
    const val ERROR = "error"
    const val OPERATIONS = "operations"
    const val RESYNC = "resync"
    const val WINDOW_START = "windowStart"
    const val SHOULD_SAVE = "shouldSave"
    const val CURRENT_PERSISTED_VERSION = "currentPersistedVersion"
    const val SUCCESS = "success"
    const val MESSAGE = "message"
    const val SKIPPED = "skipped"

    // Response messages
    const val RESYNC_REQUIRED_PREFIX = "RESYNC_REQUIRED: client at"
    const val SAVE_NOT_NEEDED = "No save needed - newer version already persisted"
    const val SAVE_SUCCESS = "Document saved successfully"
    const val SAVE_FAILED_PREFIX = "Failed to save document:"

    // Error messages
    const val IMPORT_FAILED = "import failed"
}
