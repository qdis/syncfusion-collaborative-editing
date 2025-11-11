// ABOUTME: WebSocket hub for real-time document collaboration
// ABOUTME: Manages user join/leave events and broadcasts updates to room participants
package ai.apps.syncfusioncollaborativeediting.controller

import ai.apps.syncfusioncollaborativeediting.helper.CollaborativeEditingHelper
import ai.apps.syncfusioncollaborativeediting.model.UserSessionInfo
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.annotation.SendToUser
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Controller
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.security.Principal
import java.time.Instant
import java.util.*


@Controller
class DocumentEditorHub(
    private val messagingTemplate: SimpMessagingTemplate,
    private val stringRedisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(DocumentEditorHub::class.java)

    @MessageMapping("/init")
    @SendToUser("/queue/init")
    fun init(
        @Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) sessionId: String,
        @Header(name = "x-room-id", required = false) roomId: String?,
        principal: Optional<Principal>
    ): Message<Map<String, Any>> {

        logger.info("WebSocket connection initialized: $sessionId for room ID: $roomId")
        if (roomId == null) {
            // check room id
            logger.info("WebSocket connection established without room ID.")
            return MessageBuilder.createMessage(emptyMap(), MessageHeaders(mapOf()))
        }

        // Store session-to-room mapping using roomId directly
        stringRedisTemplate.opsForHash<String, String>().put("sessionIdToRoomIdMapping", sessionId, roomId)

        // Add user to presence list with timestamps
        val userInfoKey = roomId + CollaborativeEditingHelper.USER_INFO_SUFFIX
        val currentUser = principal.map { it.name }.orElse("Anonymous")
        val userSession = UserSessionInfo(
            userName = currentUser,
            userId = currentUser,
            sessionId = sessionId,
            lastHeartbeat = Instant.now(),
            lastAction = null,
            lastSave = null
        )
        val userJson = objectMapper.writeValueAsString(userSession)
        stringRedisTemplate.opsForList().rightPush(userInfoKey, userJson)

        // Track active room
        stringRedisTemplate.opsForSet().add("active_rooms", roomId)

        notifyUserJoined(roomId)

        val currentUsers = stringRedisTemplate.opsForList().range(userInfoKey, 0, -1) ?: emptyList()

        return MessageBuilder.createMessage(
            mapOf(
                "connectionId" to sessionId,
                "users" to currentUsers.map { objectMapper.readValue(it, UserSessionInfo::class.java) }
            ),
            MessageHeaders(mapOf("action" to "connectionId"))
        )

    }


    @EventListener
    fun handleConnectEvent(event: SessionConnectedEvent) {

        logger.info("WebSocket connection established: ${event.message.headers[SimpMessageHeaderAccessor.SESSION_ID_HEADER]}")
    }


    @EventListener
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        val sessionId = event.sessionId

        logger.info("WebSocket connection closed: $sessionId")

        // Get the roomId for the session
        val roomId = stringRedisTemplate.opsForHash<String, String>().get("sessionIdToRoomIdMapping", sessionId)
        if (roomId != null) {
            notifyUserLeft(roomId, sessionId)
        }
    }

    private fun notifyUserJoined(roomId: String) {
        // Get the list of users from Redis using consistent key pattern
        val userInfoKey = roomId + CollaborativeEditingHelper.USER_INFO_SUFFIX
        val userJsonStrings = stringRedisTemplate.opsForList().range(userInfoKey, 0, -1) ?: emptyList()
        val usersList = userJsonStrings.mapNotNull { userJson ->
            try {
                objectMapper.readValue(userJson, UserSessionInfo::class.java)
            } catch (e: Exception) {
                logger.error("Error parsing user information JSON", e)
                null
            }
        }

        val addUserHeaders = MessageHeaders(mapOf("action" to "addUser"))
        logger.info("Broadcasting user joined to room: $roomId with users: ${usersList.map { it.userName }}")
        broadcastToRoom(roomId, usersList, addUserHeaders)
    }

    private fun notifyUserLeft(roomId: String, sessionId: String) {
        val userInfoKey = roomId + CollaborativeEditingHelper.USER_INFO_SUFFIX
        val userJsonStrings = stringRedisTemplate.opsForList().range(userInfoKey, 0, -1) ?: emptyList()

        if (userJsonStrings.isNotEmpty()) {
            for (userJson in userJsonStrings) {
                try {
                    val userSession = objectMapper.readValue(userJson, UserSessionInfo::class.java)
                    if (userSession.sessionId == sessionId) {
                        // Remove the user from the user list
                        stringRedisTemplate.opsForList().remove(userInfoKey, 1, userJson)

                        // Fetch remaining users and broadcast full list
                        val remainingUserJsons = stringRedisTemplate.opsForList().range(userInfoKey, 0, -1) ?: emptyList()
                        val usersList = remainingUserJsons.map { json ->
                            objectMapper.readValue(json, UserSessionInfo::class.java)
                        }

                        val removeUserHeaders = MessageHeaders(mapOf("action" to "removeUser"))
                        broadcastToRoom(roomId, usersList, removeUserHeaders)

                        // Remove the session ID from the session-room mapping
                        stringRedisTemplate.opsForHash<String, String>().delete("sessionIdToRoomIdMapping", sessionId)
                        break
                    }
                } catch (e: Exception) {
                    logger.error("Error processing user disconnect", e)
                }
            }

            // If no users left, delete the user_info key and remove from active_rooms
            val remainingUsers = stringRedisTemplate.opsForList().size(userInfoKey) ?: 0
            if (remainingUsers == 0L) {
                stringRedisTemplate.delete(userInfoKey)
                stringRedisTemplate.opsForSet().remove("active_rooms", roomId)
                logger.debug("Room $roomId is now inactive (no users)")
            }
        }
    }

    fun updateUserTimestamps(
        roomId: String,
        userName: String,
        updateLastHeartbeat: Boolean = false,
        updateLastAction: Boolean = false,
        updateLastSave: Boolean = false
    ) {
        val userInfoKey = roomId + CollaborativeEditingHelper.USER_INFO_SUFFIX
        val userJsonStrings = stringRedisTemplate.opsForList().range(userInfoKey, 0, -1) ?: emptyList()

        for (userJson in userJsonStrings) {
            try {
                val userSession = objectMapper.readValue(userJson, UserSessionInfo::class.java)
                if (userSession.userName == userName) {
                    val now = Instant.now()
                    val updatedSession = userSession.copy(
                        lastHeartbeat = if (updateLastHeartbeat) now else userSession.lastHeartbeat,
                        lastAction = if (updateLastAction) now else userSession.lastAction,
                        lastSave = if (updateLastSave) now else userSession.lastSave
                    )

                    val updatedJson = objectMapper.writeValueAsString(updatedSession)

                    // Remove old entry and add updated entry
                    stringRedisTemplate.opsForList().remove(userInfoKey, 1, userJson)
                    stringRedisTemplate.opsForList().rightPush(userInfoKey, updatedJson)

                    logger.debug("Updated timestamps for user $userName in room $roomId")
                    break
                }
            } catch (e: Exception) {
                logger.error("Error updating user timestamps", e)
            }
        }
    }

    fun broadcastToRoom(roomName: String, payload: Any, headers: MessageHeaders) {
        messagingTemplate.convertAndSend(
            "/topic/public/$roomName",
            MessageBuilder.createMessage(payload, headers)
        )
    }
}
