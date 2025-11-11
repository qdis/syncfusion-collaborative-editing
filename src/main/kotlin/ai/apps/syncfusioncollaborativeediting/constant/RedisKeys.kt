// ABOUTME: Constants for Redis key patterns used in collaborative editing
// ABOUTME: Defines suffixes, global keys, and special markers for Redis operations
package ai.apps.syncfusioncollaborativeediting.constant

object RedisKeys {
    // Redis key suffixes for room-specific data structures
    const val OPS_HASH_SUFFIX = ":ops_hash"         // HSET: version -> json
    const val OPS_INDEX_SUFFIX = ":ops_index"       // ZSET: score=version, member=version
    const val VERSION_COUNTER_SUFFIX = ":version"   // STRING: current version counter
    const val PERSISTED_VERSION_SUFFIX = ":persisted_version"  // STRING: highest version saved to MinIO
    const val USER_INFO_SUFFIX = ":user_info"       // LIST: connected users (presence)

    // Global Redis keys
    const val ACTIVE_ROOMS = "active_rooms"                   // SET: rooms with active users
    const val SESSION_TO_ROOM_MAPPING = "sessionIdToRoomIdMapping"  // HASH: sessionId -> roomId

    // Special markers
    const val PENDING_PLACEHOLDER = "__PENDING__"  // Placeholder during operation transformation
}
