// ABOUTME: Helper class containing constants and Lua scripts for collaborative editing
// ABOUTME: Defines Redis key patterns, cache thresholds, and atomic operation scripts
package ai.apps.syncfusioncollaborativeediting.helper

object CollaborativeEditingHelper {

    // Maximum number of operations we can queue in single revision.
    // If we reach this limit, we will save the operations to source document.
    const val SAVE_THRESHOLD = 25

    // Redis key patterns using colon-based namespacing
    const val OPS_SUFFIX = ":ops"
    const val START_SUFFIX = ":start"
    const val TO_REMOVE_SUFFIX = ":to_remove"
    const val PERSISTED_VERSION_SUFFIX = ":persisted_version"
    const val USER_INFO_SUFFIX = ":user_info"

    /**
     * Atomically inserts a new operation and manages cache lifecycle.
     *
     * KEYS: [opsKey, startKey, toRemoveKey]
     * ARGV: [itemJson, clientVersion, cacheLimit]
     *
     * Returns: [newVersion, previousOps, removedCount]
     */
    const val NEW_INSERT_SCRIPT = """
        local opsKey, startKey, toRemoveKey = KEYS[1], KEYS[2], KEYS[3]
        local itemJson, clientVersion, cacheLimit =
          ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3])

        local start = tonumber(redis.call('GET', startKey) or "1")
        local len = redis.call('LLEN', opsKey)
        local newVersion = start + len

        redis.call('RPUSH', opsKey, itemJson)

        -- Client wants ops AFTER their version
        local idx = (clientVersion + 1) - start
        if idx < 0 then idx = 0 end

        local previous = redis.call('LRANGE', opsKey, idx, -1)

        -- Trim logic: if length exceeds cache limit, move oldest ops to to_remove queue
        local removed = 0
        len = len + 1
        if len > cacheLimit then
          removed = len - cacheLimit
          local chunk = redis.call('LRANGE', opsKey, 0, removed - 1)
          for i=1,#chunk do redis.call('RPUSH', toRemoveKey, chunk[i]) end
          redis.call('LTRIM', opsKey, removed, -1)
          redis.call('SET', startKey, start + removed)
        end

        return { newVersion, previous, removed }
    """

    /**
     * Updates an operation at a specific position after transformation.
     *
     * KEYS: [opsKey, startKey]
     * ARGV: [itemJson, prevVersion]
     *
     * Returns: "OK" or "EMPTY"
     */
    const val NEW_UPDATE_SCRIPT = """
        local opsKey, startKey = KEYS[1], KEYS[2]
        local itemJson, prevVersion = ARGV[1], tonumber(ARGV[2])

        local start = tonumber(redis.call('GET', startKey) or "1")
        local len = redis.call('LLEN', opsKey)

        if len == 0 then return "EMPTY" end

        local idx = prevVersion - start
        if idx < 0 then idx = 0 end
        if idx >= len then idx = len - 1 end

        redis.call('LSET', opsKey, idx, itemJson)
        return "OK"
    """

    /**
     * Fetches pending operations for a client since their last known version.
     *
     * KEYS: [opsKey, startKey]
     * ARGV: [clientVersion]
     *
     * Returns: [ops, resyncFlag]
     *   resyncFlag = 1 if client is stale (before window start)
     *   resyncFlag = 0 if client is within window
     */
    const val NEW_PENDING_SCRIPT = """
        local opsKey, startKey = KEYS[1], KEYS[2]
        local clientVersion = tonumber(ARGV[1] or "0")

        local start = tonumber(redis.call('GET', startKey) or "1")

        -- Client wants ops AFTER their version
        local idx = (clientVersion + 1) - start

        if idx < 0 then
          -- Client is before the window; full window + resync
          return { redis.call('LRANGE', opsKey, 0, -1), 1 }
        else
          -- Client is within or ahead of window - return ops from their position
          return { redis.call('LRANGE', opsKey, idx, -1), 0 }
        end
    """
}
