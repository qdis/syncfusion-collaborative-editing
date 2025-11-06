// ABOUTME: Redis pub/sub subscriber for cross-server collaborative editing synchronization
// ABOUTME: Receives action updates from Redis and broadcasts them to WebSocket clients
package ai.apps.syncfusioncollaborativeediting.service

import ai.apps.syncfusioncollaborativeediting.controller.DocumentEditorHub
import com.fasterxml.jackson.databind.ObjectMapper
import com.syncfusion.ej2.wordprocessor.ActionInfo
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.messaging.MessageHeaders
import org.springframework.stereotype.Component

@Component
class RedisSubscriber(
    private val redisConnectionFactory: RedisConnectionFactory,
    private val documentEditorHub: DocumentEditorHub,
    private val objectMapper: ObjectMapper
) : MessageListener {

    private val logger = LoggerFactory.getLogger(RedisSubscriber::class.java)

    @Value("\${collaborative.redis-pub-sub-channel}")
    private lateinit var channel: String

    @PostConstruct
    fun subscribeToChannel() {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(redisConnectionFactory)
        container.addMessageListener(this, ChannelTopic(channel))
        container.afterPropertiesSet()
        container.start()

        logger.info("Subscribed to Redis channel: {}", channel)
    }

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val messageBody = String(message.body)
            logger.debug("Received message from channel {}: {}", String(message.channel), messageBody)

            val action = objectMapper.readValue(messageBody, ActionInfo::class.java)
            val updateAction = mapOf("action" to "updateAction")
            val headers = MessageHeaders(updateAction)

            documentEditorHub.broadcastToRoom(action.roomName, action, headers)
        } catch (e: Exception) {
            logger.error("Error processing Redis message", e)
        }
    }
}
