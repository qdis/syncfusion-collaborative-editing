// ABOUTME: Integration tests for Redis ZSET+HASH structure with CAS-based operations
// ABOUTME: Tests version reservation, commit, contiguous ops, autosave snapshot and cleanup
package ai.apps.syncfusioncollaborativeediting

import ai.apps.syncfusioncollaborativeediting.helper.CollaborativeEditingHelper
import com.fasterxml.jackson.databind.ObjectMapper
import com.syncfusion.ej2.wordprocessor.ActionInfo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class RedisKeyStructureTest {

    @Autowired
    private lateinit var stringRedisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val testRoomName = "test-room-zset-hash"

    @BeforeEach
    fun setup() {
        cleanupTestKeys()
    }

    @AfterEach
    fun tearDown() {
        cleanupTestKeys()
    }

    private fun cleanupTestKeys() {
        val keys = listOf(
            testRoomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX,
            testRoomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX,
            testRoomName + CollaborativeEditingHelper.VERSION_COUNTER_SUFFIX,
            testRoomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX,
            testRoomName + CollaborativeEditingHelper.USER_INFO_SUFFIX,
            "dirty_rooms",
            "active_rooms"
        )
        keys.forEach { stringRedisTemplate.delete(it) }
    }

    private fun createDummyAction(version: Int, roomName: String = testRoomName): ActionInfo {
        val action = ActionInfo()
        action.version = version
        action.roomName = roomName
        action.isTransformed = true
        return action
    }

    @Test
    fun `test RESERVE_VERSION_SCRIPT - allocates sequential versions with placeholders`() {
        val opsHashKey = testRoomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX
        val opsIndexKey = testRoomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX
        val versionKey = testRoomName + CollaborativeEditingHelper.VERSION_COUNTER_SUFFIX

        val script = DefaultRedisScript<List<Any>>()
        script.setScriptText(CollaborativeEditingHelper.RESERVE_VERSION_SCRIPT)
        script.resultType = List::class.java as Class<List<Any>>

        // Reserve first version
        val result1 = stringRedisTemplate.execute(
            script,
            listOf(opsHashKey, opsIndexKey, versionKey),
            "0"
        )!!

        val version1 = (result1[0] as Number).toInt()
        assertEquals(1, version1, "First version should be 1")

        // Verify placeholder exists
        val placeholder1 = stringRedisTemplate.opsForHash<String, String>().get(opsHashKey, "1")
        assertEquals("__PENDING__", placeholder1, "Should have placeholder")

        // Reserve second version
        val result2 = stringRedisTemplate.execute(
            script,
            listOf(opsHashKey, opsIndexKey, versionKey),
            "0"
        )!!

        val version2 = (result2[0] as Number).toInt()
        assertEquals(2, version2, "Second version should be 2")

        // Verify counter
        val counter = stringRedisTemplate.opsForValue().get(versionKey)?.toInt()
        assertEquals(2, counter, "Version counter should be 2")
    }

    @Test
    fun `test COMMIT_TRANSFORMED_SCRIPT - CAS prevents double commit`() {
        val opsHashKey = testRoomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX
        val opsIndexKey = testRoomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX

        val commitScript = DefaultRedisScript<String>()
        commitScript.setScriptText(CollaborativeEditingHelper.COMMIT_TRANSFORMED_SCRIPT)
        commitScript.resultType = String::class.java

        // Set up a placeholder
        stringRedisTemplate.opsForHash<String, String>().put(opsHashKey, "1", "__PENDING__")
        stringRedisTemplate.opsForZSet().add(opsIndexKey, "1", 1.0)

        val action = createDummyAction(1)
        val actionJson = objectMapper.writeValueAsString(action)

        // First commit should succeed
        val status1 = stringRedisTemplate.execute(
            commitScript,
            listOf(opsHashKey, opsIndexKey),
            actionJson,
            "1"
        )
        assertEquals("OK", status1, "First commit should succeed")

        // Second commit should fail (CAS)
        val status2 = stringRedisTemplate.execute(
            commitScript,
            listOf(opsHashKey, opsIndexKey),
            actionJson,
            "1"
        )
        assertEquals("VERSION_CONFLICT", status2, "Second commit should fail CAS check")
    }

    @Test
    fun `test GET_PENDING_SCRIPT - returns contiguous ops only`() {
        val opsHashKey = testRoomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX
        val opsIndexKey = testRoomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX
        val persistedKey = testRoomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

        // Set persisted version to 0
        stringRedisTemplate.opsForValue().set(persistedKey, "0")

        // Set up ops: committed 1, 2, 3, pending 4, committed 5
        for (i in 1..5) {
            val action = createDummyAction(i)
            val json = if (i == 4) "__PENDING__" else objectMapper.writeValueAsString(action)
            stringRedisTemplate.opsForHash<String, String>().put(opsHashKey, i.toString(), json)
            stringRedisTemplate.opsForZSet().add(opsIndexKey, i.toString(), i.toDouble())
        }

        val script = DefaultRedisScript<List<Any>>()
        script.setScriptText(CollaborativeEditingHelper.GET_PENDING_SCRIPT)
        script.resultType = List::class.java as Class<List<Any>>

        // Client at version 0 should get ops 1, 2, 3 (stops at pending 4)
        val result = stringRedisTemplate.execute(
            script,
            listOf(opsHashKey, opsIndexKey, persistedKey),
            "0"
        )!!

        val opsData = result[0] as List<*>
        val resyncFlag = (result[1] as Number).toInt()

        assertEquals(3, opsData.size, "Should return 3 contiguous ops (stops at pending)")
        assertEquals(0, resyncFlag, "Should not need resync")
    }

    @Test
    fun `test GET_PENDING_SCRIPT - returns resync flag for stale client`() {
        val opsHashKey = testRoomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX
        val opsIndexKey = testRoomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX
        val persistedKey = testRoomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

        // Set persisted version to 10
        stringRedisTemplate.opsForValue().set(persistedKey, "10")

        // Client at version 5 is stale
        val script = DefaultRedisScript<List<Any>>()
        script.setScriptText(CollaborativeEditingHelper.GET_PENDING_SCRIPT)
        script.resultType = List::class.java as Class<List<Any>>

        val result = stringRedisTemplate.execute(
            script,
            listOf(opsHashKey, opsIndexKey, persistedKey),
            "5"
        )!!

        val opsData = result[0] as List<*>
        val resyncFlag = (result[1] as Number).toInt()
        val windowStart = (result[2] as Number).toInt()

        assertEquals(0, opsData.size, "Stale client should get empty ops")
        assertEquals(1, resyncFlag, "Should need resync")
        assertEquals(11, windowStart, "Window starts after persisted version")
    }

    @Test
    fun `test AUTOSAVE_SNAPSHOT_SCRIPT - returns pending versions`() {
        val opsHashKey = testRoomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX
        val opsIndexKey = testRoomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX
        val persistedKey = testRoomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

        // Set persisted version to 5
        stringRedisTemplate.opsForValue().set(persistedKey, "5")

        // Add ops 6, 7, 8
        for (i in 6..8) {
            val action = createDummyAction(i)
            stringRedisTemplate.opsForHash<String, String>().put(
                opsHashKey,
                i.toString(),
                objectMapper.writeValueAsString(action)
            )
            stringRedisTemplate.opsForZSet().add(opsIndexKey, i.toString(), i.toDouble())
        }

        val script = DefaultRedisScript<List<Any>>()
        script.setScriptText(CollaborativeEditingHelper.AUTOSAVE_SNAPSHOT_SCRIPT)
        script.resultType = List::class.java as Class<List<Any>>

        val result = stringRedisTemplate.execute(
            script,
            listOf(opsIndexKey, persistedKey),
            "1000"
        )!!

        val persistedVersion = (result[0] as Number).toInt()
        val versions = result[1] as List<*>

        assertEquals(5, persistedVersion, "Should return current persisted version")
        assertEquals(3, versions.size, "Should return 3 pending versions")
        assertEquals("6", versions[0].toString())
        assertEquals("8", versions[2].toString())
    }

    @Test
    fun `test AUTOSAVE_CLEANUP_SCRIPT - CAS advances persisted version and deletes ops`() {
        val opsHashKey = testRoomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX
        val opsIndexKey = testRoomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX
        val persistedKey = testRoomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

        // Set persisted version to 5
        stringRedisTemplate.opsForValue().set(persistedKey, "5")

        // Add ops 1-8
        for (i in 1..8) {
            val action = createDummyAction(i)
            stringRedisTemplate.opsForHash<String, String>().put(
                opsHashKey,
                i.toString(),
                objectMapper.writeValueAsString(action)
            )
            stringRedisTemplate.opsForZSet().add(opsIndexKey, i.toString(), i.toDouble())
        }

        val script = DefaultRedisScript<Long>()
        script.setScriptText(CollaborativeEditingHelper.AUTOSAVE_CLEANUP_SCRIPT)
        script.resultType = Long::class.java

        // Cleanup up to version 7
        val finalPersisted = stringRedisTemplate.execute(
            script,
            listOf(opsHashKey, opsIndexKey, persistedKey),
            "7",
            "5"
        )!!

        assertEquals(7L, finalPersisted, "Should advance to version 7")

        // Verify ops 1-7 are deleted
        val remaining = stringRedisTemplate.opsForZSet().size(opsIndexKey)
        assertEquals(1L, remaining, "Should have 1 remaining op (version 8)")

        // Verify persisted version updated
        val persisted = stringRedisTemplate.opsForValue().get(persistedKey)?.toInt()
        assertEquals(7, persisted, "Persisted version should be 7")
    }

    @Test
    fun `test AUTOSAVE_CLEANUP_SCRIPT - CAS fails if expected version mismatch`() {
        val opsHashKey = testRoomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX
        val opsIndexKey = testRoomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX
        val persistedKey = testRoomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

        // Set persisted version to 10 (someone else already advanced it)
        stringRedisTemplate.opsForValue().set(persistedKey, "10")

        val script = DefaultRedisScript<Long>()
        script.setScriptText(CollaborativeEditingHelper.AUTOSAVE_CLEANUP_SCRIPT)
        script.resultType = Long::class.java

        // Try to cleanup expecting version 5, but it's actually 10
        val finalPersisted = stringRedisTemplate.execute(
            script,
            listOf(opsHashKey, opsIndexKey, persistedKey),
            "7",
            "5"
        )!!

        assertEquals(10L, finalPersisted, "Should return actual persisted version (CAS failed)")

        // Verify persisted version unchanged
        val persisted = stringRedisTemplate.opsForValue().get(persistedKey)?.toInt()
        assertEquals(10, persisted, "Persisted version should remain 10")
    }

    @Test
    fun `test sequential operations maintain monotonic versions`() {
        val opsHashKey = testRoomName + CollaborativeEditingHelper.OPS_HASH_SUFFIX
        val opsIndexKey = testRoomName + CollaborativeEditingHelper.OPS_INDEX_SUFFIX
        val versionKey = testRoomName + CollaborativeEditingHelper.VERSION_COUNTER_SUFFIX

        val reserveScript = DefaultRedisScript<List<Any>>()
        reserveScript.setScriptText(CollaborativeEditingHelper.RESERVE_VERSION_SCRIPT)
        reserveScript.resultType = List::class.java as Class<List<Any>>

        val commitScript = DefaultRedisScript<String>()
        commitScript.setScriptText(CollaborativeEditingHelper.COMMIT_TRANSFORMED_SCRIPT)
        commitScript.resultType = String::class.java

        // Reserve and commit 10 operations
        for (i in 1..10) {
            val result = stringRedisTemplate.execute(
                reserveScript,
                listOf(opsHashKey, opsIndexKey, versionKey),
                (i - 1).toString()
            )!!

            val version = (result[0] as Number).toInt()
            assertEquals(i, version, "Version should be sequential")

            val action = createDummyAction(version)
            val status = stringRedisTemplate.execute(
                commitScript,
                listOf(opsHashKey, opsIndexKey),
                objectMapper.writeValueAsString(action),
                version.toString()
            )
            assertEquals("OK", status, "Commit should succeed")
        }

        // Verify all versions exist
        val allVersions = stringRedisTemplate.opsForZSet().range(opsIndexKey, 0, -1)
        assertEquals(10, allVersions?.size, "Should have 10 versions")
        assertEquals(setOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), allVersions)
    }

    @Test
    fun `test dirty_rooms tracking`() {
        // Add room to dirty set
        stringRedisTemplate.opsForSet().add("dirty_rooms", testRoomName)

        val members = stringRedisTemplate.opsForSet().members("dirty_rooms")
        assertTrue(members?.contains(testRoomName) == true, "Room should be in dirty set")

        // Remove room from dirty set
        stringRedisTemplate.opsForSet().remove("dirty_rooms", testRoomName)

        val membersAfter = stringRedisTemplate.opsForSet().members("dirty_rooms")
        assertFalse(membersAfter?.contains(testRoomName) == true, "Room should not be in dirty set")
    }
}
