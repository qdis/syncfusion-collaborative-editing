// ABOUTME: WebSocket hub for real-time document collaboration
// ABOUTME: Manages user join/leave events and broadcasts updates to room participants
package ai.apps.syncfusioncollaborativeediting.controller

import ai.apps.syncfusioncollaborativeediting.helper.CollaborativeEditingHelper
import com.fasterxml.jackson.databind.ObjectMapper
import com.syncfusion.ej2.wordprocessor.ActionInfo
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
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.stereotype.Controller
import org.springframework.web.socket.messaging.SessionConnectEvent
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.util.*


@Controller
class DocumentEditorHub(
    private val messagingTemplate: SimpMessagingTemplate,
    private val stringRedisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(DocumentEditorHub::class.java)

    @Value("\${collaborative.redis-pub-sub-channel}")
    private lateinit var pubSubChannel: String


    @MessageMapping("/init")
    @SendToUser("/queue/init")
    fun init(
        @Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) sessionId: String,
        @Header(name = "x-room-id", required = false) roomId: String?
    ): Message<Map<String, String>> {

        logger.info("WebSocket connection initialized: $sessionId for room ID: $roomId")
        if (roomId == null) {
            // check room id
            logger.info("WebSocket connection established without room ID.")
            return MessageBuilder.createMessage(emptyMap(), MessageHeaders(mapOf()))
        }
        val documentName = String(Base64.getDecoder().decode(roomId))
        stringRedisTemplate.opsForHash<String, String>().put("documentMap", sessionId, documentName)
        notifyUserJoined(roomId)

        return MessageBuilder.createMessage(mapOf("connectionId" to sessionId), MessageHeaders(mapOf("action" to "connectionId")))

    }


    @EventListener
    fun handleConnectEvent(event: SessionConnectedEvent) {

        logger.info("WebSocket connection established: ${event.message.headers[SimpMessageHeaderAccessor.SESSION_ID_HEADER]}")
    }


    @EventListener
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        val sessionId = event.sessionId

        logger.info("WebSocket connection closed: $sessionId")

        // Get the document name for the session
        val docName = stringRedisTemplate.opsForHash<String, String>().get("documentMap", sessionId)
        if (docName != null) {
            val openedDocName = docName + CollaborativeEditingHelper.USER_INFO_SUFFIX
            notifyUserLeft(docName, openedDocName, sessionId)
        }
    }

    private fun notifyUserJoined(roomId: String) {
        // Get the list of users from Redis
        val userJsonStrings = stringRedisTemplate.opsForList().range(roomId, 0, -1) ?: emptyList()
        val actionsList = userJsonStrings.mapNotNull { userJson ->
            try {
                objectMapper.readValue(userJson, ActionInfo::class.java)
            } catch (e: Exception) {
                logger.error("Error parsing user information JSON", e)
                null
            }
        }

        val addUserHeaders = MessageHeaders(mapOf("action" to "addUser"))
        logger.info("Broadcasting user joined to room: $roomId with users: ${actionsList.map { it.currentUser }}")
        broadcastToRoom(roomId, actionsList, addUserHeaders)
    }

    private fun notifyUserLeft(roomName: String, docName: String, sessionId: String) {
        // Get the user list from Redis
        val userJsonStrings = stringRedisTemplate.opsForList().range(docName, 0, -1) ?: emptyList()

        if (userJsonStrings.isNotEmpty()) {
            for (userJson in userJsonStrings) {
                try {
                    val action = objectMapper.readValue(userJson, ActionInfo::class.java)
                    if (action.connectionId == sessionId) {
                        // Remove the user from the user list
                        stringRedisTemplate.opsForList().remove(docName, 1, userJson)

                        val removeUserHeaders = MessageHeaders(mapOf("action" to "removeUser"))
                        broadcastToRoom(roomName, action, removeUserHeaders)

                        // Remove the session ID from the session-document mapping
                        stringRedisTemplate.opsForHash<String, String>().delete("documentMap", sessionId)
                        break
                    }
                } catch (e: Exception) {
                    logger.error("Error processing user disconnect", e)
                }
            }

            // If no users left, delete the document key
            val remainingUsers = stringRedisTemplate.opsForList().size(docName) ?: 0
            if (remainingUsers == 0L) {
                stringRedisTemplate.delete(docName)
            }
        }
    }

    fun broadcastToRoom(roomName: String, payload: Any, headers: MessageHeaders) {
        messagingTemplate.convertAndSend(
            "/topic/public/$roomName",
            MessageBuilder.createMessage(payload, headers)
        )
    }

    fun publishToRedis(roomName: String, payload: Any) {
        try {
            val message = objectMapper.writeValueAsString(payload)
            stringRedisTemplate.convertAndSend(pubSubChannel, message)
            logger.debug("Message published to Redis: {}", message)
        } catch (e: Exception) {
            logger.error("Error publishing to Redis", e)
        }
    }
}
