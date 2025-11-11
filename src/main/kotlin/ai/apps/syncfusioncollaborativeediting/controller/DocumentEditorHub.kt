// ABOUTME: WebSocket hub for real-time document collaboration
// ABOUTME: Manages WebSocket connections, broadcasts updates, delegates session logic to service
package ai.apps.syncfusioncollaborativeediting.controller

import ai.apps.syncfusioncollaborativeediting.constant.RedisKeys
import ai.apps.syncfusioncollaborativeediting.model.ConnectionInitResponse
import ai.apps.syncfusioncollaborativeediting.service.CollaborativeEditingService
import org.slf4j.LoggerFactory
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
import java.util.*

@Controller
class DocumentEditorHub(
    private val messagingTemplate: SimpMessagingTemplate,
    private val stringRedisTemplate: StringRedisTemplate,
    private val collaborativeEditingService: CollaborativeEditingService
) {

    private val logger = LoggerFactory.getLogger(DocumentEditorHub::class.java)

    @MessageMapping("/init")
    @SendToUser("/queue/init")
    fun init(
        @Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) sessionId: String,
        @Header(name = "x-room-id", required = false) roomId: String?,
        principal: Optional<Principal>
    ): Message<ConnectionInitResponse> {

        logger.info("WebSocket connection initialized: $sessionId for room ID: $roomId")
        if (roomId == null) {
            logger.info("WebSocket connection established without room ID.")
            return MessageBuilder.createMessage(
                ConnectionInitResponse(connectionId = sessionId, users = emptyList()),
                MessageHeaders(mapOf())
            )
        }

        // Store session-to-room mapping
        stringRedisTemplate.opsForHash<String, String>().put(
            RedisKeys.SESSION_TO_ROOM_MAPPING,
            sessionId,
            roomId
        )

        // Add user session
        val currentUser = principal.map { it.name }.orElse("Anonymous")
        collaborativeEditingService.addUserSession(roomId, sessionId, currentUser)

        notifyUserJoined(roomId)

        val currentUsers = collaborativeEditingService.getUserSessions(roomId)

        return MessageBuilder.createMessage(
            ConnectionInitResponse(
                connectionId = sessionId,
                users = currentUsers
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
        val roomId = stringRedisTemplate.opsForHash<String, String>().get(
            RedisKeys.SESSION_TO_ROOM_MAPPING,
            sessionId
        )
        if (roomId != null) {
            notifyUserLeft(roomId, sessionId)
        }
    }

    private fun notifyUserJoined(roomId: String) {
        val usersList = collaborativeEditingService.getUserSessions(roomId)
        val addUserHeaders = MessageHeaders(mapOf("action" to "addUser"))
        logger.info("Broadcasting user joined to room: $roomId with users: ${usersList.map { it.userName }}")
        broadcastToRoom(roomId, usersList, addUserHeaders)
    }

    fun notifyUserLeft(roomId: String, sessionId: String) {
        val removed = collaborativeEditingService.removeUserSession(roomId, sessionId)

        if (removed) {
            // Fetch remaining users and broadcast
            val removeUserHeaders = MessageHeaders(mapOf("action" to "removeUser"))
            broadcastToRoom(roomId, mapOf("connectionId" to sessionId), removeUserHeaders)

            // Remove session-to-room mapping
            stringRedisTemplate.opsForHash<String, String>().delete(
                RedisKeys.SESSION_TO_ROOM_MAPPING,
                sessionId
            )
        }
    }

    fun broadcastToRoom(roomName: String, payload: Any, headers: MessageHeaders) {
        messagingTemplate.convertAndSend(
            "/topic/public/$roomName",
            MessageBuilder.createMessage(payload, headers)
        )
    }
}
