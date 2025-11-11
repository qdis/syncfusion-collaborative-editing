// ABOUTME: Core business logic service for collaborative document editing
// ABOUTME: Handles document operations, Redis management, and user session tracking
package ai.apps.syncfusioncollaborativeediting.service

import ai.apps.syncfusioncollaborativeediting.constant.RedisKeys
import ai.apps.syncfusioncollaborativeediting.constant.ResponseFields
import ai.apps.syncfusioncollaborativeediting.model.UserSessionInfo
import ai.apps.syncfusioncollaborativeediting.repository.FileRepository
import ai.apps.syncfusioncollaborativeediting.util.RedisKeyBuilder
import com.fasterxml.jackson.databind.ObjectMapper
import com.syncfusion.docio.FormatType
import com.syncfusion.ej2.wordprocessor.ActionInfo
import com.syncfusion.ej2.wordprocessor.WordProcessorHelper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

@Service
class CollaborativeEditingService(
    private val minioService: MinioService,
    private val stringRedisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val scriptExecutor: RedisScriptExecutor,
    private val fileRepository: FileRepository
) {

    private val logger = LoggerFactory.getLogger(CollaborativeEditingService::class.java)

    /**
     * Import document from MinIO and apply pending operations.
     * Returns SFDT string with version stamped.
     */
    fun importDocument(fileId: UUID): String {
        val file = fileRepository.findById(fileId).orElseThrow {
            IllegalArgumentException("File not found: $fileId")
        }
        val fileName = file.fileName
        val document = getDocumentFromMinIO(fileName)

        // Apply pending operations newer than persisted MinIO state
        val actions = getPendingOperations(fileId)
        if (actions.isNotEmpty()) {
            document.updateActions(actions)
        }
        logger.info("Imported file: $fileName (id=$fileId) with ${actions.size} pending actions")

        val keys = RedisKeyBuilder(fileId)

        // Initialize version counters for new documents atomically
        scriptExecutor.initVersionCounters(keys)

        val currentVersion = stringRedisTemplate.opsForValue().get(keys.versionKey())?.toIntOrNull() ?: 0
        val persistedVersion = stringRedisTemplate.opsForValue().get(keys.persistedVersionKey())?.toIntOrNull() ?: 0

        // Stamp with highest committed version that was applied
        val stampVersion = if (actions.isNotEmpty()) {
            actions.maxOf { it.version }
        } else {
            maxOf(currentVersion, persistedVersion)
        }

        // Serialize to SFDT and stamp version
        val sfdtString = WordProcessorHelper.serialize(document)
        val tree = objectMapper.readTree(sfdtString)
        (tree as com.fasterxml.jackson.databind.node.ObjectNode).put(ResponseFields.VERSION, stampVersion)

        logger.info("Document for file: $fileId stamped with version: $stampVersion")

        return objectMapper.writeValueAsString(tree)
    }

    /**
     * Append operation to Redis and return operation with assigned version.
     * Throws exception if client is stale (version < persisted).
     */
    fun appendOperation(action: ActionInfo, fileId: UUID): ActionInfo {
        logger.info("Appending operation for file: $fileId, client version: ${action.version}")

        val keys = RedisKeyBuilder(fileId)

        // Ensure version counter is at least persisted_version
        scriptExecutor.ensureVersionMin(keys)

        // Check if client is stale
        val persistedVersion = stringRedisTemplate.opsForValue().get(keys.persistedVersionKey())?.toIntOrNull() ?: 0
        if (action.version < persistedVersion) {
            throw StaleClientException("${ResponseFields.RESYNC_REQUIRED_PREFIX} ${action.version} < persisted $persistedVersion")
        }

        // Atomically append operation
        val actionCopy = action.copy(isTransformed = false)
        val newVersion = scriptExecutor.appendOperation(
            keys,
            objectMapper.writeValueAsString(actionCopy)
        ).toInt()

        return actionCopy.copy(version = newVersion)
    }

    /**
     * Get operations since client version for sync.
     * Returns operations data, resync flag, and window start.
     */
    fun getOperationsSince(fileId: UUID, clientVersion: Int): GetOperationsResult {
        val keys = RedisKeyBuilder(fileId)

        val scriptResult = scriptExecutor.getOperationsSince(keys, clientVersion)

        val actions = scriptResult.opsData.map {
            objectMapper.readValue(it, ActionInfo::class.java)
        }

        return GetOperationsResult(
            operations = actions,
            resync = scriptResult.resync,
            windowStart = scriptResult.windowStart
        )
    }

    /**
     * Check if document should be saved based on version comparison.
     */
    fun shouldSave(fileId: UUID, latestAppliedVersion: Int): ShouldSaveResult {
        val keys = RedisKeyBuilder(fileId)
        val currentPersistedVersion = stringRedisTemplate.opsForValue().get(keys.persistedVersionKey())?.toIntOrNull() ?: 0
        val shouldSave = latestAppliedVersion > currentPersistedVersion

        logger.debug("ShouldSave check: file=$fileId, version=$latestAppliedVersion, persisted=$currentPersistedVersion, shouldSave=$shouldSave")

        return ShouldSaveResult(shouldSave, currentPersistedVersion)
    }

    /**
     * Save document to MinIO and cleanup old operations.
     * Returns success result or throws exception on failure.
     */
    fun saveDocument(fileId: UUID, sfdt: String, latestAppliedVersion: Int): SaveDocumentResult {
        logger.info("Saving document for file: $fileId at version: $latestAppliedVersion")

        val keys = RedisKeyBuilder(fileId)

        // Check if newer version is already saved
        val currentPersistedVersion = stringRedisTemplate.opsForValue().get(keys.persistedVersionKey())?.toIntOrNull() ?: 0
        if (latestAppliedVersion <= currentPersistedVersion) {
            logger.info("Skipping save: version $latestAppliedVersion <= persisted version $currentPersistedVersion for file: $fileId")
            return SaveDocumentResult(
                success = true,
                message = ResponseFields.SAVE_NOT_NEEDED,
                skipped = true
            )
        }

        // Lookup fileName from database
        val file = fileRepository.findById(fileId).orElseThrow {
            IllegalArgumentException("File not found: $fileId")
        }
        val fileName = file.fileName

        // Convert SFDT to DOCX
        val doc = WordProcessorHelper.save(sfdt)
        val outputStream = ByteArrayOutputStream()
        doc.save(outputStream, FormatType.Docx)
        val docxBytes = outputStream.toByteArray()

        // Upload to MinIO
        minioService.uploadDocument(fileName, docxBytes)
        logger.info("Uploaded document to MinIO: $fileName")

        // Cleanup Redis operations
        scriptExecutor.cleanupAfterSave(keys, latestAppliedVersion)

        logger.info("Cleaned up operations < version $latestAppliedVersion for file: $fileId")

        return SaveDocumentResult(
            success = true,
            message = ResponseFields.SAVE_SUCCESS,
            skipped = false
        )
    }

    /**
     * Get pending operations for a file (operations after persisted version).
     */
    fun getPendingOperations(fileId: UUID): List<ActionInfo> {
        return try {
            val keys = RedisKeyBuilder(fileId)
            val persistedVersion = stringRedisTemplate.opsForValue().get(keys.persistedVersionKey())?.toIntOrNull() ?: 0

            val versions = stringRedisTemplate.opsForZSet()
                .rangeByScore(keys.opsIndexKey(), (persistedVersion + 1).toDouble(), Double.POSITIVE_INFINITY)
                ?.toList()
                ?: emptyList()

            if (versions.isEmpty()) return emptyList()

            val raw = stringRedisTemplate.opsForHash<String, String>().multiGet(keys.opsHashKey(), versions)

            val committedPrefix = ArrayList<String>()
            for (i in versions.indices) {
                val v = raw[i]
                if (v == null || v == RedisKeys.PENDING_PLACEHOLDER) break
                committedPrefix.add(v)
            }

            committedPrefix.map { objectMapper.readValue(it, ActionInfo::class.java) }
                .sortedBy { it.version }
        } catch (ex: Exception) {
            logger.error("Error getting pending operations", ex)
            emptyList()
        }
    }

    /**
     * Update user session timestamps in Redis.
     */
    fun updateUserTimestamps(
        fileId: UUID,
        userName: String,
        updateLastHeartbeat: Boolean = false,
        updateLastAction: Boolean = false,
        updateLastSave: Boolean = false
    ): Boolean {
        val keys = RedisKeyBuilder(fileId)
        val userInfoKey = keys.userInfoKey()
        val userJsonStrings = stringRedisTemplate.opsForList().range(userInfoKey, 0, -1) ?: emptyList()

        for (userJson in userJsonStrings) {
            try {
                val userSession = objectMapper.readValue(userJson, UserSessionInfo::class.java)
                if (userSession.userName == userName) {
                    val now = Instant.now()
                    val updatedSession = userSession.copy(
                        lastHeartbeat = if (updateLastHeartbeat) now else userSession.lastHeartbeat,
                        lastAction = if (updateLastAction) now else userSession.lastAction,
                        lastSave = if (updateLastSave) now else userSession.lastSave
                    )

                    val updatedJson = objectMapper.writeValueAsString(updatedSession)

                    // Remove old entry and add updated entry
                    stringRedisTemplate.opsForList().remove(userInfoKey, 1, userJson)
                    stringRedisTemplate.opsForList().rightPush(userInfoKey, updatedJson)

                    logger.debug("Updated timestamps for user $userName in file $fileId")
                    return true
                }
            } catch (e: Exception) {
                logger.error("Error updating user timestamps", e)
            }
        }
        return false
    }

    /**
     * Get all user sessions for a file.
     */
    fun getUserSessions(fileId: UUID): List<UserSessionInfo> {
        val keys = RedisKeyBuilder(fileId)
        val userInfoKey = keys.userInfoKey()
        val userJsonStrings = stringRedisTemplate.opsForList().range(userInfoKey, 0, -1) ?: emptyList()

        return userJsonStrings.mapNotNull { userJson ->
            try {
                objectMapper.readValue(userJson, UserSessionInfo::class.java)
            } catch (e: Exception) {
                logger.error("Error parsing user information JSON", e)
                null
            }
        }
    }

    /**
     * Add user session to file.
     */
    fun addUserSession(fileId: UUID, sessionId: String, userName: String) {
        val keys = RedisKeyBuilder(fileId)
        val userInfoKey = keys.userInfoKey()

        val userSession = UserSessionInfo(
            userName = userName,
            userId = userName,
            sessionId = sessionId,
            lastHeartbeat = Instant.now(),
            lastAction = null,
            lastSave = null
        )

        val userJson = objectMapper.writeValueAsString(userSession)
        stringRedisTemplate.opsForList().rightPush(userInfoKey, userJson)
        stringRedisTemplate.opsForSet().add(RedisKeys.ACTIVE_ROOMS, fileId.toString())
    }

    /**
     * Remove user session from file.
     * Returns true if user was found and removed.
     */
    fun removeUserSession(fileId: UUID, sessionId: String): Boolean {
        val keys = RedisKeyBuilder(fileId)
        val userInfoKey = keys.userInfoKey()
        val userJsonStrings = stringRedisTemplate.opsForList().range(userInfoKey, 0, -1) ?: emptyList()

        for (userJson in userJsonStrings) {
            try {
                val userSession = objectMapper.readValue(userJson, UserSessionInfo::class.java)
                if (userSession.sessionId == sessionId) {
                    stringRedisTemplate.opsForList().remove(userInfoKey, 1, userJson)

                    // If no users left, cleanup
                    val remainingUsers = stringRedisTemplate.opsForList().size(userInfoKey) ?: 0
                    if (remainingUsers == 0L) {
                        stringRedisTemplate.delete(userInfoKey)
                        stringRedisTemplate.opsForSet().remove(RedisKeys.ACTIVE_ROOMS, fileId.toString())
                        logger.debug("File $fileId is now inactive (no users)")
                    }

                    return true
                }
            } catch (e: Exception) {
                logger.error("Error removing user session", e)
            }
        }
        return false
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

// Extension function for ActionInfo.copy()
private fun ActionInfo.copy(
    isTransformed: Boolean? = null,
    version: Int? = null
): ActionInfo {
    val original = this
    return ActionInfo().apply {
        roomName = original.roomName
        this.version = version ?: original.version
        connectionId = original.connectionId
        currentUser = original.currentUser
        clientVersion = original.clientVersion
        operations = original.operations
        this.isTransformed = isTransformed ?: original.isTransformed
    }
}

// Result classes
data class GetOperationsResult(
    val operations: List<ActionInfo>,
    val resync: Boolean,
    val windowStart: Int
)

data class ShouldSaveResult(
    val shouldSave: Boolean,
    val currentPersistedVersion: Int
)

data class SaveDocumentResult(
    val success: Boolean,
    val message: String,
    val skipped: Boolean
)

// Custom exception for stale client
class StaleClientException(message: String) : RuntimeException(message)
