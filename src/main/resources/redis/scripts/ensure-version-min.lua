-- Ensure version counter is at least as high as persisted_version.
-- If version < persisted_version, set version = persisted_version.
--
-- KEYS: [versionKey, persistedVersionKey]
-- ARGV: none
--
-- Returns: current version after adjustment (number)
local versionKey, persistedVersionKey = KEYS[1], KEYS[2]

local currentVersion = tonumber(redis.call('GET', versionKey) or "0")
local persistedVersion = tonumber(redis.call('GET', persistedVersionKey) or "0")

if currentVersion < persistedVersion then
    redis.call('SET', versionKey, persistedVersion)
    return persistedVersion
end

return currentVersion
