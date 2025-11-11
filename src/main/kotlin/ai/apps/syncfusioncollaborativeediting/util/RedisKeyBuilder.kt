// ABOUTME: Utility class for constructing Redis keys based on room names
// ABOUTME: Eliminates repeated string concatenation and ensures consistent key naming
package ai.apps.syncfusioncollaborativeediting.util

import ai.apps.syncfusioncollaborativeediting.constant.RedisKeys

class RedisKeyBuilder(private val roomName: String) {
    fun opsHashKey() = "$roomName${RedisKeys.OPS_HASH_SUFFIX}"
    fun opsIndexKey() = "$roomName${RedisKeys.OPS_INDEX_SUFFIX}"
    fun versionKey() = "$roomName${RedisKeys.VERSION_COUNTER_SUFFIX}"
    fun persistedVersionKey() = "$roomName${RedisKeys.PERSISTED_VERSION_SUFFIX}"
    fun userInfoKey() = "$roomName${RedisKeys.USER_INFO_SUFFIX}"
}
