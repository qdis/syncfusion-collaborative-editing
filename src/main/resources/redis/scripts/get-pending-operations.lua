-- Get contiguous committed ops for client sync.
-- Returns resync flag if client is stale (before persisted window).
--
-- KEYS: [opsHashKey, opsIndexKey, persistedKey]
-- ARGV: [clientVersion]
--
-- Returns: {opsData[], resyncFlag, windowStart}
local opsHashKey, opsIndexKey, persistedKey = KEYS[1], KEYS[2], KEYS[3]
local clientVersion = tonumber(ARGV[1] or "0")

local persistedVersion = tonumber(redis.call('GET', persistedKey) or "0")

if clientVersion < persistedVersion then
    return {{}, 1, persistedVersion}
end

local versions = redis.call('ZRANGEBYSCORE', opsIndexKey, clientVersion + 1, '+inf')
local result = {}

for _, v in ipairs(versions) do
    local data = redis.call('HGET', opsHashKey, v)
    if data then
        table.insert(result, data)
    end
end

return {result, 0, persistedVersion}
