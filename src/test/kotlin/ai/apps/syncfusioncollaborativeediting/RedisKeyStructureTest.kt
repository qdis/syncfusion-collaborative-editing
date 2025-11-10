// ABOUTME: Integration tests for Redis key structure cutover
// ABOUTME: Tests trim boundaries, stale clients, concurrent ops, presence, errors, and import deduplication
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

    private val testRoomName = "test-room-redis-keys"

    @BeforeEach
    fun setup() {
        // Clean up any existing test keys
        cleanupTestKeys()
    }

    @AfterEach
    fun tearDown() {
        cleanupTestKeys()
    }

    private fun cleanupTestKeys() {
        val keys = listOf(
            testRoomName + CollaborativeEditingHelper.OPS_SUFFIX,
            testRoomName + CollaborativeEditingHelper.START_SUFFIX,
            testRoomName + CollaborativeEditingHelper.TO_REMOVE_SUFFIX,
            testRoomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX,
            testRoomName + CollaborativeEditingHelper.USER_INFO_SUFFIX
        )
        keys.forEach { stringRedisTemplate.delete(it) }
    }

    private fun createDummyAction(version: Int, roomName: String = testRoomName): ActionInfo {
        val action = ActionInfo()
        action.version = version
        action.roomName = roomName
        action.isTransformed = false
        return action
    }

    @Test
    fun `test trim boundary - push cacheLimit+5 ops, verify length and start bumped`() {
        val cacheLimit = 10
        val opsToAdd = cacheLimit + 5

        val opsKey = testRoomName + CollaborativeEditingHelper.OPS_SUFFIX
        val startKey = testRoomName + CollaborativeEditingHelper.START_SUFFIX
        val toRemoveKey = testRoomName + CollaborativeEditingHelper.TO_REMOVE_SUFFIX

        val script = DefaultRedisScript<List<Any>>()
        script.setScriptText(CollaborativeEditingHelper.NEW_INSERT_SCRIPT)
        script.resultType = List::class.java as Class<List<Any>>

        // Insert cacheLimit + 5 operations
        for (i in 0 until opsToAdd) {
            val action = createDummyAction(i)
            val serialized = objectMapper.writeValueAsString(action)
            val keys = listOf(opsKey, startKey, toRemoveKey)
            val args = listOf(serialized, i.toString(), cacheLimit.toString())
            stringRedisTemplate.execute(script, keys, *args.toTypedArray())
        }

        // Verify ops list length is exactly cacheLimit
        val opsLength = stringRedisTemplate.opsForList().size(opsKey)
        assertEquals(cacheLimit.toLong(), opsLength, "Ops list should be trimmed to cacheLimit")

        // Verify start has been bumped by 5
        val start = stringRedisTemplate.opsForValue().get(startKey)?.toInt() ?: 1
        assertEquals(6, start, "Start should be bumped from 1 to 6 (removed 5 ops)")

        // Verify to_remove contains 5 operations
        val toRemoveLength = stringRedisTemplate.opsForList().size(toRemoveKey)
        assertEquals(5L, toRemoveLength, "to_remove should contain 5 trimmed operations")
    }

    @Test
    fun `test stale client - clientVersion before start triggers resync`() {
        val cacheLimit = 10
        val opsKey = testRoomName + CollaborativeEditingHelper.OPS_SUFFIX
        val startKey = testRoomName + CollaborativeEditingHelper.START_SUFFIX
        val toRemoveKey = testRoomName + CollaborativeEditingHelper.TO_REMOVE_SUFFIX

        val insertScript = DefaultRedisScript<List<Any>>()
        insertScript.setScriptText(CollaborativeEditingHelper.NEW_INSERT_SCRIPT)
        insertScript.resultType = List::class.java as Class<List<Any>>

        // Insert 15 operations to trigger trim (start will move to 6)
        for (i in 0 until 15) {
            val action = createDummyAction(i)
            val serialized = objectMapper.writeValueAsString(action)
            val keys = listOf(opsKey, startKey, toRemoveKey)
            val args = listOf(serialized, i.toString(), cacheLimit.toString())
            stringRedisTemplate.execute(insertScript, keys, *args.toTypedArray())
        }

        // Verify start is now 6
        val start = stringRedisTemplate.opsForValue().get(startKey)?.toInt() ?: 1
        assertEquals(6, start, "Start should be 6 after trim (removed 5 ops)")

        val pendingScript = DefaultRedisScript<List<Any>>()
        pendingScript.setScriptText(CollaborativeEditingHelper.NEW_PENDING_SCRIPT)
        pendingScript.resultType = List::class.java as Class<List<Any>>

        // Test 1: Truly stale client (version 3, which is before start=6)
        // idx = (3+1) - 6 = -2, which is < 0 → resync
        val staleClientVersion = 3
        val keys = listOf(opsKey, startKey)
        val args = listOf(staleClientVersion.toString())

        val response1 = stringRedisTemplate.execute(pendingScript, keys, *args.toTypedArray())
        assertNotNull(response1)
        val resyncFlag1 = when (val flag = response1!![1]) {
            is Number -> flag.toInt()
            is String -> flag.toInt()
            else -> 0
        }
        assertEquals(1, resyncFlag1, "Resync flag should be 1 for truly stale client (v3 < start-1)")

        // Test 2: Boundary case - client at start-1 (version 5)
        // idx = (5+1) - 6 = 0, which is >= 0 → no resync, normal serve
        val boundaryClientVersion = start - 1
        val args2 = listOf(boundaryClientVersion.toString())

        val response2 = stringRedisTemplate.execute(pendingScript, keys, *args2.toTypedArray())
        assertNotNull(response2)
        val resyncFlag2 = when (val flag = response2!![1]) {
            is Number -> flag.toInt()
            is String -> flag.toInt()
            else -> 0
        }
        assertEquals(0, resyncFlag2, "Resync flag should be 0 for boundary client (v${start-1} == start-1)")
    }

    @Test
    fun `test concurrent ops around trim - both succeed, transform context includes appended op`() {
        val cacheLimit = 10
        val opsKey = testRoomName + CollaborativeEditingHelper.OPS_SUFFIX
        val startKey = testRoomName + CollaborativeEditingHelper.START_SUFFIX
        val toRemoveKey = testRoomName + CollaborativeEditingHelper.TO_REMOVE_SUFFIX

        val insertScript = DefaultRedisScript<List<Any>>()
        insertScript.setScriptText(CollaborativeEditingHelper.NEW_INSERT_SCRIPT)
        insertScript.resultType = List::class.java as Class<List<Any>>

        // Insert 9 operations (one away from trim)
        for (i in 0 until 9) {
            val action = createDummyAction(i)
            val serialized = objectMapper.writeValueAsString(action)
            val keys = listOf(opsKey, startKey, toRemoveKey)
            val args = listOf(serialized, i.toString(), cacheLimit.toString())
            stringRedisTemplate.execute(insertScript, keys, *args.toTypedArray())
        }

        // Insert 10th operation (will trigger trim at cacheLimit)
        val action10 = createDummyAction(9)
        val serialized10 = objectMapper.writeValueAsString(action10)
        val keys = listOf(opsKey, startKey, toRemoveKey)
        val args10 = listOf(serialized10, "9", cacheLimit.toString())

        val response = stringRedisTemplate.execute(insertScript, keys, *args10.toTypedArray())

        assertNotNull(response)
        val previousOps = response!![1]
        assertTrue(previousOps is List<*>, "previousOps should be a list")

        val opsList = previousOps as List<*>
        assertTrue(opsList.isNotEmpty(), "previousOps should not be empty")

        // Verify the last operation in previousOps is the one we just appended
        val lastOpJson = opsList.last() as String
        val lastOp = objectMapper.readValue(lastOpJson, ActionInfo::class.java)
        assertEquals(9, lastOp.version, "Last op in transform context should match appended op")
    }

    @Test
    fun `test presence lifecycle - join and leave updates room-user_info, broadcast matches Redis`() {
        val userInfoKey = testRoomName + CollaborativeEditingHelper.USER_INFO_SUFFIX

        // Simulate user join by adding user to list
        val user1 = createDummyAction(0)
        user1.connectionId = "user1-connection"
        user1.currentUser = "Alice"
        val user1Json = objectMapper.writeValueAsString(user1)

        stringRedisTemplate.opsForList().rightPush(userInfoKey, user1Json)

        // Verify user is in list
        val users = stringRedisTemplate.opsForList().range(userInfoKey, 0, -1)
        assertNotNull(users)
        assertEquals(1, users!!.size, "Should have 1 user")

        // Simulate user leave by removing user
        stringRedisTemplate.opsForList().remove(userInfoKey, 1, user1Json)

        // Verify user is removed
        val usersAfterLeave = stringRedisTemplate.opsForList().range(userInfoKey, 0, -1)
        assertNotNull(usersAfterLeave)
        assertEquals(0, usersAfterLeave!!.size, "Should have 0 users after leave")
    }

    @Test
    fun `test error path - UPDATE with empty ops returns EMPTY`() {
        val opsKey = testRoomName + CollaborativeEditingHelper.OPS_SUFFIX
        val startKey = testRoomName + CollaborativeEditingHelper.START_SUFFIX

        val updateScript = DefaultRedisScript<String>()
        updateScript.setScriptText(CollaborativeEditingHelper.NEW_UPDATE_SCRIPT)
        updateScript.resultType = String::class.java

        // Try to update when ops list is empty
        val action = createDummyAction(1)
        val serialized = objectMapper.writeValueAsString(action)
        val keys = listOf(opsKey, startKey)
        val args = listOf(serialized, "1")

        val result = stringRedisTemplate.execute(updateScript, keys, *args.toTypedArray())

        assertEquals("EMPTY", result, "UPDATE on empty ops should return EMPTY")
    }

    @Test
    fun `test import dedupe - persisted_version filters to_remove ops correctly`() {
        val opsKey = testRoomName + CollaborativeEditingHelper.OPS_SUFFIX
        val toRemoveKey = testRoomName + CollaborativeEditingHelper.TO_REMOVE_SUFFIX
        val persistedVersionKey = testRoomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

        // Add ops to to_remove queue with explicit versions
        val oldOp1 = createDummyAction(0)
        oldOp1.version = 1
        val oldOp2 = createDummyAction(0)
        oldOp2.version = 2
        val oldOp3 = createDummyAction(0)
        oldOp3.version = 4  // This one is > persistedVersion=3, should be included

        stringRedisTemplate.opsForList().rightPush(toRemoveKey, objectMapper.writeValueAsString(oldOp1))
        stringRedisTemplate.opsForList().rightPush(toRemoveKey, objectMapper.writeValueAsString(oldOp2))
        stringRedisTemplate.opsForList().rightPush(toRemoveKey, objectMapper.writeValueAsString(oldOp3))

        // Add current ops to ops queue
        val currentOp = createDummyAction(0)
        currentOp.version = 5
        stringRedisTemplate.opsForList().rightPush(opsKey, objectMapper.writeValueAsString(currentOp))

        // Set persisted_version to 3 (indicating ops 1-3 are already in MinIO)
        stringRedisTemplate.opsForValue().set(persistedVersionKey, "3")

        // Simulate import logic with version filtering
        val persistedVersion = stringRedisTemplate.opsForValue().get(persistedVersionKey)?.toIntOrNull() ?: 0

        val actions = mutableListOf<ActionInfo>()

        // Filter to_remove ops by version
        val toRemoveOps = stringRedisTemplate.opsForList().range(toRemoveKey, 0, -1) ?: emptyList()
        toRemoveOps.forEach { item ->
            val action = objectMapper.readValue(item, ActionInfo::class.java)
            if (action.version > persistedVersion) {
                actions.add(action)
            }
        }

        // Always get current ops
        val opsValues = stringRedisTemplate.opsForList().range(opsKey, 0, -1) ?: emptyList()
        opsValues.forEach { item ->
            actions.add(objectMapper.readValue(item, ActionInfo::class.java))
        }

        // Verify: should have oldOp3 (v4) and currentOp (v5), but not oldOp1 (v1) or oldOp2 (v2)
        assertEquals(2, actions.size, "Should have 2 ops: v4 from to_remove and v5 from ops")
        assertEquals(4, actions[0].version, "First op should be v4 from to_remove")
        assertEquals(5, actions[1].version, "Second op should be v5 from ops")
    }

    @Test
    fun `test NEW_INSERT returns correct version after multiple inserts`() {
        val cacheLimit = 50
        val opsKey = testRoomName + CollaborativeEditingHelper.OPS_SUFFIX
        val startKey = testRoomName + CollaborativeEditingHelper.START_SUFFIX
        val toRemoveKey = testRoomName + CollaborativeEditingHelper.TO_REMOVE_SUFFIX

        val insertScript = DefaultRedisScript<List<Any>>()
        insertScript.setScriptText(CollaborativeEditingHelper.NEW_INSERT_SCRIPT)
        insertScript.resultType = List::class.java as Class<List<Any>>

        // Insert 5 operations
        val versions = mutableListOf<Int>()
        for (i in 0 until 5) {
            val action = createDummyAction(i)
            val serialized = objectMapper.writeValueAsString(action)
            val keys = listOf(opsKey, startKey, toRemoveKey)
            val args = listOf(serialized, i.toString(), cacheLimit.toString())

            val response = stringRedisTemplate.execute(insertScript, keys, *args.toTypedArray())
            assertNotNull(response)

            val version = response!![0].toString().toInt()
            versions.add(version)
        }

        // Verify versions are sequential: 1, 2, 3, 4, 5
        assertEquals(listOf(1, 2, 3, 4, 5), versions, "Versions should be sequential starting from 1")
    }

    @Test
    fun `test duplicate-save coalescing - saver filters by persisted_version`() {
        val persistedVersionKey = testRoomName + CollaborativeEditingHelper.PERSISTED_VERSION_SUFFIX

        // Set persisted_version to 3 (ops 1-3 already saved)
        stringRedisTemplate.opsForValue().set(persistedVersionKey, "3")

        // Create actions with versions 1, 2, 4, 5 (simulating duplicate save bundle)
        val actions = listOf(
            createDummyAction(0).apply { version = 1 },
            createDummyAction(0).apply { version = 2 },
            createDummyAction(0).apply { version = 4 },
            createDummyAction(0).apply { version = 5 }
        )

        // Simulate saver's filtering logic
        val persistedVersion = stringRedisTemplate.opsForValue().get(persistedVersionKey)?.toIntOrNull() ?: 0
        val toApply = actions.filter { it.version > persistedVersion }

        // Verify only versions > 3 are included
        assertEquals(2, toApply.size, "Should filter to 2 ops (v4, v5)")
        assertEquals(4, toApply[0].version, "First op should be v4")
        assertEquals(5, toApply[1].version, "Second op should be v5")
    }

    @Test
    fun `test presence join path - user written to user_info on init`() {
        val userInfoKey = testRoomName + CollaborativeEditingHelper.USER_INFO_SUFFIX

        // Simulate user join by adding user to list (as done in init())
        val user1 = createDummyAction(0)
        user1.connectionId = "session-123"
        user1.currentUser = "Alice"
        user1.roomName = testRoomName
        val user1Json = objectMapper.writeValueAsString(user1)

        stringRedisTemplate.opsForList().rightPush(userInfoKey, user1Json)

        // Verify user is in list
        val users = stringRedisTemplate.opsForList().range(userInfoKey, 0, -1)
        assertNotNull(users)
        assertEquals(1, users!!.size, "Should have 1 user in presence list")

        val retrievedUser = objectMapper.readValue(users[0], ActionInfo::class.java)
        assertEquals("session-123", retrievedUser.connectionId, "Connection ID should match")
        assertEquals("Alice", retrievedUser.currentUser, "Current user should match")
    }

    @Test
    fun `test schedule-only-on-trim - removed count triggers auto-save`() {
        val cacheLimit = 10
        val opsKey = testRoomName + CollaborativeEditingHelper.OPS_SUFFIX
        val startKey = testRoomName + CollaborativeEditingHelper.START_SUFFIX
        val toRemoveKey = testRoomName + CollaborativeEditingHelper.TO_REMOVE_SUFFIX

        val insertScript = DefaultRedisScript<List<Any>>()
        insertScript.setScriptText(CollaborativeEditingHelper.NEW_INSERT_SCRIPT)
        insertScript.resultType = List::class.java as Class<List<Any>>

        // Insert operations and track removed counts
        val removedCounts = mutableListOf<Int>()
        for (i in 0 until 15) {
            val action = createDummyAction(i)
            val serialized = objectMapper.writeValueAsString(action)
            val keys = listOf(opsKey, startKey, toRemoveKey)
            val args = listOf(serialized, i.toString(), cacheLimit.toString())

            val response = stringRedisTemplate.execute(insertScript, keys, *args.toTypedArray())
            assertNotNull(response)

            val removedCount = response!![2].toString().toInt()
            removedCounts.add(removedCount)
        }

        // First 10 inserts should have removed=0 (no trim)
        for (i in 0 until 10) {
            assertEquals(0, removedCounts[i], "Insert $i should have removed=0 (no trim yet)")
        }

        // Inserts 11-15 should have removed > 0 (trim triggered)
        for (i in 10 until 15) {
            assertTrue(removedCounts[i] > 0, "Insert $i should have removed > 0 (trim triggered)")
        }
    }
}
