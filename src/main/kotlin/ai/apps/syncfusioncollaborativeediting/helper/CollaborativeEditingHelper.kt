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
     * KEYS: [opsHashKey, opsIndexKey, versionKey, persistedKey]
     * ARGV: [clientVersion]
     *
     * Returns: [newVersion, opsData[]] or ["STALE_CLIENT", persistedVersion]
     */
    const val RESERVE_VERSION_SCRIPT = """
        local opsHashKey, opsIndexKey, versionKey, persistedKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
        local clientVersion = tonumber(ARGV[1])

        -- Check if client is stale (before persisted window)
        local persistedVersion = tonumber(redis.call('GET', persistedKey) or "0")
        if clientVersion < persistedVersion then
            return {"STALE_CLIENT", persistedVersion}
        end

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
     * Delete a pending slot when abandoning a CAS retry.
     * Removes the __PENDING__ placeholder from both hash and index.
     *
     * KEYS: [opsHashKey, opsIndexKey]
     * ARGV: [version]
     *
     * Returns: "DELETED"
     */
    const val DELETE_PENDING_SLOT_SCRIPT = """
        local opsHashKey, opsIndexKey = KEYS[1], KEYS[2]
        local version = ARGV[1]

        redis.call('HDEL', opsHashKey, version)
        redis.call('ZREM', opsIndexKey, version)

        return 'DELETED'
    """

    /**
     * Re-fetch contiguous committed ops between clientVersion and targetVersion.
     * Used during CAS retries to get newly committed ops without allocating a new version.
     *
     * KEYS: [opsHashKey, opsIndexKey]
     * ARGV: [clientVersion, targetVersion]
     *
     * Returns: opsData[]
     */
    const val REFETCH_OPS_SCRIPT = """
        local opsHashKey, opsIndexKey = KEYS[1], KEYS[2]
        local clientVersion, targetVersion = tonumber(ARGV[1]), tonumber(ARGV[2])

        local versions = redis.call('ZRANGEBYSCORE', opsIndexKey, clientVersion + 1, targetVersion - 1)
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

        return result
    """

    /**
     * Commit transformed op with CAS check and contiguity verification.
     * Only succeeds if slot is still pending and all prior versions are committed.
     *
     * KEYS: [opsHashKey, opsIndexKey, persistedKey]
     * ARGV: [transformedJson, version]
     *
     * Returns: "OK", "VERSION_CONFLICT", "GAP_BEFORE", or "PENDING_BEFORE"
     */
    const val COMMIT_TRANSFORMED_SCRIPT = """
        local opsHashKey, opsIndexKey, persistedKey = KEYS[1], KEYS[2], KEYS[3]
        local transformedJson, version = ARGV[1], tonumber(ARGV[2])

        -- Ensure all versions between persisted_version+1 and version-1 are contiguous and committed
        local persisted = tonumber(redis.call('GET', persistedKey) or "0")
        local start = persisted + 1
        local last = version - 1

        if last >= start then
            local versions = redis.call('ZRANGEBYSCORE', opsIndexKey, start, last)
            local expect = start
            for _, v in ipairs(versions) do
                local ver = tonumber(v)
                if ver ~= expect then
                    return 'GAP_BEFORE'
                end
                local data = redis.call('HGET', opsHashKey, v)
                if not data or data == '__PENDING__' then
                    return 'PENDING_BEFORE'
                end
                expect = expect + 1
            end
            if expect ~= version then
                return 'GAP_BEFORE'
            end
        end

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
