package com.claudecode.native.data.repository

import app.cash.turbine.test
import com.claudecode.native.ViewModelTestBase
import com.claudecode.native.data.model.ServerConfig
import com.claudecode.native.data.model.ServerListState
import com.claudecode.native.data.storage.ServerStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for ServerRepository thread-safety and concurrent access patterns.
 *
 * Note: These tests verify the repository's behavior under concurrent operations.
 * The repository uses MutableStateFlow.update() which provides atomic updates,
 * ensuring thread-safety for concurrent modifications.
 *
 * Important: ServerRepository loads from ServerStorage on construction, so we must
 * clear the storage AND create a fresh repository instance for each test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryTest : ViewModelTestBase() {

    private lateinit var repository: ServerRepository

    @BeforeTest
    fun setUp() {
        super.setup()
        // Clear storage before each test to ensure clean state
        ServerStorage.clear()
        // Create fresh repository after clearing storage
        repository = ServerRepository()
    }

    @AfterTest
    fun cleanUp() {
        // Clean up after tests
        ServerStorage.clear()
        super.tearDown()
    }

    // ===== Basic CRUD Tests =====

    @Test
    fun `initial state should have empty server list`() = runTest {
        val repository = ServerRepository()

        assertTrue(repository.servers.isEmpty())
        assertNull(repository.currentServer)
        assertNull(repository.currentServerId)
    }

    @Test
    fun `addServer should add server and set as current if first`() = runTest {
        val repository = ServerRepository()

        val server = repository.addServer("Test Server", "localhost:8080", "Test description")

        assertEquals(1, repository.servers.size)
        assertEquals("Test Server", server.name)
        assertEquals("localhost:8080", server.host)
        assertEquals("Test description", server.description)
        assertEquals(server.id, repository.currentServerId)
    }

    @Test
    fun `addServer should clean host input by removing protocol and trailing slash`() = runTest {
        val repository = ServerRepository()

        val server = repository.addServer("Test", "https://example.com/api/v1/")

        assertEquals("example.com", server.host)
    }

    @Test
    fun `addServer should trim name and description`() = runTest {
        val repository = ServerRepository()

        val server = repository.addServer("  Test Server  ", "localhost", "  Description  ")

        assertEquals("Test Server", server.name)
        assertEquals("Description", server.description)
    }

    @Test
    fun `updateServer should modify existing server`() = runTest {
        val repository = ServerRepository()
        val server = repository.addServer("Original", "localhost:8080")

        repository.updateServer(server.id, "Updated", "newhost:9090", "New desc")

        val updated = repository.servers.first { it.id == server.id }
        assertEquals("Updated", updated.name)
        assertEquals("newhost:9090", updated.host)
        assertEquals("New desc", updated.description)
    }

    @Test
    fun `deleteServer should remove server`() = runTest {
        val repository = ServerRepository()
        val server = repository.addServer("Test", "localhost")

        repository.deleteServer(server.id)

        assertTrue(repository.servers.isEmpty())
    }

    @Test
    fun `deleteServer should switch current to another server if deleting current`() = runTest {
        val repository = ServerRepository()
        val server1 = repository.addServer("Server 1", "host1")
        val server2 = repository.addServer("Server 2", "host2")

        // server1 should be current (first added)
        assertEquals(server1.id, repository.currentServerId)

        repository.deleteServer(server1.id)

        // Should switch to server2
        assertEquals(server2.id, repository.currentServerId)
        assertEquals(1, repository.servers.size)
    }

    @Test
    fun `switchServer should update currentServerId`() = runTest {
        val repository = ServerRepository()
        val server1 = repository.addServer("Server 1", "host1")
        val server2 = repository.addServer("Server 2", "host2")

        repository.switchServer(server2.id)

        assertEquals(server2.id, repository.currentServerId)
    }

    @Test
    fun `switchServer should update lastConnectedAt timestamp`() = runTest {
        val repository = ServerRepository()
        val server1 = repository.addServer("Server 1", "host1")
        val server2 = repository.addServer("Server 2", "host2")

        val beforeSwitch = System.currentTimeMillis()
        repository.switchServer(server2.id)
        val afterSwitch = System.currentTimeMillis()

        val switched = repository.servers.first { it.id == server2.id }
        val lastConnected = assertNotNull(switched.lastConnectedAt)
        assertTrue(lastConnected >= beforeSwitch)
        assertTrue(lastConnected <= afterSwitch)
    }

    @Test
    fun `switchServer should do nothing for non-existent server id`() = runTest {
        val repository = ServerRepository()
        val server1 = repository.addServer("Server 1", "host1")

        repository.switchServer("non-existent-id")

        // Should still be server1
        assertEquals(server1.id, repository.currentServerId)
    }

    // ===== Token Management Tests =====

    @Test
    fun `updateToken should set auth token for server`() = runTest {
        val repository = ServerRepository()
        val server = repository.addServer("Test", "localhost")

        repository.updateToken(server.id, "jwt-token-123")

        val updated = repository.servers.first { it.id == server.id }
        assertEquals("jwt-token-123", updated.authToken)
    }

    @Test
    fun `updateToken should update lastConnectedAt when setting token`() = runTest {
        val repository = ServerRepository()
        val server = repository.addServer("Test", "localhost")

        val beforeUpdate = System.currentTimeMillis()
        repository.updateToken(server.id, "jwt-token-123")
        val afterUpdate = System.currentTimeMillis()

        val updated = repository.servers.first { it.id == server.id }
        val lastConnected = assertNotNull(updated.lastConnectedAt)
        assertTrue(lastConnected >= beforeUpdate)
        assertTrue(lastConnected <= afterUpdate)
    }

    @Test
    fun `clearCurrentToken should remove token from current server`() = runTest {
        val repository = ServerRepository()
        val server = repository.addServer("Test", "localhost")
        repository.updateToken(server.id, "jwt-token-123")

        repository.clearCurrentToken()

        val updated = repository.servers.first { it.id == server.id }
        assertNull(updated.authToken)
    }

    @Test
    fun `getCurrentToken should return current server token`() = runTest {
        val repository = ServerRepository()
        val server = repository.addServer("Test", "localhost")
        repository.updateToken(server.id, "jwt-token-123")

        assertEquals("jwt-token-123", repository.getCurrentToken())
    }

    @Test
    fun `getCurrentToken should return null when no current server`() = runTest {
        val repository = ServerRepository()

        assertNull(repository.getCurrentToken())
    }

    // ===== StateFlow Tests =====

    @Test
    fun `state flow should emit updates when servers change`() = runTest {
        val repository = ServerRepository()

        repository.state.test {
            // Initial empty state
            val initial = awaitItem()
            assertTrue(initial.servers.isEmpty())

            // Add server
            repository.addServer("Test", "localhost")
            val afterAdd = awaitItem()
            assertEquals(1, afterAdd.servers.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state flow should emit updates when current server changes`() = runTest {
        val repository = ServerRepository()

        repository.state.test {
            awaitItem() // Initial state

            val server1 = repository.addServer("Server 1", "host1")
            awaitItem() // After first add

            val server2 = repository.addServer("Server 2", "host2")
            awaitItem() // After second add

            repository.switchServer(server2.id)
            val afterSwitch = awaitItem()
            assertEquals(server2.id, afterSwitch.currentServerId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ===== Thread-Safety / Concurrent Access Tests =====

    @Test
    fun `concurrent addServer calls should not lose data`() = runTest {
        val repository = ServerRepository()
        val serverCount = 10

        // Add multiple servers concurrently
        val jobs = (1..serverCount).map { i ->
            async {
                repository.addServer("Server $i", "host$i:808$i")
            }
        }

        jobs.awaitAll()
        testDispatcher.scheduler.advanceUntilIdle()

        // All servers should be present
        assertEquals(serverCount, repository.servers.size)

        // Verify each server was added with correct data
        val hostPorts = repository.servers.map { it.host }.toSet()
        assertEquals(serverCount, hostPorts.size) // No duplicates lost
    }

    @Test
    fun `concurrent updateServer calls should apply all updates`() = runTest {
        val repository = ServerRepository()

        // Add servers first
        val servers = (1..5).map { i ->
            repository.addServer("Server $i", "host$i")
        }
        testDispatcher.scheduler.advanceUntilIdle()

        // Update all servers concurrently
        val jobs = servers.mapIndexed { index, server ->
            async {
                repository.updateServer(server.id, "Updated ${index + 1}", "newhost${index + 1}", "Desc ${index + 1}")
            }
        }

        jobs.awaitAll()
        testDispatcher.scheduler.advanceUntilIdle()

        // All updates should be applied
        repository.servers.forEachIndexed { index, server ->
            assertTrue(server.name.startsWith("Updated"))
            assertTrue(server.host.startsWith("newhost"))
            assertTrue(server.description.startsWith("Desc"))
        }
    }

    @Test
    fun `concurrent deleteServer calls should remove all specified servers`() = runTest {
        val repository = ServerRepository()

        // Add servers
        val servers = (1..5).map { i ->
            repository.addServer("Server $i", "host$i")
        }
        testDispatcher.scheduler.advanceUntilIdle()

        // Delete servers 1, 3, and 5 concurrently
        val toDelete = listOf(servers[0], servers[2], servers[4])
        val jobs = toDelete.map { server ->
            async {
                repository.deleteServer(server.id)
            }
        }

        jobs.awaitAll()
        testDispatcher.scheduler.advanceUntilIdle()

        // Only servers 2 and 4 should remain
        assertEquals(2, repository.servers.size)
        val remainingIds = repository.servers.map { it.id }.toSet()
        assertTrue(servers[1].id in remainingIds)
        assertTrue(servers[3].id in remainingIds)
    }

    @Test
    fun `concurrent mixed operations should maintain consistency`() = runTest {
        val repository = ServerRepository()

        // Add initial servers
        val server1 = repository.addServer("Server 1", "host1")
        val server2 = repository.addServer("Server 2", "host2")
        testDispatcher.scheduler.advanceUntilIdle()

        // Perform concurrent mixed operations
        val jobs = listOf(
            async { repository.addServer("Server 3", "host3") },
            async { repository.updateServer(server1.id, "Updated 1", "newhost1", "") },
            async { repository.updateToken(server2.id, "token-123") },
            async { repository.switchServer(server2.id) },
            async { repository.addServer("Server 4", "host4") }
        )

        jobs.awaitAll()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify state is consistent
        assertEquals(4, repository.servers.size)

        // Server 1 should be updated
        val updatedServer1 = repository.servers.find { it.id == server1.id }
        assertNotNull(updatedServer1)
        assertEquals("Updated 1", updatedServer1.name)
        assertEquals("newhost1", updatedServer1.host)

        // Server 2 should have token
        val updatedServer2 = repository.servers.find { it.id == server2.id }
        assertNotNull(updatedServer2)
        assertEquals("token-123", updatedServer2.authToken)
    }

    @Test
    fun `rapid addServer and deleteServer should not cause race conditions`() = runTest {
        val repository = ServerRepository()

        // Rapidly add and delete
        repeat(10) { i ->
            val server = repository.addServer("Server $i", "host$i")
            if (i % 2 == 0) {
                repository.deleteServer(server.id)
            }
        }
        testDispatcher.scheduler.advanceUntilIdle()

        // Should have 5 servers remaining (odd indices)
        assertEquals(5, repository.servers.size)
    }

    @Test
    fun `concurrent switchServer calls should end with valid state`() = runTest {
        val repository = ServerRepository()

        // Add multiple servers
        val servers = (1..5).map { i ->
            repository.addServer("Server $i", "host$i")
        }
        testDispatcher.scheduler.advanceUntilIdle()

        // Switch rapidly between all servers
        val jobs = servers.map { server ->
            async {
                repository.switchServer(server.id)
            }
        }

        jobs.awaitAll()
        testDispatcher.scheduler.advanceUntilIdle()

        // Current server should be one of the valid servers
        val currentId = repository.currentServerId
        assertNotNull(currentId)
        assertTrue(servers.any { it.id == currentId })
    }

    // ===== Edge Case Tests =====

    @Test
    fun `updateServer on non-existent id should not throw`() = runTest {
        val repository = ServerRepository()
        repository.addServer("Test", "localhost")

        // Should not throw
        repository.updateServer("non-existent", "Name", "host", "desc")

        // Original server should be unchanged
        assertEquals(1, repository.servers.size)
    }

    @Test
    fun `deleteServer on non-existent id should not throw`() = runTest {
        val repository = ServerRepository()
        repository.addServer("Test", "localhost")

        // Should not throw
        repository.deleteServer("non-existent")

        // Original server should remain
        assertEquals(1, repository.servers.size)
    }

    @Test
    fun `updateToken on non-existent server should not throw`() = runTest {
        val repository = ServerRepository()
        repository.addServer("Test", "localhost")

        // Should not throw
        repository.updateToken("non-existent", "token")

        // Original server should be unchanged
        val server = repository.servers.first()
        assertNull(server.authToken)
    }

    @Test
    fun `clearCurrentToken when no current server should not throw`() = runTest {
        val repository = ServerRepository()

        // Should not throw
        repository.clearCurrentToken()
    }

    @Test
    fun `second addServer should not change currentServerId`() = runTest {
        val repository = ServerRepository()
        val server1 = repository.addServer("Server 1", "host1")
        val server2 = repository.addServer("Server 2", "host2")

        // First server should still be current
        assertEquals(server1.id, repository.currentServerId)
    }

    @Test
    fun `deleting all servers should result in empty state`() = runTest {
        val repository = ServerRepository()
        val server1 = repository.addServer("Server 1", "host1")
        val server2 = repository.addServer("Server 2", "host2")

        repository.deleteServer(server1.id)
        repository.deleteServer(server2.id)

        assertTrue(repository.servers.isEmpty())
        assertNull(repository.currentServer)
        assertNull(repository.currentServerId)
    }
}
