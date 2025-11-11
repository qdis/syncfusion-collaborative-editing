// ABOUTME: Response model for GetActionsFromServer endpoint synchronization.
// ABOUTME: Contains operations array, resync flag, and optional window start version.
package ai.apps.syncfusioncollaborativeediting.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.syncfusion.ej2.wordprocessor.ActionInfo

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OperationsSyncResponse(
    val operations: List<ActionInfo>,
    val resync: Boolean,
    val windowStart: Int? = null
)
