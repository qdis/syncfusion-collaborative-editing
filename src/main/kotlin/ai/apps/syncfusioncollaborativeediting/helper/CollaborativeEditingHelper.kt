// ABOUTME: Helper class containing constants and Lua scripts for collaborative editing
// ABOUTME: Defines Redis key patterns using ZSET+HASH structure with CAS-based atomicity
package ai.apps.syncfusioncollaborativeediting.helper

object CollaborativeEditingHelper {

    // Redis key patterns using ZSET+HASH for ordered, gapless operation storage
    const val OPS_HASH_SUFFIX = ":ops_hash"         // HSET: version -> json
    const val OPS_INDEX_SUFFIX = ":ops_index"       // ZSET: score=version, member=version
    const val VERSION_COUNTER_SUFFIX = ":version"   // STRING: current version counter
    const val PERSISTED_VERSION_SUFFIX = ":persisted_version"  // STRING: highest version saved to MinIO
    const val USER_INFO_SUFFIX = ":user_info"       // LIST: connected users (presence)

    /**
     * Reserve version and placeholder, return contiguous committed ops for transform.
     * This prevents version gaps by reserving slots with __PENDING__ markers.
     *
     * KEYS: [opsHashKey, opsIndexKey, versionKey]
     * ARGV: [clientVersion]
     *
     * Returns: [newVersion, opsData[]]
     */
    const val RESERVE_VERSION_SCRIPT = """
        local opsHashKey, opsIndexKey, versionKey = KEYS[1], KEYS[2], KEYS[3]
        local clientVersion = tonumber(ARGV[1])

        -- Allocate new version
        local newVersion = redis.call('INCR', versionKey)

        -- Reserve slot with placeholder
        redis.call('HSET', opsHashKey, tostring(newVersion), '__PENDING__')
        redis.call('ZADD', opsIndexKey, newVersion, tostring(newVersion))

        -- Get contiguous committed ops starting after clientVersion
        local versions = redis.call('ZRANGEBYSCORE', opsIndexKey, clientVersion + 1, newVersion - 1)
        local result = {}
        local expect = clientVersion + 1

        for _, v in ipairs(versions) do
            local ver = tonumber(v)
            if ver ~= expect then break end  -- Gap found, stop

            local data = redis.call('HGET', opsHashKey, v)
            if data == '__PENDING__' or not data then break end  -- Not committed yet

            table.insert(result, data)
            expect = expect + 1
        end

        return {newVersion, result}
    """

    /**
     * Commit transformed op with CAS check.
     * Only succeeds if slot is still pending (hasn't been taken by another process).
     *
     * KEYS: [opsHashKey, opsIndexKey]
     * ARGV: [transformedJson, version]
     *
     * Returns: "OK" or "VERSION_CONFLICT"
     */
    const val COMMIT_TRANSFORMED_SCRIPT = """
        local opsHashKey, opsIndexKey = KEYS[1], KEYS[2]
        local transformedJson, version = ARGV[1], tonumber(ARGV[2])

        -- CAS: verify slot is still pending
        local current = redis.call('HGET', opsHashKey, tostring(version))
        if current ~= '__PENDING__' and current ~= false then
            return 'VERSION_CONFLICT'
        end

        -- Commit
        redis.call('HSET', opsHashKey, tostring(version), transformedJson)
        return 'OK'
    """

    /**
     * Get contiguous committed ops for client sync.
     * Returns resync flag if client is stale (before persisted window).
     *
     * KEYS: [opsHashKey, opsIndexKey, persistedKey]
     * ARGV: [clientVersion]
     *
     * Returns: {opsData[], resyncFlag, windowStart}
     */
    const val GET_PENDING_SCRIPT = """
        local opsHashKey, opsIndexKey, persistedKey = KEYS[1], KEYS[2], KEYS[3]
        local clientVersion = tonumber(ARGV[1] or "0")

        local persistedVersion = tonumber(redis.call('GET', persistedKey) or "0")
        local windowStart = persistedVersion + 1

        -- Client is stale (before persisted window)
        if clientVersion < persistedVersion then
            return {{}, 1, windowStart}
        end

        -- Get contiguous range
        local versions = redis.call('ZRANGEBYSCORE', opsIndexKey, clientVersion + 1, '+inf')
        local result = {}
        local expect = clientVersion + 1

        for _, v in ipairs(versions) do
            local ver = tonumber(v)
            if ver ~= expect then break end

            local data = redis.call('HGET', opsHashKey, v)
            if data == '__PENDING__' or not data then break end

            table.insert(result, data)
            expect = expect + 1
        end

        return {result, 0, windowStart}
    """

    /**
     * Autosave: snapshot pending versions (no lock held).
     * Fast read-only operation to prepare for background save.
     *
     * KEYS: [opsIndexKey, persistedKey]
     * ARGV: [maxCount]
     *
     * Returns: {persistedVersion, versions[]}
     */
    const val AUTOSAVE_SNAPSHOT_SCRIPT = """
        local opsIndexKey, persistedKey = KEYS[1], KEYS[2]
        local maxCount = tonumber(ARGV[1] or "1000")

        local persistedVersion = tonumber(redis.call('GET', persistedKey) or "0")
        local versions = redis.call('ZRANGEBYSCORE', opsIndexKey,
            persistedVersion + 1, '+inf', 'LIMIT', 0, maxCount)

        return {persistedVersion, versions}
    """

    /**
     * Autosave cleanup with CAS on persisted_version.
     * Only advances if no other process has advanced persisted_version.
     *
     * KEYS: [opsHashKey, opsIndexKey, persistedKey]
     * ARGV: [newPersistedVersion, expectedCurrentPersisted]
     *
     * Returns: actualPersistedVersion
     */
    const val AUTOSAVE_CLEANUP_SCRIPT = """
        local opsHashKey, opsIndexKey, persistedKey = KEYS[1], KEYS[2], KEYS[3]
        local newPersisted = tonumber(ARGV[1])
        local expectedCurrent = tonumber(ARGV[2])

        local currentPersisted = tonumber(redis.call('GET', persistedKey) or "0")

        -- CAS check: only advance if no one else did
        if currentPersisted ~= expectedCurrent then
            return currentPersisted  -- Someone else advanced, we're done
        end

        -- Advance persisted_version
        if newPersisted > currentPersisted then
            redis.call('SET', persistedKey, newPersisted)

            -- Delete ops <= newPersisted
            local toDelete = redis.call('ZRANGEBYSCORE', opsIndexKey, '-inf', newPersisted)
            for _, v in ipairs(toDelete) do
                redis.call('HDEL', opsHashKey, v)
            end
            redis.call('ZREMRANGEBYSCORE', opsIndexKey, '-inf', newPersisted)
        end

        return newPersisted
    """
}
