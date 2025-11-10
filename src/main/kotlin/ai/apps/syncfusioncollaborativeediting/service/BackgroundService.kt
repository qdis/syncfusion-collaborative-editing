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

            // Filter out already-persisted actions for idempotency
            val persistedKey = workItem.roomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX
            val persistedVersion = stringRedisTemplate.opsForValue().get(persistedKey)?.toIntOrNull() ?: 0
            val toApply = actions.filter { it.version > persistedVersion }

            if (toApply.isEmpty()) {
                logger.info("All actions already persisted for room: ${workItem.roomName}, skipping")
                return
            }

            // Transform only untransformed actions
            val actionsArrayList = ArrayList(toApply)
            toApply.forEach { action ->
                if (!action.isTransformed) {
                    CollaborativeEditingHandler.transformOperation(action, actionsArrayList)
                }
            }

            // Get the document name (in a real implementation, this should be tracked per room)
            val fileName = Base64.getDecoder().decode( workItem.roomName).toString(StandardCharsets.UTF_8)

            logger.info("Applying ${toApply.size} operations to document: $fileName in room: ${workItem.roomName} (filtered ${actions.size - toApply.size} already-persisted)")
            // Load the document from MinIO
            val documentData = minioService.downloadDocument(fileName)
            val document = ByteArrayInputStream(documentData.readAllBytes()).use { stream ->
                WordProcessorHelper.load(stream, true)
            }

            // Apply all actions to the document
            val handler = CollaborativeEditingHandler(document)
            toApply.forEach { info ->
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

            logger.info("Successfully saved ${toApply.size} operations to document: $fileName")

            outputStream.close()
        } catch (e: Exception) {
            logger.error("Error applying operations to source document", e)
            throw e
        }
    }

    private fun clearRecordsFromRedisCache(workItem: SaveInfo) {
        val partialSave = workItem.partialSave
        val roomName = workItem.roomName
        val actions = workItem.actions ?: emptyList()

        try {
            // Calculate highest saved version (assumes actions have version field set)
            val highestVersion = actions.maxOfOrNull { it.version } ?: 0

            if (!partialSave) {
                // Full save - set persisted version and clear operation keys
                val persistedVersionKey = roomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX
                stringRedisTemplate.opsForValue().set(persistedVersionKey, highestVersion.toString())

                val opsKey = roomName + CollaborativeEditingHelper.OPS_SUFFIX
                val startKey = roomName + CollaborativeEditingHelper.START_SUFFIX

                stringRedisTemplate.delete(opsKey)
                stringRedisTemplate.delete(startKey)
            } else {
                // Partial save - update persisted version only
                val persistedVersionKey = roomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX
                val currentPersisted = stringRedisTemplate.opsForValue().get(persistedVersionKey)?.toIntOrNull() ?: 0
                if (highestVersion > currentPersisted) {
                    stringRedisTemplate.opsForValue().set(persistedVersionKey, highestVersion.toString())
                }
            }

            // Always clear the to_remove queue after successful save
            val toRemoveKey = roomName + CollaborativeEditingHelper.TO_REMOVE_SUFFIX
            stringRedisTemplate.delete(toRemoveKey)

            logger.info("Cleared Redis cache for room: $roomName (partialSave: $partialSave, highestVersion: $highestVersion)")
        } catch (e: Exception) {
            logger.error("Error clearing Redis cache", e)
        }
    }
}
