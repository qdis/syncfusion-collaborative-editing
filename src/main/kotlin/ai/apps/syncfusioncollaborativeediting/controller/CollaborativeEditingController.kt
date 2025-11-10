// ABOUTME: Main REST controller for collaborative editing operations
// ABOUTME: Handles document import, action updates, and Redis cache management
package ai.apps.syncfusioncollaborativeediting.controller

import ai.apps.syncfusioncollaborativeediting.helper.CollaborativeEditingHelper
import ai.apps.syncfusioncollaborativeediting.model.FilesPathInfo
import ai.apps.syncfusioncollaborativeediting.service.MinioService
import com.fasterxml.jackson.databind.ObjectMapper
import com.syncfusion.ej2.wordprocessor.ActionInfo
import com.syncfusion.ej2.wordprocessor.CollaborativeEditingHandler
import com.syncfusion.ej2.wordprocessor.WordProcessorHelper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.messaging.MessageHeaders
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream

@RestController
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
class CollaborativeEditingController(
    private val minioService: MinioService,
    private val stringRedisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val documentEditorHub: DocumentEditorHub,
) {

    private val logger = LoggerFactory.getLogger(CollaborativeEditingController::class.java)

    @PostMapping("/api/collaborativeediting/ImportFile")
    fun importFile(@RequestBody file: FilesPathInfo): String {
        return try {
            val document = getDocumentFromMinIO(file.fileName)

            // Only apply ops newer than the persisted MinIO state
            val actions = getPendingOperations(file.roomName)
            if (actions.isNotEmpty()) {
                document.updateActions(actions)
            }
            logger.info(
                "Imported file: ${file.fileName} for room: ${file.roomName} with ${actions.size} pending actions"
            )


            val versionKey = file.roomName + CollaborativeEditingHelper.VERSION_COUNTER_SUFFIX
            val persistedKey = file.roomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

            // Initialize counters for new documents atomically
            val initScript = """
                    if redis.call('EXISTS', KEYS[1]) == 0 then
                        redis.call('SET', KEYS[1], '0')
                        redis.call('SET', KEYS[2], '0')
                        return 1
                    end
                    return 0
                """
            stringRedisTemplate.execute(
                DefaultRedisScript(initScript, Long::class.java),
                listOf(versionKey, persistedKey)
            )

            val currentVersion = stringRedisTemplate.opsForValue().get(versionKey)?.toIntOrNull() ?: 0
            val persistedVersion = stringRedisTemplate.opsForValue().get(persistedKey)?.toIntOrNull() ?: 0

            // Stamp with highest committed version that was applied
            val stampVersion = if (actions.isNotEmpty()) {
                actions.maxOf { it.version }
            } else {
                maxOf(currentVersion, persistedVersion)
            }

            // Serialize to SFDT and stamp version
            val sfdtString = WordProcessorHelper.serialize(document)
            val tree = objectMapper.readTree(sfdtString)
            (tree as com.fasterxml.jackson.databind.node.ObjectNode).put("version", stampVersion)

            objectMapper.writeValueAsString(tree)
        } catch (e: Exception) {
            logger.error("Error importing file", e)
            """{"error":"${e.message ?: "import failed"}"}"""
        }
    }


    @PostMapping("/api/collaborativeediting/UpdateAction")
    fun updateAction(@RequestBody param: ActionInfo): ActionInfo {
        logger.info("Received UpdateAction request for room: ${param.roomName}, version: ${param.version}")
        val roomName = param.roomName

        return try {
            val transformedAction = addOperationsToCache(param)

            // Mark room as dirty for background autosave
            stringRedisTemplate.opsForSet().add("dirty_rooms", roomName)

            // Broadcast to local WebSocket clients only (single-node deployment)
            val action = mapOf("action" to "updateAction")
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

            val script = DefaultRedisScript<List<Any>>()
            script.setScriptText(CollaborativeEditingHelper.GET_PENDING_SCRIPT)
            script.resultType = List::class.java as Class<List<Any>>

            val opsHashKey = roomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX
            val opsIndexKey = roomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX
            val persistedKey = roomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

            val result = stringRedisTemplate.execute(
                script,
                listOf(opsHashKey, opsIndexKey, persistedKey),
                clientVersion.toString()
            ) ?: throw IllegalStateException("Get pending failed")

            val opsData = result[0] as List<*>
            val resyncFlag = (result[1] as Number).toInt()
            val windowStart = (result[2] as Number).toInt()

            val actions = opsData.map { objectMapper.readValue(it.toString(), ActionInfo::class.java) }

            val response = mutableMapOf<String, Any>("operations" to actions)
            if (resyncFlag == 1) {
                response["resync"] = true
                response["windowStart"] = windowStart
            }

            objectMapper.writeValueAsString(response)
        } catch (ex: Exception) {
            logger.error("Error getting actions from server", ex)
            """{"operations":[],"resync":false}"""
        }
    }


    private fun getPendingOperations(roomName: String): List<ActionInfo> {
        return try {
            val opsHashKey = roomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX
            val opsIndexKey = roomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX
            val persistedKey = roomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

            val persistedVersion = stringRedisTemplate.opsForValue().get(persistedKey)?.toIntOrNull() ?: 0

            val versions = stringRedisTemplate.opsForZSet()
                .rangeByScore(opsIndexKey, (persistedVersion + 1).toDouble(), Double.POSITIVE_INFINITY)
                ?.toList()
                ?: emptyList()

            if (versions.isEmpty()) return emptyList()

            val raw = stringRedisTemplate.opsForHash<String, String>().multiGet(opsHashKey, versions)

            val committedPrefix = ArrayList<String>()
            for (i in versions.indices) {
                val v = raw[i]
                if (v == null || v == "__PENDING__") break
                committedPrefix.add(v)
            }

            committedPrefix.map { objectMapper.readValue(it, ActionInfo::class.java) }
                .sortedBy { it.version }
        } catch (ex: Exception) {
            logger.error("Error getting pending operations", ex)
            emptyList()
        }
    }


    private fun addOperationsToCache(action: ActionInfo): ActionInfo {
        val roomName = action.roomName
        val opsHashKey = roomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX
        val opsIndexKey = roomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX
        val versionKey = roomName + CollaborativeEditingHelper.VERSION_COUNTER_SUFFIX
        val persistedKey = roomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

        val clientVersion = action.version

        // Phase 1: Reserve version ONCE (ATOMIC)
        val reserveScript = DefaultRedisScript<List<Any>>()
        reserveScript.setScriptText(CollaborativeEditingHelper.RESERVE_VERSION_SCRIPT)
        reserveScript.resultType = List::class.java as Class<List<Any>>

        val reserveResult = stringRedisTemplate.execute(
            reserveScript,
            listOf(opsHashKey, opsIndexKey, versionKey, persistedKey),
            clientVersion.toString()
        ) ?: throw IllegalStateException("Reserve failed")

        // Check for STALE_CLIENT status
        if (reserveResult[0] == "STALE_CLIENT") {
            val persistedVersion = (reserveResult[1] as Number).toInt()
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "RESYNC_REQUIRED: client version $clientVersion < persisted version $persistedVersion"
            )
        }

        val newVersion = (reserveResult[0] as Number).toInt()
        var previousOpsData = reserveResult[1] as List<*>

        val maxRetries = 5
        var attempt = 0

        while (attempt < maxRetries) {
            attempt++

            // Phase 2: Transform locally (not holding Redis)
            val previousOps = previousOpsData.map {
                objectMapper.readValue(it.toString(), ActionInfo::class.java)
            }

            action.version = newVersion

            if (previousOps.isNotEmpty()) {
                val opsArray = ArrayList(previousOps + action)
                CollaborativeEditingHandler.transformOperation(action, opsArray)
            }
            action.isTransformed = true

            // Phase 3: Commit with CAS (ATOMIC)
            val commitScript = DefaultRedisScript<String>()
            commitScript.setScriptText(CollaborativeEditingHelper.COMMIT_TRANSFORMED_SCRIPT)
            commitScript.resultType = String::class.java

            val status = stringRedisTemplate.execute(
                commitScript,
                listOf(opsHashKey, opsIndexKey, persistedKey),
                objectMapper.writeValueAsString(action),
                newVersion.toString()
            )

            if (status == "OK") {
                logger.debug("Successfully committed version $newVersion for room $roomName")
                return action
            }

            // Retry for any preconditions that indicate gaps
            if (status == "GAP_BEFORE" || status == "PENDING_BEFORE" || status == "VERSION_CONFLICT") {
                logger.warn("CAS retry $attempt for room $roomName (status: $status)")

                // Re-fetch ops for next retry (some pending ops may have been committed)
                if (attempt < maxRetries) {
                    val refetchScript = DefaultRedisScript<List<Any>>()
                    refetchScript.setScriptText(CollaborativeEditingHelper.REFETCH_OPS_SCRIPT)
                    refetchScript.resultType = List::class.java as Class<List<Any>>

                    previousOpsData = stringRedisTemplate.execute(
                        refetchScript,
                        listOf(opsHashKey, opsIndexKey),
                        clientVersion.toString(),
                        newVersion.toString()
                    ) ?: emptyList<Any>()
                }
                continue
            }

            throw IllegalStateException("Unexpected commit status: $status")
        }

        // Max retries exceeded - cleanup the pending slot
        val deleteScript = DefaultRedisScript<String>()
        deleteScript.setScriptText(CollaborativeEditingHelper.DELETE_PENDING_SLOT_SCRIPT)
        deleteScript.resultType = String::class.java

        stringRedisTemplate.execute(
            deleteScript,
            listOf(opsHashKey, opsIndexKey),
            newVersion.toString()
        )

        throw IllegalStateException("CAS failed after $maxRetries retries, cleaned up pending slot $newVersion")
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
