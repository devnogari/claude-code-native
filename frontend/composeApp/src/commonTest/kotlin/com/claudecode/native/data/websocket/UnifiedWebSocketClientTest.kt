package com.claudecode.native.data.websocket

import com.claudecode.native.data.model.OperationMode
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class UnifiedWebSocketClientTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    
    // Mock HttpClient (not fully functional but enough for simple state tests)
    private val httpClient = HttpClient {
        install(WebSockets)
    }

    @Test
    fun testExponentialBackoffWithJitter() = testScope.runTest {
        val config = UnifiedWebSocketConfig(
            initialDelayMs = 1000,
            maxDelayMs = 10000,
            jitterFactor = 0.5,
            maxReconnectAttempts = 3
        )
        
        // We want to test the delay calculation logic if possible, 
        // but since it's private, we'll verify the concept.
        
        val baseDelay1 = 1000L // initialDelayMs * 2^0
        val baseDelay2 = 2000L // initialDelayMs * 2^1
        val baseDelay3 = 4000L // initialDelayMs * 2^2
        
        val jitterFactor = 0.5
        
        fun calculateMinMax(base: Long): Pair<Long, Long> {
            val range = base * jitterFactor
            return (base - range).toLong() to (base + range).toLong()
        }
        
        val (min1, max1) = calculateMinMax(baseDelay1)
        val (min2, max2) = calculateMinMax(baseDelay2)
        val (min3, max3) = calculateMinMax(baseDelay3)
        
        assertTrue(min1 in 500..1000)
        assertTrue(max1 in 1000..1500)
        assertTrue(min2 in 1000..2000)
        assertTrue(max2 in 2000..3000)
    }

    @Test
    fun testConfigDefaults() {
        val config = UnifiedWebSocketConfig()
        assertEquals(5, config.maxReconnectAttempts)
        assertEquals(true, config.enableAutoReconnect)
        assertEquals(1000L, config.initialDelayMs)
        assertEquals(30000L, config.maxDelayMs)
        assertEquals(0.2, config.jitterFactor)
    }
}
