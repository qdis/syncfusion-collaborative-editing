-- Initialize version counters for new documents atomically.
--
-- KEYS: [versionKey, persistedKey]
--
-- Returns: 1 if initialized, 0 if already exists
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('SET', KEYS[1], '0')
    redis.call('SET', KEYS[2], '0')
    return 1
end
return 0
