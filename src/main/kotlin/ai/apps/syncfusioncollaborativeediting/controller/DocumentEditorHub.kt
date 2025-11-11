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
        @Header(name = "x-file-id", required = false) fileIdString: String?,
        principal: Optional<Principal>
    ): Message<ConnectionInitResponse> {

        logger.info("WebSocket connection initialized: $sessionId for file ID: $fileIdString")
        if (fileIdString == null) {
            logger.info("WebSocket connection established without file ID.")
            return MessageBuilder.createMessage(
                ConnectionInitResponse(connectionId = sessionId, users = emptyList()),
                MessageHeaders(mapOf())
            )
        }

        val fileId = UUID.fromString(fileIdString)

        // Store session-to-file mapping
        stringRedisTemplate.opsForHash<String, String>().put(
            RedisKeys.SESSION_TO_ROOM_MAPPING,
            sessionId,
            fileIdString
        )

        // Add user session
        val currentUser = principal.map { it.name }.orElse("Anonymous")
        collaborativeEditingService.addUserSession(fileId, sessionId, currentUser)

        notifyUserJoined(fileId)

        val currentUsers = collaborativeEditingService.getUserSessions(fileId)

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

        // Get the fileId for the session
        val fileIdString = stringRedisTemplate.opsForHash<String, String>().get(
            RedisKeys.SESSION_TO_ROOM_MAPPING,
            sessionId
        )
        if (fileIdString != null) {
            val fileId = UUID.fromString(fileIdString)
            notifyUserLeft(fileId, sessionId)
        }
    }

    private fun notifyUserJoined(fileId: UUID) {
        val usersList = collaborativeEditingService.getUserSessions(fileId)
        val addUserHeaders = MessageHeaders(mapOf("action" to "addUser"))
        logger.info("Broadcasting user joined to file: $fileId with users: ${usersList.map { it.userName }}")
        broadcastToRoom(fileId, usersList, addUserHeaders)
    }

    fun notifyUserLeft(fileId: UUID, sessionId: String) {
        val removed = collaborativeEditingService.removeUserSession(fileId, sessionId)

        if (removed) {
            // Fetch remaining users and broadcast
            val removeUserHeaders = MessageHeaders(mapOf("action" to "removeUser"))
            broadcastToRoom(fileId, mapOf("connectionId" to sessionId), removeUserHeaders)

            // Remove session-to-file mapping
            stringRedisTemplate.opsForHash<String, String>().delete(
                RedisKeys.SESSION_TO_ROOM_MAPPING,
                sessionId
            )
        }
    }

    fun broadcastToRoom(fileId: UUID, payload: Any, headers: MessageHeaders) {
        messagingTemplate.convertAndSend(
            "/topic/public/$fileId",
            MessageBuilder.createMessage(payload, headers)
        )
    }
}
