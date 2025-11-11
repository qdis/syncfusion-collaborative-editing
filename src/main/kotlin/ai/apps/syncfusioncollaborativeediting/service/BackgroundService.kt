// ABOUTME: Background service for cleanup of inactive file keys and stale user sessions
// ABOUTME: Removes Redis keys for files with no users and removes stale user sessions
package ai.apps.syncfusioncollaborativeediting.service

import ai.apps.syncfusioncollaborativeediting.constant.RedisKeys
import ai.apps.syncfusioncollaborativeediting.controller.DocumentEditorHub
import ai.apps.syncfusioncollaborativeediting.util.RedisKeyBuilder
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class BackgroundService(
    private val stringRedisTemplate: StringRedisTemplate,
    private val collaborativeEditingService: CollaborativeEditingService,
    private val documentEditorHub: DocumentEditorHub
) {

    private val logger = LoggerFactory.getLogger(BackgroundService::class.java)

    @Scheduled(fixedDelayString = "\${collaborative.room-cleanup-interval-ms:30000}")
    fun cleanupInactiveRooms() {
        try {
            val activeFileIds = stringRedisTemplate.opsForSet().members(RedisKeys.ACTIVE_ROOMS) ?: return

            for (fileIdString in activeFileIds) {
                val fileId = UUID.fromString(fileIdString)

                // First cleanup stale user sessions
                cleanupStaleUserSessions(fileId)

                // Then check if file should be cleaned up
                val keys = RedisKeyBuilder(fileId)
                val userCount = stringRedisTemplate.opsForList().size(keys.userInfoKey()) ?: 0

                if (userCount == 0L) {
                    // File has no users, check last activity
                    val lastOp = stringRedisTemplate.opsForZSet()
                        .reverseRange(keys.opsIndexKey(), 0, 0)
                        ?.firstOrNull()

                    if (lastOp == null) {
                        // No ops and no users - cleanup file keys
                        cleanupFileKeys(fileId)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("File cleanup failed", e)
        }
    }

    private fun cleanupStaleUserSessions(fileId: UUID) {
        val staleThresholdMillis = Duration.ofMinutes(2).toMillis()
        val now = Instant.now()

        val users = collaborativeEditingService.getUserSessions(fileId)

        for (user in users) {
            val lastHeartbeat = user.lastHeartbeat
            if (lastHeartbeat == null || Duration.between(lastHeartbeat, now).toMillis() > staleThresholdMillis) {
                // Use notifyUserLeft to handle cleanup and broadcast
                documentEditorHub.notifyUserLeft(fileId, user.sessionId)
                logger.info("Removed stale user session: ${user.userName} from file: $fileId (heartbeat missed for 2 minutes)")
            }
        }
    }

    private fun cleanupFileKeys(fileId: UUID) {
        val keys = RedisKeyBuilder(fileId)
        val opsCount = stringRedisTemplate.opsForZSet().size(keys.opsIndexKey()) ?: 0

        if (opsCount == 0L) {
            val keysList = listOf(
                keys.opsHashKey(),
                keys.opsIndexKey(),
                keys.versionKey(),
                keys.persistedVersionKey(),
                keys.userInfoKey()
            )
            stringRedisTemplate.delete(keysList)
            stringRedisTemplate.opsForSet().remove(RedisKeys.ACTIVE_ROOMS, fileId.toString())
            logger.info("Cleaned up inactive file: $fileId")
        }
    }
}
