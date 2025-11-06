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
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Controller
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.security.Principal

@Controller
class DocumentEditorHub(
    private val messagingTemplate: SimpMessagingTemplate,
    private val stringRedisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(DocumentEditorHub::class.java)

    @Value("\${collaborative.redis-pub-sub-channel}")
    private lateinit var pubSubChannel: String

    @MessageMapping("/join/{documentName}")
    fun joinGroup(
        info: ActionInfo,
        headerAccessor: SimpMessageHeaderAccessor,
        @DestinationVariable documentName: String,
        principal: Principal
    ) {
        // Get the connection ID and authenticated username
        val connectionId = headerAccessor.sessionId ?: return
        val username = principal.name

        info.connectionId = connectionId
        info.currentUser = username
        val docName = info.roomName

        val additionalHeaders = mapOf("action" to "connectionId")
        val headers = MessageHeaders(additionalHeaders)

        // Send the connection ID to the client
        broadcastToRoom(docName, info, headers)

        // Maintain the session ID with its corresponding document name
        stringRedisTemplate.opsForHash<String, String>().put("documentMap", connectionId, documentName)

        // Add the user details to the Redis cache
        val openedDocName = docName + CollaborativeEditingHelper.USER_INFO_SUFFIX
        stringRedisTemplate.opsForList().rightPush(openedDocName, objectMapper.writeValueAsString(info))

        // Broadcast user joined event
        notifyUserJoined(openedDocName)
    }

    @EventListener
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        val sessionId = event.sessionId ?: return

        // Get the document name for the session
        val docName = stringRedisTemplate.opsForHash<String, String>().get("documentMap", sessionId)
        if (docName != null) {
            val openedDocName = docName + CollaborativeEditingHelper.USER_INFO_SUFFIX
            notifyUserLeft(openedDocName, sessionId)
        }
    }

    private fun notifyUserJoined(docName: String) {
        // Get the list of users from Redis
        val userJsonStrings = stringRedisTemplate.opsForList().range(docName, 0, -1) ?: emptyList()
        val actionsList = userJsonStrings.mapNotNull { userJson ->
            try {
                objectMapper.readValue(userJson, ActionInfo::class.java)
            } catch (e: Exception) {
                logger.error("Error parsing user information JSON", e)
                null
            }
        }

        val addUserHeaders = MessageHeaders(mapOf("action" to "addUser"))
        broadcastToRoom(docName, actionsList, addUserHeaders)
    }

    private fun notifyUserLeft(docName: String, sessionId: String) {
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
                        broadcastToRoom(docName, action, removeUserHeaders)

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
