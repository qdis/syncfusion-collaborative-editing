// ABOUTME: Background service for periodically saving collaborative edits to storage
// ABOUTME: Processes queued operations and persists them to MinIO every 5 seconds
package ai.apps.syncfusioncollaborativeediting.service

import ai.apps.syncfusioncollaborativeediting.helper.CollaborativeEditingHelper
import ai.apps.syncfusioncollaborativeediting.model.SaveInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.syncfusion.docio.FormatType
import com.syncfusion.ej2.wordprocessor.ActionInfo
import com.syncfusion.ej2.wordprocessor.CollaborativeEditingHandler
import com.syncfusion.ej2.wordprocessor.WordProcessorHelper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.Semaphore

@Service
class BackgroundService(
    private val minioService: MinioService,
    private val stringRedisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper // <— add
) {

    private val logger = LoggerFactory.getLogger(BackgroundService::class.java)
    private val semaphore = Semaphore(1)


    // New: run on a configurable cadence (default 5s)
    @Scheduled(fixedDelayString = "\${collaborative.autosave-interval-ms:5000}")
    fun periodicAutosave() {
        try {
            semaphore.acquire()

            // Find all rooms that currently have an ops list.
            // Note: KEYS is O(N). See “Caveats” below for SCAN/room index.
            val opsKeys = stringRedisTemplate
                .keys("*${CollaborativeEditingHelper.OPS_SUFFIX}")
                .orEmpty()

            for (opsKey in opsKeys) {
                val roomName = opsKey.removeSuffix(CollaborativeEditingHelper.OPS_SUFFIX)
                val actions = fetchUnpersistedActions(roomName)
                if (actions.isEmpty()) continue

                logger.info("Autosave tick: room=$roomName ops=${actions.size}")
                // Apply and persist
                applyOperationsToSourceDocument(SaveInfo(roomName = roomName, actions = actions, partialSave = true))
                // Advance persisted version and clear to_remove
                clearRecordsFromRedisCache(SaveInfo(roomName = roomName, actions = actions, partialSave = true))
            }
        } finally {
            semaphore.release()
        }
    }

    // Collect all actions with version > persisted_version from both queues.
    private fun fetchUnpersistedActions(roomName: String): List<ActionInfo> {
        val persistedKey = roomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX
        val toRemoveKey = roomName + CollaborativeEditingHelper.TO_REMOVE_SUFFIX
        val opsKey = roomName + CollaborativeEditingHelper.OPS_SUFFIX

        val persistedVersion = stringRedisTemplate.opsForValue()
            .get(persistedKey)?.toIntOrNull() ?: 0

        val fromToRemove = stringRedisTemplate.opsForList()
            .range(toRemoveKey, 0, -1).orEmpty()
            .map { objectMapper.readValue(it, ActionInfo::class.java) }
            .filter { it.version > persistedVersion }

        val fromOps = stringRedisTemplate.opsForList()
            .range(opsKey, 0, -1).orEmpty()
            .map { objectMapper.readValue(it, ActionInfo::class.java) }
            .filter { it.version > persistedVersion }

        return buildList {
            addAll(fromToRemove)
            addAll(fromOps)
        }
    }

    // keep existing applyOperationsToSourceDocument(...) and clearRecordsFromRedisCache(...)


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
            val fileName = Base64.getDecoder().decode(workItem.roomName).toString(StandardCharsets.UTF_8)

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
                } catch (e: Exception) {
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

    // BackgroundService.kt
    private fun clearRecordsFromRedisCache(workItem: SaveInfo) {
        val partialSave = workItem.partialSave
        val roomName = workItem.roomName
        val actions = workItem.actions ?: emptyList()

        try {
            // Highest version that was just saved to MinIO
            val highestVersion = actions.maxOfOrNull { it.version } ?: 0

            val persistedVersionKey = roomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX
            val opsKey = roomName + CollaborativeEditingHelper.OPS_SUFFIX
            val startKey = roomName + CollaborativeEditingHelper.START_SUFFIX
            val toRemoveKey = roomName + CollaborativeEditingHelper.TO_REMOVE_SUFFIX

            if (!partialSave) {
                // Full save: set persisted, clear live window
                stringRedisTemplate.opsForValue().set(persistedVersionKey, highestVersion.toString())
                stringRedisTemplate.delete(opsKey)
                stringRedisTemplate.delete(startKey)
            } else {
                // Partial save: bump persisted_version if advanced
                val currentPersisted = stringRedisTemplate.opsForValue()
                    .get(persistedVersionKey)?.toIntOrNull() ?: 0
                val newPersisted = maxOf(currentPersisted, highestVersion)
                if (newPersisted > currentPersisted) {
                    stringRedisTemplate.opsForValue().set(persistedVersionKey, newPersisted.toString())
                }

                // Trim ops ≤ persisted_version and advance :start accordingly
                val start = stringRedisTemplate.opsForValue().get(startKey)?.toIntOrNull() ?: 1
                val len = stringRedisTemplate.opsForList().size(opsKey)?.toInt() ?: 0
                val toTrim = maxOf(0, minOf(len, newPersisted - start + 1))
                if (toTrim > 0) {
                    stringRedisTemplate.opsForList().trim(opsKey, toTrim.toLong(), -1)
                    stringRedisTemplate.opsForValue().set(startKey, (start + toTrim).toString())
                }
            }

            // Clear the spillover queue after any successful save
            stringRedisTemplate.delete(toRemoveKey)

            logger.info(
                "Cleared Redis cache for room: $roomName " +
                        "(partialSave=$partialSave, highestVersion=$highestVersion)"
            )
        } catch (e: Exception) {
            logger.error("Error clearing Redis cache", e)
        }
    }

}
