-- UI-initiated save cleanup: prune operations and update persisted_version.
-- Deletes operations with version < savedVersion (strictly less than).
--
-- KEYS: [opsHashKey, opsIndexKey, persistedKey]
-- ARGV: [savedVersion]
--
-- Returns: "OK"
local opsHashKey, opsIndexKey, persistedKey = KEYS[1], KEYS[2], KEYS[3]
local savedVersion = tonumber(ARGV[1])

local currentPersisted = tonumber(redis.call('GET', persistedKey) or "0")

-- Update persisted_version to savedVersion (if greater)
if savedVersion > currentPersisted then
    redis.call('SET', persistedKey, savedVersion)
end

-- Delete ops with version < savedVersion (strictly less than)
if savedVersion > 1 then
    local toDelete = redis.call('ZRANGEBYSCORE', opsIndexKey, '-inf', '(' .. savedVersion)
    for _, v in ipairs(toDelete) do
        redis.call('HDEL', opsHashKey, v)
    end
    redis.call('ZREMRANGEBYSCORE', opsIndexKey, '-inf', '(' .. savedVersion)
end

return 'OK'
