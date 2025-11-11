// ABOUTME: Utility class for constructing Redis keys based on file UUID
// ABOUTME: Eliminates repeated string concatenation and ensures consistent key naming
package ai.apps.syncfusioncollaborativeediting.util

import ai.apps.syncfusioncollaborativeediting.constant.RedisKeys
import java.util.UUID

class RedisKeyBuilder(private val fileId: UUID) {
    private val key = fileId.toString()

    fun opsHashKey() = "$key${RedisKeys.OPS_HASH_SUFFIX}"
    fun opsIndexKey() = "$key${RedisKeys.OPS_INDEX_SUFFIX}"
    fun versionKey() = "$key${RedisKeys.VERSION_COUNTER_SUFFIX}"
    fun persistedVersionKey() = "$key${RedisKeys.PERSISTED_VERSION_SUFFIX}"
    fun userInfoKey() = "$key${RedisKeys.USER_INFO_SUFFIX}"
}
