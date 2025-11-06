// ABOUTME: Data class for background save operations queue
// ABOUTME: Tracks document operations that need to be persisted to storage
package ai.apps.syncfusioncollaborativeediting.model

import com.syncfusion.ej2.wordprocessor.ActionInfo

data class SaveInfo(
    var roomName: String = "",
    var actions: List<ActionInfo>? = null,
    var userId: String = "",
    var version: Int = 0,
    var partialSave: Boolean = false
)
