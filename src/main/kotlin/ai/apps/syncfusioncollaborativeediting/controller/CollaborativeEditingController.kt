// ABOUTME: REST controller for collaborative editing HTTP endpoints
// ABOUTME: Thin layer that delegates business logic to CollaborativeEditingService
package ai.apps.syncfusioncollaborativeediting.controller

import ai.apps.syncfusioncollaborativeediting.constant.ResponseFields
import ai.apps.syncfusioncollaborativeediting.model.*
import ai.apps.syncfusioncollaborativeediting.service.CollaborativeEditingService
import ai.apps.syncfusioncollaborativeediting.service.StaleClientException
import com.fasterxml.jackson.databind.ObjectMapper
import com.syncfusion.ej2.wordprocessor.ActionInfo
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.messaging.MessageHeaders
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.Principal

@RestController
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
class CollaborativeEditingController(
    private val collaborativeEditingService: CollaborativeEditingService,
    private val objectMapper: ObjectMapper,
    private val documentEditorHub: DocumentEditorHub
) {

    private val logger = LoggerFactory.getLogger(CollaborativeEditingController::class.java)

    @PostMapping("/api/collaborativeediting/ImportFile")
    fun importFile(@RequestBody file: FilesPathInfo): Any {
        return try {
            collaborativeEditingService.importDocument(file.fileName, file.roomName)
        } catch (e: Exception) {
            logger.error("Error importing file", e)
            objectMapper.writeValueAsString(
                ApiResponse<Unit>(error = e.message ?: ResponseFields.IMPORT_FAILED)
            )
        }
    }

    @PostMapping("/api/collaborativeediting/UpdateAction")
    fun updateAction(
        @RequestBody param: ActionInfo,
        principal: Principal
    ): ActionInfo {
        logger.info("Received UpdateAction request for room: ${param.roomName}, version: ${param.version}")

        val result = try {
            collaborativeEditingService.appendOperation(param, param.roomName)
        } catch (e: StaleClientException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
        }

        // Update user timestamps
        collaborativeEditingService.updateUserTimestamps(
            roomId = param.roomName,
            userName = principal.name,
            updateLastHeartbeat = true,
            updateLastAction = true
        )

        // Broadcast operation
        documentEditorHub.broadcastToRoom(
            param.roomName,
            result,
            MessageHeaders(mapOf("action" to "updateAction"))
        )

        return result
    }

    @PostMapping("/api/collaborativeediting/GetActionsFromServer")
    fun getActionsFromServer(@RequestBody param: ActionInfo): String {
        return try {
            val result = collaborativeEditingService.getOperationsSince(param.roomName, param.version)

            val response = OperationsSyncResponse(
                operations = result.operations,
                resync = result.resync,
                windowStart = if (result.resync) result.windowStart else null
            )

            objectMapper.writeValueAsString(response)
        } catch (ex: Exception) {
            logger.error("Error getting actions from server", ex)
            objectMapper.writeValueAsString(
                OperationsSyncResponse(operations = emptyList(), resync = false)
            )
        }
    }

    @PostMapping("/api/collaborativeediting/ShouldSave")
    fun shouldSave(
        @RequestBody request: ShouldSaveRequest,
        principal: Principal
    ): String {
        return try {
            val result = collaborativeEditingService.shouldSave(request.roomName, request.latestAppliedVersion)

            // Update user heartbeat
            collaborativeEditingService.updateUserTimestamps(
                roomId = request.roomName,
                userName = principal.name,
                updateLastHeartbeat = true
            )

            objectMapper.writeValueAsString(
                SaveCheckResponse(
                    shouldSave = result.shouldSave,
                    currentPersistedVersion = result.currentPersistedVersion
                )
            )
        } catch (ex: Exception) {
            logger.error("Error checking if should save", ex)
            objectMapper.writeValueAsString(
                SaveCheckResponse(shouldSave = false, error = ex.message)
            )
        }
    }

    @PostMapping("/api/collaborativeediting/SaveDocument")
    fun saveDocument(
        @RequestBody request: UpdateDocumentRequest,
        principal: Principal
    ): String {
        return try {
            val result = collaborativeEditingService.saveDocument(
                request.roomName,
                request.sfdt,
                request.latestAppliedVersion
            )

            // Update user's lastSave and lastHeartbeat timestamps
            collaborativeEditingService.updateUserTimestamps(
                roomId = request.roomName,
                userName = principal.name,
                updateLastHeartbeat = true,
                updateLastSave = true
            )

            objectMapper.writeValueAsString(
                SaveResultResponse(
                    success = true,
                    message = result.message,
                    skipped = if (result.skipped) true else null
                )
            )
        } catch (ex: Exception) {
            logger.error("Error saving document", ex)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "${ResponseFields.SAVE_FAILED_PREFIX} ${ex.message}"
            )
        }
    }
}
