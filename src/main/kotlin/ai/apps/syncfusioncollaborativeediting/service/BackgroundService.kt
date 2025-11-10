// ABOUTME: Background service for periodically saving collaborative edits to storage
// ABOUTME: Processes queued operations and persists them to MinIO every 5 seconds
package ai.apps.syncfusioncollaborativeediting.service

import ai.apps.syncfusioncollaborativeediting.helper.CollaborativeEditingHelper
import ai.apps.syncfusioncollaborativeediting.model.SaveInfo
import com.syncfusion.docio.FormatType
import com.syncfusion.ej2.wordprocessor.CollaborativeEditingHandler
import com.syncfusion.ej2.wordprocessor.WordProcessorHelper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Semaphore

@Service
class BackgroundService(
    private val minioService: MinioService,
    private val stringRedisTemplate: StringRedisTemplate
) {

    private val logger = LoggerFactory.getLogger(BackgroundService::class.java)
    private val itemsToProcess = mutableListOf<SaveInfo>()
    private val semaphore = Semaphore(1)


    @Scheduled(fixedRate = 5000) // Runs every 5 seconds
    fun runBackgroundTask() {
        try {
            semaphore.acquire()
            synchronized(itemsToProcess) {
                while (itemsToProcess.isNotEmpty()) {
                    val item = itemsToProcess.removeAt(0)
                    logger.info("Processing background save for room: ${item.roomName}")
                    try {
                        applyOperationsToSourceDocument(item)
                        clearRecordsFromRedisCache(item)
                    } catch (e: Exception) {
                        logger.error("Error processing save item", e)
                    }
                }
            }
        } catch (e: InterruptedException) {
            logger.error("Background task interrupted", e)
        } finally {
            semaphore.release()
        }
    }

    fun addItemToProcess(item: SaveInfo) {
        synchronized(itemsToProcess) {
            itemsToProcess.add(item)
            logger.info("Added item to process queue. Queue size: ${itemsToProcess.size}")
        }
    }

    private fun applyOperationsToSourceDocument(workItem: SaveInfo) {
        try {
            val actions = workItem.actions ?: return
            if (actions.isEmpty()) return

            // Transform any untransformed actions
            actions.forEach { action ->
                if (!action.isTransformed) {
                    CollaborativeEditingHandler.transformOperation(action, ArrayList(actions))
                }
            }

            // Get the document name (in a real implementation, this should be tracked per room)
            val fileName = Base64.getDecoder().decode( workItem.roomName).toString(StandardCharsets.UTF_8)

            logger.info("Applying ${actions.size} operations to document: $fileName in room: ${workItem.roomName}")
            // Load the document from MinIO
            val documentData = minioService.downloadDocument(fileName)
            val document = ByteArrayInputStream(documentData.readAllBytes()).use { stream ->
                WordProcessorHelper.load(stream, true)
            }

            // Apply all actions to the document
            val handler = CollaborativeEditingHandler(document)
            actions.forEach { info ->
                try {
                    handler.updateAction(info)
                }catch (e: Exception){
                    logger.error("Error applying action to document: $fileName", e)
                }
            }

            // Save the updated document
            val outputStream = ByteArrayOutputStream()
            val doc = WordProcessorHelper.save(WordProcessorHelper.serialize(handler.document))
            doc.save(outputStream, FormatType.Docx)

            val data = outputStream.toByteArray()

            // Upload the updated document back to MinIO
            minioService.uploadDocument(fileName, data)

            logger.info("Successfully saved ${actions.size} operations to document: $fileName")

            outputStream.close()
        } catch (e: Exception) {
            logger.error("Error applying operations to source document", e)
            throw e
        }
    }

    private fun clearRecordsFromRedisCache(workItem: SaveInfo) {
        val partialSave = workItem.partialSave
        val roomName = workItem.roomName

        try {
            if (!partialSave) {
                // Full save - clear all room-related keys
                stringRedisTemplate.delete(roomName)
                stringRedisTemplate.delete(roomName + CollaborativeEditingHelper.REVISION_INFO_SUFFIX)
                stringRedisTemplate.delete(roomName + CollaborativeEditingHelper.VERSION_INFO_SUFFIX)
            }
            // Always clear the actions to remove queue
            stringRedisTemplate.delete(roomName + CollaborativeEditingHelper.ACTIONS_TO_REMOVE_SUFFIX)

            logger.info("Cleared Redis cache for room: $roomName (partialSave: $partialSave)")
        } catch (e: Exception) {
            logger.error("Error clearing Redis cache", e)
        }
    }
}
