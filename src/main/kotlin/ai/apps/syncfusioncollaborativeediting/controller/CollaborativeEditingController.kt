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

            // Get the list of pending operations for the document
            val actions = getPendingOperations(file.roomName, 0, -1)
            if (!actions.isNullOrEmpty()) {
                // If there are any pending actions, update the document with these actions
                document.updateActions(actions)
            }

            logger.info("Imported file: ${file.fileName} for room: ${file.roomName} with ${actions?.size ?: 0} pending actions")

            // Get the current version directly from Redis
            val versionKey = file.roomName + CollaborativeEditingHelper.VERSION_INFO_SUFFIX
            val currentVersion = stringRedisTemplate.opsForValue().get(versionKey)?.toIntOrNull() ?: 0

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
        val transformedAction = addOperationsToCache(param)

        val action = mapOf("action" to "updateAction")
        documentEditorHub.publishToRedis(roomName, transformedAction)
        documentEditorHub.broadcastToRoom(roomName, transformedAction, MessageHeaders(action))

        return transformedAction
    }

    @PostMapping("/api/collaborativeediting/GetActionsFromServer")
    fun getActionsFromServer(@RequestBody param: ActionInfo): String {
        return try {
            val roomName = param.roomName
            val lastSyncedVersion = param.version
            var clientVersion = param.version

            // Fetch actions that are effective and pending based on the last synced version
            val actions = getEffectivePendingVersion(roomName, lastSyncedVersion)
            val currentAction = mutableListOf<ActionInfo>()

            for (action in actions) {
                // Increment the version for each action sequentially
                action.version = ++clientVersion

                // Filter actions to only include those that are newer than the client's last known version
                if (action.version > lastSyncedVersion) {
                    // Transform actions that have not been transformed yet
                    if (!action.isTransformed) {
                        CollaborativeEditingHandler.transformOperation(action, ArrayList(actions))
                    }
                    currentAction.add(action)
                }
            }

            // Serialize the filtered and transformed actions to JSON and return
            objectMapper.writeValueAsString(currentAction)
        } catch (ex: Exception) {
            logger.error("Error getting actions from server", ex)
            "{}"
        }
    }

    private fun getPendingOperations(listKey: String, startIndex: Int, endIndex: Int): List<ActionInfo>? {
        return try {
            val script = DefaultRedisScript<List<Any>>()
            script.setScriptText(CollaborativeEditingHelper.PENDING_OPERATIONS)
            script.resultType = List::class.java as Class<List<Any>>

            val keys = listOf(listKey, listKey + CollaborativeEditingHelper.ACTIONS_TO_REMOVE_SUFFIX)
            val args = listOf(startIndex.toString(), endIndex.toString())

            val response = stringRedisTemplate.execute(script, keys, *args.toTypedArray())
            val actions = mutableListOf<ActionInfo>()

            response?.forEach { result ->
                if (result is List<*>) {
                    result.forEach { item ->
                        if (item is String) {
                            actions.add(objectMapper.readValue(item, ActionInfo::class.java))
                        }
                    }
                }
            }

            actions
        } catch (ex: Exception) {
            logger.error("Error getting pending operations", ex)
            null
        }
    }

    private fun getEffectivePendingVersion(roomName: String, lastSyncedVersion: Int): List<ActionInfo> {
        val script = DefaultRedisScript<List<Any>>()
        script.setScriptText(CollaborativeEditingHelper.EFFECTIVE_PENDING_OPERATIONS)
        script.resultType = List::class.java as Class<List<Any>>

        val keys = listOf(roomName, roomName + CollaborativeEditingHelper.REVISION_INFO_SUFFIX)
        val args = listOf(lastSyncedVersion.toString(), CollaborativeEditingHelper.SAVE_THRESHOLD.toString())

        val response = stringRedisTemplate.execute(script, keys, *args.toTypedArray())
        val actions = mutableListOf<ActionInfo>()

        response?.forEach { result ->
            if (result is String) {
                actions.add(objectMapper.readValue(result, ActionInfo::class.java))
            }
        }

        return actions
    }

    private fun addOperationsToCache(action: ActionInfo): ActionInfo {
        var updatedAction = action
        val clientVersion = action.version
        val serializedAction = objectMapper.writeValueAsString(action)
        val roomName = action.roomName

        // Define the keys for Redis operations based on the action's room name
        val keys = listOf(
            roomName + CollaborativeEditingHelper.VERSION_INFO_SUFFIX,
            roomName,
            roomName + CollaborativeEditingHelper.REVISION_INFO_SUFFIX,
            roomName + CollaborativeEditingHelper.ACTIONS_TO_REMOVE_SUFFIX
        )

        // Prepare values for the Redis script
        val values = listOf(
            serializedAction,
            clientVersion.toString(),
            CollaborativeEditingHelper.SAVE_THRESHOLD.toString()
        )

        try {
            val script = DefaultRedisScript<List<Any>>()
            script.setScriptText(CollaborativeEditingHelper.INSERT_SCRIPT)
            script.resultType = List::class.java as Class<List<Any>>

            val response = stringRedisTemplate.execute(script, keys, *values.toTypedArray())

            response.let { results ->
                // Parse the version number from the script results
                val version = results[0].toString().toInt()

                // Deserialize the list of previous operations from the script results
                val previousOperations = mutableListOf<ActionInfo>()
                val data = results[1]
                if (data is List<*>) {
                    data.forEach { result ->
                        if (result is String) {
                            previousOperations.add(objectMapper.readValue(result, ActionInfo::class.java))
                        }
                    }
                }

                // Increment the version for each previous operation
                previousOperations.forEach { op -> op.version += 1 }

                // Check if there are multiple previous operations to determine if transformation is needed
                if (previousOperations.size > 1) {
                    // Set the current action to the last operation in the list
                    updatedAction = previousOperations[previousOperations.size - 1]
                    val operationsArrayList = ArrayList(previousOperations)
                    previousOperations.forEach { op ->
                        // Transform operations that have not been transformed yet
                        val operation = op.operations
                        if (operation != null && !op.isTransformed) {
                            CollaborativeEditingHandler.transformOperation(op, operationsArrayList)
                        }
                    }
                }

                // Update the action's version and mark it as transformed
                updatedAction.version = version
                updatedAction.isTransformed = true

                // Update the record in the cache with the new version
                updateRecordToCache(version, updatedAction)

                // Check if there are cleared operations to be saved
                if (results.size > 2 && results[2] != null) {
                    @Suppress("UNCHECKED_CAST")
                    autoSaveChangesToSourceDocument(results[2] as List<Any>, updatedAction)
                }
            }
        } catch (e: Exception) {
            logger.error("Error adding operations to cache", e)
        }

        return updatedAction
    }

    private fun updateRecordToCache(version: Int, action: ActionInfo) {
        val serializedAction = objectMapper.writeValueAsString(action)
        val roomName = action.roomName
        val revisionInfoKey = roomName + CollaborativeEditingHelper.REVISION_INFO_SUFFIX
        val previousVersion = (version - 1).toString()
        val saveThreshold = CollaborativeEditingHelper.SAVE_THRESHOLD.toString()

        try {
            val script = DefaultRedisScript<Void>()
            script.setScriptText(CollaborativeEditingHelper.UPDATE_RECORD)

            val keys = listOf(roomName, revisionInfoKey)
            val args = listOf(serializedAction, previousVersion, saveThreshold)

            stringRedisTemplate.execute(script, keys, *args.toTypedArray())
        } catch (ex: Exception) {
            logger.error("Error updating record to cache", ex)
        }
    }

    private fun autoSaveChangesToSourceDocument(clearedOperations: List<Any>, action: ActionInfo) {
        val actions = clearedOperations.mapNotNull { operation ->
            if (operation is String) {
                objectMapper.readValue(operation, ActionInfo::class.java)
            } else null
        }

        // Prepare the message for saving the cleared operations
        val message = SaveInfo(
            roomName = action.roomName,
            actions = actions,
            partialSave = true
        )
        logger.info("Scheduling auto-save for room: ${action.roomName} with ${actions.size} cleared operations")
        backgroundService.addItemToProcess(message)
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
