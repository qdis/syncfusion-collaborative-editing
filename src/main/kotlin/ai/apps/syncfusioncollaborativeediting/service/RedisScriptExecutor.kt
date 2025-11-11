// ABOUTME: Typed wrapper service for executing Redis Lua scripts
// ABOUTME: Encapsulates script execution boilerplate and provides clean API for collaborative editing
package ai.apps.syncfusioncollaborativeediting.service

import ai.apps.syncfusioncollaborativeediting.util.RedisKeyBuilder
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service

@Service
class RedisScriptExecutor(
    private val stringRedisTemplate: StringRedisTemplate,
    private val initVersionCountersScript: RedisScript<Long>,
    private val appendOperationScript: RedisScript<Long>,
    private val getPendingOperationsScript: RedisScript<List<*>>,
    private val uiSaveCleanupScript: RedisScript<String>,
    private val ensureVersionMinScript: RedisScript<Long>
) {

    /**
     * Initialize version counters for new documents atomically.
     *
     * @param keys RedisKeyBuilder for the room
     * @return 1 if initialized, 0 if already exists
     */
    fun initVersionCounters(keys: RedisKeyBuilder): Long {
        return stringRedisTemplate.execute(
            initVersionCountersScript,
            listOf(keys.versionKey(), keys.persistedVersionKey())
        )
    }

    /**
     * Atomically append operation to Redis and assign version number.
     *
     * @param keys RedisKeyBuilder for the room
     * @param opJson JSON-serialized ActionInfo
     * @return The new version number assigned to the operation
     */
    fun appendOperation(keys: RedisKeyBuilder, opJson: String): Long {
        return stringRedisTemplate.execute(
            appendOperationScript,
            listOf(keys.versionKey(), keys.opsHashKey(), keys.opsIndexKey()),
            opJson
        )
    }

    /**
     * Get operations since client version for synchronization.
     *
     * @param keys RedisKeyBuilder for the room
     * @param clientVersion The client's current version
     * @return GetOperationsScriptResult containing operations data, resync flag, and window start
     */
    fun getOperationsSince(keys: RedisKeyBuilder, clientVersion: Int): GetOperationsScriptResult {
        val result = stringRedisTemplate.execute(
            getPendingOperationsScript,
            listOf(keys.opsHashKey(), keys.opsIndexKey(), keys.persistedVersionKey()),
            clientVersion.toString()
        )

        val opsData = result[0] as List<*>
        val resyncFlag = (result[1] as Number).toInt()
        val windowStart = (result[2] as Number).toInt()

        return GetOperationsScriptResult(
            opsData = opsData.map { it.toString() },
            resync = resyncFlag == 1,
            windowStart = windowStart
        )
    }

    /**
     * Prune operations older than saved version and update persisted_version marker.
     *
     * @param keys RedisKeyBuilder for the room
     * @param savedVersion The version number that was just persisted to MinIO
     * @return "OK" on success
     */
    fun cleanupAfterSave(keys: RedisKeyBuilder, savedVersion: Int): String {
        return stringRedisTemplate.execute(
            uiSaveCleanupScript,
            listOf(keys.opsHashKey(), keys.opsIndexKey(), keys.persistedVersionKey()),
            savedVersion.toString()
        )
    }

    /**
     * Ensure version counter is at least as high as persisted_version.
     *
     * @param keys RedisKeyBuilder for the room
     * @return The current version after adjustment
     */
    fun ensureVersionMin(keys: RedisKeyBuilder): Long {
        return stringRedisTemplate.execute(
            ensureVersionMinScript,
            listOf(keys.versionKey(), keys.persistedVersionKey())
        )
    }
}

/**
 * Result from getPendingOperationsScript Lua script.
 */
data class GetOperationsScriptResult(
    val opsData: List<String>,
    val resync: Boolean,
    val windowStart: Int
)
