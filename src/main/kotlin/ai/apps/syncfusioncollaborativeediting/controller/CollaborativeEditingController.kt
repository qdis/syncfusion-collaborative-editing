// ABOUTME: Main REST controller for collaborative editing operations
// ABOUTME: Handles document import, action updates, and Redis cache management
package ai.apps.syncfusioncollaborativeediting.controller

import ai.apps.syncfusioncollaborativeediting.helper.CollaborativeEditingHelper
import ai.apps.syncfusioncollaborativeediting.model.FilesPathInfo
import ai.apps.syncfusioncollaborativeediting.model.SaveInfo
import ai.apps.syncfusioncollaborativeediting.service.BackgroundService
import ai.apps.syncfusioncollaborativeediting.service.MinioService
import com.fasterxml.jackson.databind.ObjectMapper
import com.syncfusion.ej2.wordprocessor.ActionInfo
import com.syncfusion.ej2.wordprocessor.CollaborativeEditingHandler
import com.syncfusion.ej2.wordprocessor.WordProcessorHelper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.messaging.MessageHeaders
import org.springframework.web.bind.annotation.*
import java.io.ByteArrayInputStream

@RestController
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
class CollaborativeEditingController(
    private val minioService: MinioService,
    private val stringRedisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val documentEditorHub: DocumentEditorHub,
    private val backgroundService: BackgroundService
) {

    private val logger = LoggerFactory.getLogger(CollaborativeEditingController::class.java)

    @PostMapping("/api/collaborativeediting/ImportFile")
    fun importFile(@RequestBody file: FilesPathInfo): String {
        return try {

            val document = getDocumentFromMinIO(file.fileName)

            // Get pending operations, filtering out persisted ones
            val actions = getPendingOperations(file.roomName)
            if (actions.isNotEmpty()) {
                // If there are any pending actions, update the document with these actions
                document.updateActions(actions)
            }

            logger.info("Imported file: ${file.fileName} for room: ${file.roomName} with ${actions.size} pending actions")

            // Calculate current version: start + length - 1 (or 0 if no ops)
            val startKey = file.roomName + CollaborativeEditingHelper.START_SUFFIX
            val opsKey = file.roomName + CollaborativeEditingHelper.OPS_SUFFIX

            val start = stringRedisTemplate.opsForValue().get(startKey)?.toIntOrNull() ?: 1
            val length = stringRedisTemplate.opsForList().size(opsKey)?.toInt() ?: 0
            val currentVersion = if (length > 0) start + length - 1 else 0

            // Serialize the updated document to SFDT format
            val sfdtString = WordProcessorHelper.serialize(document)
            val tree = objectMapper.readTree(sfdtString)
            (tree as com.fasterxml.jackson.databind.node.ObjectNode).put("version", currentVersion)

            return objectMapper.writeValueAsString(tree)
        } catch (e: Exception) {
            logger.error("Error importing file", e)
            """{"sections":[{"blocks":[{"inlines":[{"text":"${e.message}"}]}]}]}"""
        }
    }

    @PostMapping("/api/collaborativeediting/UpdateAction")
    fun updateAction(@RequestBody param: ActionInfo): ActionInfo {
        logger.info("Received UpdateAction request for room: ${param.roomName}, version: ${param.version}")
        val roomName = param.roomName

        return try {
            val transformedAction = addOperationsToCache(param)

            // Broadcast only after successful UPDATE
            val action = mapOf("action" to "updateAction")
            documentEditorHub.publishToRedis(roomName, transformedAction)
            documentEditorHub.broadcastToRoom(roomName, transformedAction, MessageHeaders(action))

            transformedAction
        } catch (e: Exception) {
            logger.error("Error in UpdateAction - returning 409 Conflict", e)
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "Operation update failed: ${e.message}"
            )
        }
    }

    @PostMapping("/api/collaborativeediting/GetActionsFromServer")
    fun getActionsFromServer(@RequestBody param: ActionInfo): String {
        return try {
            val roomName = param.roomName
            val clientVersion = param.version

            // Fetch operations since client's version with resync flag
            val (actions, needsResync) = getPendingOperationsSince(roomName, clientVersion)

            // Transform untransformed operations
            actions.forEach { action ->
                if (!action.isTransformed) {
                    CollaborativeEditingHandler.transformOperation(action, ArrayList(actions))
                }
            }

            // Build response with resync flag if needed
            val response = mutableMapOf<String, Any>(
                "operations" to actions
            )
            if (needsResync) {
                response["resync"] = true
            }

            objectMapper.writeValueAsString(response)
        } catch (ex: Exception) {
            logger.error("Error getting actions from server", ex)
            """{"operations":[],"resync":false}"""
        }
    }

    private fun getPendingOperations(roomName: String): List<ActionInfo> {
        return try {
            val opsKey = roomName + CollaborativeEditingHelper.OPS_SUFFIX
            val toRemoveKey = roomName + CollaborativeEditingHelper.TO_REMOVE_SUFFIX
            val persistedVersionKey = roomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

            val persistedVersion = stringRedisTemplate.opsForValue().get(persistedVersionKey)?.toIntOrNull() ?: 0

            val actions = mutableListOf<ActionInfo>()

            // Get to_remove ops and filter by persisted_version
            val toRemoveOps = stringRedisTemplate.opsForList().range(toRemoveKey, 0, -1) ?: emptyList()
            toRemoveOps.forEach { item ->
                val action = objectMapper.readValue(item, ActionInfo::class.java)
                // Only include ops not yet persisted to MinIO
                if (action.version > persistedVersion) {
                    actions.add(action)
                }
            }

            // Always get ops from the main queue
            val opsValues = stringRedisTemplate.opsForList().range(opsKey, 0, -1) ?: emptyList()
            opsValues.forEach { item ->
                actions.add(objectMapper.readValue(item, ActionInfo::class.java))
            }

            actions
        } catch (ex: Exception) {
            logger.error("Error getting pending operations", ex)
            emptyList()
        }
    }

    private fun getPendingOperationsSince(roomName: String, clientVersion: Int): Pair<List<ActionInfo>, Boolean> {
        val script = DefaultRedisScript<List<Any>>()
        script.setScriptText(CollaborativeEditingHelper.NEW_PENDING_SCRIPT)
        script.resultType = List::class.java as Class<List<Any>>

        val opsKey = roomName + CollaborativeEditingHelper.OPS_SUFFIX
        val startKey = roomName + CollaborativeEditingHelper.START_SUFFIX
        val keys = listOf(opsKey, startKey)
        val args = listOf(clientVersion.toString())

        val response = stringRedisTemplate.execute(script, keys, *args.toTypedArray())
            ?: return Pair(emptyList(), false)

        val actions = mutableListOf<ActionInfo>()
        var resyncFlag = false

        if (response.size >= 2) {
            // First element is the operations list
            val opsData = response[0]
            if (opsData is List<*>) {
                opsData.forEach { item ->
                    if (item is String) {
                        actions.add(objectMapper.readValue(item, ActionInfo::class.java))
                    }
                }
            }

            // Second element is the resync flag (1 = needs resync, 0 = normal)
            val flagValue = response[1]
            resyncFlag = when (flagValue) {
                is Number -> flagValue.toInt() == 1
                is String -> flagValue.toIntOrNull() == 1
                else -> false
            }
        }

        return Pair(actions, resyncFlag)
    }

    private fun addOperationsToCache(action: ActionInfo): ActionInfo {
        val clientVersion = action.version
        val serializedAction = objectMapper.writeValueAsString(action)
        val roomName = action.roomName

        val opsKey = roomName + CollaborativeEditingHelper.OPS_SUFFIX
        val startKey = roomName + CollaborativeEditingHelper.START_SUFFIX
        val toRemoveKey = roomName + CollaborativeEditingHelper.TO_REMOVE_SUFFIX

        val keys = listOf(opsKey, startKey, toRemoveKey)
        val cacheLimit = (CollaborativeEditingHelper.SAVE_THRESHOLD * 2).toString()
        val values = listOf(serializedAction, clientVersion.toString(), cacheLimit)

        try {
            // Call NEW_INSERT_SCRIPT to atomically append operation and get previous ops
            val insertScript = DefaultRedisScript<List<Any>>()
            insertScript.setScriptText(CollaborativeEditingHelper.NEW_INSERT_SCRIPT)
            insertScript.resultType = List::class.java as Class<List<Any>>

            val response = stringRedisTemplate.execute(insertScript, keys, *values.toTypedArray())
                ?: throw IllegalStateException("INSERT script returned null")

            // Parse results: [newVersion, previousOps, removedCount]
            val serverVersion = response[0].toString().toInt()
            val removedCount = response[2].toString().toInt()

            val previousOperations = mutableListOf<ActionInfo>()
            val data = response[1]
            if (data is List<*>) {
                data.forEach { result ->
                    if (result is String) {
                        previousOperations.add(objectMapper.readValue(result, ActionInfo::class.java))
                    }
                }
            }

            // Transform the operation against previousOps if needed
            // Note: previousOps includes the just-appended op due to RPUSH before LRANGE
            var updatedAction = previousOperations.last()
            if (previousOperations.size > 1) {
                val operationsArrayList = ArrayList(previousOperations)
                previousOperations.forEach { op ->
                    if (op.operations != null && !op.isTransformed) {
                        CollaborativeEditingHandler.transformOperation(op, operationsArrayList)
                    }
                }
                updatedAction = previousOperations.last()
            }

            updatedAction.version = serverVersion
            updatedAction.isTransformed = true

            // Call NEW_UPDATE_SCRIPT to persist the transformed operation
            val updateResult = updateRecordToCache(serverVersion, updatedAction)
            if (updateResult != "OK") {
                logger.error("UPDATE script failed: $updateResult")
                throw IllegalStateException("Failed to update operation in cache: $updateResult")
            }

            // Only schedule auto-save if this operation actually triggered a trim
            if (removedCount > 0) {
                val trimmedOps = stringRedisTemplate.opsForList().range(toRemoveKey, 0, -1) ?: emptyList()
                val actions = trimmedOps.mapNotNull { operation ->
                    if (operation is String) {
                        objectMapper.readValue(operation, ActionInfo::class.java)
                    } else null
                }

                if (actions.isNotEmpty()) {
                    val message = SaveInfo(
                        roomName = roomName,
                        actions = actions,
                        partialSave = true
                    )
                    logger.info("Scheduling auto-save for room: $roomName with ${actions.size} trimmed operations (removed: $removedCount)")
                    backgroundService.addItemToProcess(message)
                }
            }

            return updatedAction
        } catch (e: Exception) {
            logger.error("Error adding operations to cache", e)
            throw e
        }
    }

    private fun updateRecordToCache(version: Int, action: ActionInfo): String {
        val serializedAction = objectMapper.writeValueAsString(action)
        val roomName = action.roomName

        val opsKey = roomName + CollaborativeEditingHelper.OPS_SUFFIX
        val startKey = roomName + CollaborativeEditingHelper.START_SUFFIX

        try {
            val script = DefaultRedisScript<String>()
            script.setScriptText(CollaborativeEditingHelper.NEW_UPDATE_SCRIPT)
            script.resultType = String::class.java

            val keys = listOf(opsKey, startKey)
            val args = listOf(serializedAction, version.toString())

            return stringRedisTemplate.execute(script, keys, *args.toTypedArray()) ?: "ERROR"
        } catch (ex: Exception) {
            logger.error("Error updating record to cache", ex)
            return "ERROR"
        }
    }

    private fun getDocumentFromMinIO(fileName: String): WordProcessorHelper {
        return try {
            val objectData = minioService.downloadDocument(fileName)
            val data = objectData.readAllBytes()

            ByteArrayInputStream(data).use { stream ->
                WordProcessorHelper.load(stream, true)
            }
        } catch (e: Exception) {
            logger.error("Error getting document from MinIO", e)
            throw e
        }
    }
}
