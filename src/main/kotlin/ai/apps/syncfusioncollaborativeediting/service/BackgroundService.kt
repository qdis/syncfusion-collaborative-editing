// ABOUTME: Background service for periodically saving collaborative edits to storage
// ABOUTME: Uses snapshot + CAS pattern for non-blocking autosave to MinIO
package ai.apps.syncfusioncollaborativeediting.service

import ai.apps.syncfusioncollaborativeediting.helper.CollaborativeEditingHelper
import com.fasterxml.jackson.databind.ObjectMapper
import com.syncfusion.docio.FormatType
import com.syncfusion.ej2.wordprocessor.ActionInfo
import com.syncfusion.ej2.wordprocessor.CollaborativeEditingHandler
import com.syncfusion.ej2.wordprocessor.WordProcessorHelper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
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
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(BackgroundService::class.java)
    private val semaphore = Semaphore(1)

    @Scheduled(fixedDelayString = "\${collaborative.autosave-interval-ms:3000}")
    fun periodicAutosave() {
        try {
            semaphore.acquire()

            val dirtyRooms = stringRedisTemplate.opsForSet().members("dirty_rooms") ?: emptySet()

            for (roomName in dirtyRooms) {
                try {
                    processRoomAutosave(roomName)
                } catch (e: Exception) {
                    logger.error("Autosave failed for $roomName", e)
                }
            }
        } finally {
            semaphore.release()
        }
    }

    private fun processRoomAutosave(roomName: String) {
        val opsIndexKey = roomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX
        val opsHashKey = roomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX
        val persistedKey = roomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

        // Phase 1: Snapshot (no lock, fast)
        val snapshotScript = DefaultRedisScript<List<Any>>()
        snapshotScript.setScriptText(CollaborativeEditingHelper.AUTOSAVE_SNAPSHOT_SCRIPT)
        snapshotScript.resultType = List::class.java as Class<List<Any>>

        val snapshot = stringRedisTemplate.execute(
            snapshotScript,
            listOf(opsIndexKey, persistedKey),
            "1000"
        ) ?: return

        val persistedVersion = (snapshot[0] as Number).toInt()
        val versions = (snapshot[1] as List<*>).map { it.toString() }

        if (versions.isEmpty()) {
            // No pending ops, remove from dirty set
            stringRedisTemplate.opsForSet().remove("dirty_rooms", roomName)
            return
        }

        // Fetch payloads
        val payloads = stringRedisTemplate.opsForHash<String, String>()
            .multiGet(opsHashKey, versions)
            .filterNotNull()
            .filter { it != "__PENDING__" }

        if (payloads.isEmpty()) {
            logger.debug("All ops still pending for $roomName")
            return
        }

        val actions = payloads.map { objectMapper.readValue(it, ActionInfo::class.java) }
        val highestVersion = actions.maxOf { it.version }

        logger.info("Autosaving ${actions.size} ops for $roomName (${persistedVersion + 1} to $highestVersion)")

        // Phase 2: Apply to MinIO (no Redis locks held)
        val fileName = String(Base64.getDecoder().decode(roomName), StandardCharsets.UTF_8)
        val success = applyOperationsToMinIO(fileName, actions)

        if (!success) {
            logger.error("MinIO write failed for $roomName, will retry next cycle")
            return
        }

        // Phase 3: Cleanup with CAS
        val cleanupScript = DefaultRedisScript<Long>()
        cleanupScript.setScriptText(CollaborativeEditingHelper.AUTOSAVE_CLEANUP_SCRIPT)
        cleanupScript.resultType = Long::class.java

        val finalPersisted = stringRedisTemplate.execute(
            cleanupScript,
            listOf(opsHashKey, opsIndexKey, persistedKey),
            highestVersion.toString(),
            persistedVersion.toString()
        ) ?: persistedVersion.toLong()

        logger.info("Advanced persisted_version to $finalPersisted for $roomName")

        // Check if room is clean
        val remainingOps = stringRedisTemplate.opsForZSet().size(opsIndexKey) ?: 0L
        if (remainingOps == 0L) {
            stringRedisTemplate.opsForSet().remove("dirty_rooms", roomName)
            logger.debug("Room $roomName is now clean")
        }
    }

    private fun applyOperationsToMinIO(fileName: String, actions: List<ActionInfo>): Boolean {
        return try {
            // Transform untransformed operations
            val actionsArrayList = ArrayList(actions)
            actions.forEach { action ->
                if (!action.isTransformed) {
                    CollaborativeEditingHandler.transformOperation(action, actionsArrayList)
                }
            }

            // Load document from MinIO
            val documentData = minioService.downloadDocument(fileName)
            val document = ByteArrayInputStream(documentData.readAllBytes()).use { stream ->
                WordProcessorHelper.load(stream, true)
            }

            // Apply operations
            val handler = CollaborativeEditingHandler(document)
            actions.forEach { info ->
                try {
                    handler.updateAction(info)
                } catch (e: Exception) {
                    logger.error("Error applying action version ${info.version} to document: $fileName", e)
                    throw e
                }
            }

            // Save document
            val outputStream = ByteArrayOutputStream()
            val doc = WordProcessorHelper.save(WordProcessorHelper.serialize(handler.document))
            doc.save(outputStream, FormatType.Docx)

            val data = outputStream.toByteArray()
            minioService.uploadDocument(fileName, data)

            outputStream.close()

            logger.info("Successfully persisted ${actions.size} operations to $fileName")
            true
        } catch (e: Exception) {
            logger.error("MinIO operation failed for $fileName", e)
            false
        }
    }
}
