// ABOUTME: Background service for cleanup of inactive room keys and stale user sessions
// ABOUTME: Removes Redis keys for rooms with no users and removes stale user sessions
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
            val activeRooms = stringRedisTemplate.opsForSet().members(RedisKeys.ACTIVE_ROOMS) ?: return

            for (roomName in activeRooms) {
                // First cleanup stale user sessions
                cleanupStaleUserSessions(roomName)

                // Then check if room should be cleaned up
                val keys = RedisKeyBuilder(roomName)
                val userCount = stringRedisTemplate.opsForList().size(keys.userInfoKey()) ?: 0

                if (userCount == 0L) {
                    // Room has no users, check last activity
                    val lastOp = stringRedisTemplate.opsForZSet()
                        .reverseRange(keys.opsIndexKey(), 0, 0)
                        ?.firstOrNull()

                    if (lastOp == null) {
                        // No ops and no users - cleanup room keys
                        cleanupRoomKeys(roomName)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Room cleanup failed", e)
        }
    }

    private fun cleanupStaleUserSessions(roomName: String) {
        val staleThresholdMillis = Duration.ofMinutes(2).toMillis()
        val now = Instant.now()

        val users = collaborativeEditingService.getUserSessions(roomName)

        for (user in users) {
            val lastHeartbeat = user.lastHeartbeat
            if (lastHeartbeat == null || Duration.between(lastHeartbeat, now).toMillis() > staleThresholdMillis) {
                // Use notifyUserLeft to handle cleanup and broadcast
                documentEditorHub.notifyUserLeft(roomName, user.sessionId)
                logger.info("Removed stale user session: ${user.userName} from room: $roomName (heartbeat missed for 2 minutes)")
            }
        }
    }

    private fun cleanupRoomKeys(roomName: String) {
        val keys = RedisKeyBuilder(roomName)
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
            stringRedisTemplate.opsForSet().remove(RedisKeys.ACTIVE_ROOMS, roomName)
            logger.info("Cleaned up inactive room: $roomName")
        }
    }
}
