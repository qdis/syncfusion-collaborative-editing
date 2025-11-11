-- Atomically append operation to Redis.
-- Increments version counter, stores operation in hash, and adds to sorted set.
--
-- KEYS: [versionKey, opsHashKey, opsIndexKey]
-- ARGV: [opJson]
--
-- Returns: newVersion (Long)
local versionKey, opsHashKey, opsIndexKey = KEYS[1], KEYS[2], KEYS[3]
local opJson = ARGV[1]

local newVersion = redis.call('INCR', versionKey)

-- Update version field in JSON before storing
local updatedJson = string.gsub(opJson, '"version"%s*:%s*%d+', '"version":' .. newVersion)

redis.call('HSET', opsHashKey, tostring(newVersion), updatedJson)
redis.call('ZADD', opsIndexKey, newVersion, tostring(newVersion))

return newVersion
