# Code Quality Roadmap Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Establish comprehensive code quality infrastructure and refactor large files for maintainability

**Architecture:** Multi-phase approach - (1) Setup quality tooling, (2) Add test infrastructure, (3) Refactor large files, (4) Add documentation

**Tech Stack:** Kotlin Multiplatform, Compose, Detekt, JUnit5, Koin Test

---

## Overview

### Current State Analysis

| Area | Frontend | Backend |
|------|----------|---------|
| Test Coverage | 0% (no tests) | ~60% (good) |
| Linting | None | golangci-lint |
| File Size Issues | ChatViewModel (2727 LOC), ChatScreen (1220 LOC) | None major |
| Documentation | Minimal KDoc | Good |

### Priority Matrix

| Task | Impact | Effort | Parallelizable |
|------|--------|--------|----------------|
| Setup Detekt | High | Low | Yes |
| Add Test Infrastructure | High | Medium | Yes |
| Refactor ChatViewModel | Critical | High | No (depends on tests) |
| Refactor ChatScreen | High | Medium | No (depends on tests) |
| Add KDoc Documentation | Medium | Medium | Yes |

---

## Phase 1: Quality Tooling Setup (Parallel)

### Task 1.1: Setup Detekt for Static Analysis

**Files:**
- Create: `frontend/config/detekt/detekt.yml`
- Modify: `frontend/build.gradle.kts`

**Step 1: Add Detekt plugin to version catalog**

Modify `frontend/gradle/libs.versions.toml`:
```toml
[versions]
detekt = "1.23.7"

[plugins]
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
```

**Step 2: Create Detekt configuration**

Create `frontend/config/detekt/detekt.yml`:
```yaml
build:
  maxIssues: 0
  excludeCorrectable: false
  weights:
    complexity: 2
    formatting: 1
    LongParameterList: 1
    style: 1
    comments: 1

config:
  validation: true
  warningsAsErrors: false
  checkExhaustiveness: false

processors:
  active: true
  exclude:
    - 'DetektProgressListener'

console-reports:
  active: true
  exclude:
    - 'ProjectStatisticsReport'
    - 'ComplexityReport'
    - 'NotificationReport'
    - 'FindingsReport'
    - 'FileBasedFindingsReport'

comments:
  active: true
  UndocumentedPublicClass:
    active: false  # Enable later
  UndocumentedPublicFunction:
    active: false  # Enable later

complexity:
  active: true
  ComplexMethod:
    active: true
    threshold: 15
  LongMethod:
    active: true
    threshold: 60
  LongParameterList:
    active: true
    functionThreshold: 6
    constructorThreshold: 7
  TooManyFunctions:
    active: true
    thresholdInFiles: 15
    thresholdInClasses: 15
    thresholdInInterfaces: 10
    thresholdInObjects: 15
  LargeClass:
    active: true
    threshold: 600
  NestedBlockDepth:
    active: true
    threshold: 4

exceptions:
  active: true
  TooGenericExceptionCaught:
    active: true
    exceptionNames:
      - ArrayIndexOutOfBoundsException
      - Error
      - Exception
      - IllegalMonitorStateException
      - NullPointerException
      - IndexOutOfBoundsException
      - RuntimeException
      - Throwable

naming:
  active: true
  FunctionNaming:
    active: true
    functionPattern: '[a-z][a-zA-Z0-9]*'
  VariableNaming:
    active: true
    variablePattern: '[a-z][a-zA-Z0-9]*'

performance:
  active: true
  SpreadOperator:
    active: false  # Common in Compose

style:
  active: true
  MagicNumber:
    active: false  # Too noisy for UI code
  MaxLineLength:
    active: true
    maxLineLength: 120
  WildcardImport:
    active: true
    excludeImports:
      - 'java.util.*'
      - 'kotlinx.android.synthetic.*'
      - 'androidx.compose.foundation.*'
      - 'androidx.compose.foundation.layout.*'
      - 'androidx.compose.material3.*'
      - 'androidx.compose.runtime.*'
      - 'androidx.compose.ui.*'
```

**Step 3: Apply Detekt plugin**

Modify `frontend/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.hot.reload) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
    }
}
```

**Step 4: Run Detekt to generate baseline**

```bash
cd frontend
./gradlew detektBaseline
```

**Step 5: Verify Detekt works**

```bash
./gradlew detekt
```

**Step 6: Commit**

```bash
git add config/detekt/ build.gradle.kts gradle/libs.versions.toml
git commit -m "build: setup detekt for static code analysis"
```

---

### Task 1.2: Setup Test Infrastructure

**Files:**
- Modify: `frontend/composeApp/build.gradle.kts`
- Create: `frontend/composeApp/src/commonTest/kotlin/com/claudecode/native/TestUtil.kt`
- Create: `frontend/composeApp/src/desktopTest/kotlin/com/claudecode/native/ui/viewmodel/ChatViewModelTest.kt`

**Step 1: Add test dependencies**

Modify `frontend/gradle/libs.versions.toml`:
```toml
[versions]
# ... existing versions
# Note: kotlin-test uses the kotlin version (2.3.0)
# Note: kotlinx-coroutines-test uses the kotlinx-coroutines version (1.10.2)
mockk = "1.13.14"
turbine = "1.2.0"

[libraries]
# ... existing libraries
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
kotlin-test-junit5 = { module = "org.jetbrains.kotlin:kotlin-test-junit5", version.ref = "kotlin" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
```

**Step 2: Configure test in composeApp**

Add to `frontend/composeApp/build.gradle.kts` in the kotlin {} block:
```kotlin
sourceSets {
    commonTest.dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.turbine)
    }

    desktopTest.dependencies {
        implementation(libs.kotlin.test.junit5)
        implementation(libs.mockk)
    }
}
```

**Step 3: Create test utility base class**

Create `frontend/composeApp/src/commonTest/kotlin/com/claudecode/native/TestUtil.kt`:
```kotlin
package com.claudecode.native

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Base class for ViewModel tests with coroutine support.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTestBase {
    protected val testDispatcher: TestDispatcher = StandardTestDispatcher()

    open fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    open fun tearDown() {
        Dispatchers.resetMain()
    }
}
```

**Step 4: Create sample test**

Create `frontend/composeApp/src/desktopTest/kotlin/com/claudecode/native/ui/viewmodel/LoginViewModelTest.kt`:
```kotlin
package com.claudecode.native.ui.viewmodel

import app.cash.turbine.test
import com.claudecode.native.ViewModelTestBase
import com.claudecode.native.data.api.ApiClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest : ViewModelTestBase() {

    private lateinit var apiClient: ApiClient
    private lateinit var viewModel: LoginViewModel

    @BeforeEach
    override fun setup() {
        super.setup()
        apiClient = mockk(relaxed = true)
        viewModel = LoginViewModel(apiClient)
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
    }

    @Test
    fun `initial state should not be loading`() = runTest {
        viewModel.isLoading.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `initial state should have no error`() = runTest {
        viewModel.error.test {
            assertEquals(null, awaitItem())
        }
    }
}
```

**Step 5: Run tests**

```bash
./gradlew desktopTest
```

**Step 6: Commit**

```bash
git add composeApp/build.gradle.kts gradle/libs.versions.toml \
  composeApp/src/commonTest/ composeApp/src/desktopTest/
git commit -m "test: setup test infrastructure with JUnit5, MockK, and Turbine"
```

---

## Phase 2: ChatViewModel Refactoring (Sequential)

### Task 2.1: Extract Message Management

**Goal:** Extract message-related logic from ChatViewModel (2727 LOC) to MessageManager

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/MessageManager.kt`
- Create: `frontend/composeApp/src/desktopTest/kotlin/com/claudecode/native/ui/viewmodel/MessageManagerTest.kt`
- Modify: `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/ChatViewModel.kt`

**Step 1: Write failing tests for MessageManager**

Create `frontend/composeApp/src/desktopTest/kotlin/com/claudecode/native/ui/viewmodel/MessageManagerTest.kt`:
```kotlin
package com.claudecode.native.ui.viewmodel

import app.cash.turbine.test
import com.claudecode.native.ViewModelTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MessageManagerTest : ViewModelTestBase() {

    private lateinit var manager: MessageManager

    @BeforeEach
    override fun setup() {
        super.setup()
        manager = MessageManager()
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
    }

    @Test
    fun `initial messages should be empty`() = runTest {
        manager.messages.test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `addMessage should append to list`() = runTest {
        val message = ChatMessage(
            id = "1",
            role = MessageRole.USER,
            blocks = listOf(ContentBlock.Text("Hello"))
        )

        manager.addMessage(message)

        manager.messages.test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
            assertEquals("1", messages[0].id)
        }
    }

    @Test
    fun `clearMessages should empty the list`() = runTest {
        manager.addMessage(ChatMessage("1", MessageRole.USER, listOf()))
        manager.clearMessages()

        manager.messages.test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `prependMessages should add to beginning`() = runTest {
        manager.addMessage(ChatMessage("2", MessageRole.USER, listOf()))
        manager.prependMessages(listOf(ChatMessage("1", MessageRole.USER, listOf())))

        manager.messages.test {
            val messages = awaitItem()
            assertEquals(2, messages.size)
            assertEquals("1", messages[0].id)
            assertEquals("2", messages[1].id)
        }
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
./gradlew desktopTest --tests "*.MessageManagerTest"
```
Expected: FAIL - MessageManager class not found

**Step 3: Create MessageManager class**

Create `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/MessageManager.kt`:
```kotlin
package com.claudecode.native.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages chat messages with thread-safe operations.
 *
 * Extracted from ChatViewModel to reduce class size and improve testability.
 */
class MessageManager {
    private val mutex = Mutex()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(false)
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private var currentOffset: Int = 0

    /**
     * Add a message to the end of the list.
     */
    suspend fun addMessage(message: ChatMessage) = mutex.withLock {
        _messages.value = _messages.value + message
    }

    /**
     * Add multiple messages to the end of the list.
     */
    suspend fun addMessages(messages: List<ChatMessage>) = mutex.withLock {
        _messages.value = _messages.value + messages
    }

    /**
     * Prepend messages to the beginning (for pagination).
     */
    suspend fun prependMessages(messages: List<ChatMessage>) = mutex.withLock {
        val existingIds = _messages.value.map { it.id }.toSet()
        val uniqueMessages = messages.filter { it.id !in existingIds }
        _messages.value = uniqueMessages + _messages.value
    }

    /**
     * Replace all messages.
     */
    suspend fun setMessages(messages: List<ChatMessage>) = mutex.withLock {
        _messages.value = messages
    }

    /**
     * Clear all messages.
     */
    suspend fun clearMessages() = mutex.withLock {
        _messages.value = emptyList()
        _hasMoreMessages.value = false
        _isLoadingMore.value = false
        currentOffset = 0
    }

    /**
     * Update pagination state.
     */
    fun setHasMoreMessages(hasMore: Boolean) {
        _hasMoreMessages.value = hasMore
    }

    /**
     * Set loading state for pagination.
     */
    fun setLoadingMore(loading: Boolean) {
        _isLoadingMore.value = loading
    }

    /**
     * Get current offset for pagination.
     */
    fun getCurrentOffset(): Int = currentOffset

    /**
     * Update offset after loading more messages.
     */
    fun incrementOffset(count: Int) {
        currentOffset += count
    }

    /**
     * Find and update a message by ID.
     */
    suspend fun updateMessage(id: String, updater: (ChatMessage) -> ChatMessage) = mutex.withLock {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id) updater(msg) else msg
        }
    }

    /**
     * Get message by ID.
     */
    fun getMessageById(id: String): ChatMessage? = _messages.value.find { it.id == id }
}
```

**Step 4: Run tests to verify they pass**

```bash
./gradlew desktopTest --tests "*.MessageManagerTest"
```
Expected: PASS

**Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/MessageManager.kt \
  composeApp/src/desktopTest/kotlin/com/claudecode/native/ui/viewmodel/MessageManagerTest.kt
git commit -m "refactor: extract MessageManager from ChatViewModel"
```

---

### Task 2.2: Extract Queue Management

**Goal:** Extract message queue logic to QueueManager

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/QueueManager.kt`
- Create: `frontend/composeApp/src/desktopTest/kotlin/com/claudecode/native/ui/viewmodel/QueueManagerTest.kt`

**Step 1: Write failing tests**

Create `frontend/composeApp/src/desktopTest/kotlin/com/claudecode/native/ui/viewmodel/QueueManagerTest.kt`:
```kotlin
package com.claudecode.native.ui.viewmodel

import app.cash.turbine.test
import com.claudecode.native.ViewModelTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerTest : ViewModelTestBase() {

    private lateinit var manager: QueueManager

    @BeforeEach
    override fun setup() {
        super.setup()
        manager = QueueManager(maxQueueSize = 10)
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
    }

    @Test
    fun `initial queue should be empty`() = runTest {
        val queue = manager.getQueueForConversation("conv1")
        queue.test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `addToQueue should add message to conversation queue`() = runTest {
        val message = QueuedMessage(
            id = "1",
            content = "Hello",
            timestamp = System.currentTimeMillis(),
            source = QueuedMessageSource.LOCAL_APP
        )

        manager.addToQueue("conv1", message)

        val queue = manager.getQueueForConversation("conv1")
        queue.test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
            assertEquals("1", messages[0].id)
        }
    }

    @Test
    fun `removeFromQueue should remove message`() = runTest {
        manager.addToQueue("conv1", QueuedMessage("1", "Hello", 0, QueuedMessageSource.LOCAL_APP))
        manager.removeFromQueue("conv1", "1")

        val queue = manager.getQueueForConversation("conv1")
        queue.test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `clearQueue should empty conversation queue`() = runTest {
        manager.addToQueue("conv1", QueuedMessage("1", "Hello", 0, QueuedMessageSource.LOCAL_APP))
        manager.clearQueue("conv1")

        val queue = manager.getQueueForConversation("conv1")
        queue.test {
            assertTrue(awaitItem().isEmpty())
        }
    }

    @Test
    fun `queues should be isolated per conversation`() = runTest {
        manager.addToQueue("conv1", QueuedMessage("1", "Hello", 0, QueuedMessageSource.LOCAL_APP))
        manager.addToQueue("conv2", QueuedMessage("2", "World", 0, QueuedMessageSource.LOCAL_APP))

        val queue1 = manager.getQueueForConversation("conv1")
        queue1.test {
            assertEquals(1, awaitItem().size)
        }

        val queue2 = manager.getQueueForConversation("conv2")
        queue2.test {
            assertEquals(1, awaitItem().size)
        }
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
./gradlew desktopTest --tests "*.QueueManagerTest"
```
Expected: FAIL

**Step 3: Create QueueManager class**

Create `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/QueueManager.kt`:
```kotlin
package com.claudecode.native.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages queued messages per conversation with thread-safe operations.
 *
 * Provides conversation-scoped message queuing for:
 * - Local app messages (user-initiated)
 * - CLI messages (from Claude Code)
 *
 * @param maxQueueSize Maximum number of messages allowed in queue per conversation
 */
class QueueManager(private val maxQueueSize: Int = 50) {
    private val mutex = Mutex()

    private val queuedMessagesMap = mutableMapOf<String, MutableStateFlow<List<QueuedMessage>>>()

    /**
     * Get the queue StateFlow for a specific conversation.
     * Creates new flow if conversation not tracked yet.
     */
    fun getQueueForConversation(conversationId: String): StateFlow<List<QueuedMessage>> {
        return queuedMessagesMap.getOrPut(conversationId) {
            MutableStateFlow(emptyList())
        }.asStateFlow()
    }

    /**
     * Add a message to the conversation's queue.
     */
    suspend fun addToQueue(conversationId: String, message: QueuedMessage) = mutex.withLock {
        val flow = queuedMessagesMap.getOrPut(conversationId) {
            MutableStateFlow(emptyList())
        }
        val currentQueue = flow.value
        if (currentQueue.size < maxQueueSize) {
            flow.value = currentQueue + message
        }
    }

    /**
     * Remove a message from the queue by ID.
     */
    suspend fun removeFromQueue(conversationId: String, messageId: String) = mutex.withLock {
        val flow = queuedMessagesMap[conversationId] ?: return@withLock
        flow.value = flow.value.filter { it.id != messageId }
    }

    /**
     * Remove messages before a certain timestamp.
     */
    suspend fun removeMessagesBefore(conversationId: String, timestamp: Long) = mutex.withLock {
        val flow = queuedMessagesMap[conversationId] ?: return@withLock
        flow.value = flow.value.filter { it.timestamp >= timestamp }
    }

    /**
     * Clear all messages in a conversation's queue.
     */
    suspend fun clearQueue(conversationId: String) = mutex.withLock {
        queuedMessagesMap[conversationId]?.value = emptyList()
    }

    /**
     * Update the entire queue for a conversation.
     */
    suspend fun updateQueue(conversationId: String, messages: List<QueuedMessage>) = mutex.withLock {
        val flow = queuedMessagesMap.getOrPut(conversationId) {
            MutableStateFlow(emptyList())
        }
        flow.value = messages.take(maxQueueSize)
    }

    /**
     * Get the current queue size for a conversation.
     */
    fun getQueueSize(conversationId: String): Int {
        return queuedMessagesMap[conversationId]?.value?.size ?: 0
    }

    /**
     * Check if queue has local (app-initiated) messages.
     */
    fun hasLocalMessages(conversationId: String): Boolean {
        return queuedMessagesMap[conversationId]?.value?.any {
            it.source == QueuedMessageSource.LOCAL_APP
        } ?: false
    }
}
```

**Step 4: Run tests to verify they pass**

```bash
./gradlew desktopTest --tests "*.QueueManagerTest"
```
Expected: PASS

**Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/QueueManager.kt \
  composeApp/src/desktopTest/kotlin/com/claudecode/native/ui/viewmodel/QueueManagerTest.kt
git commit -m "refactor: extract QueueManager from ChatViewModel"
```

---

### Task 2.3: Extract Progress Tracking

**Goal:** Extract progress tracking logic to ProgressTracker

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/ProgressTracker.kt`
- Create: `frontend/composeApp/src/desktopTest/kotlin/com/claudecode/native/ui/viewmodel/ProgressTrackerTest.kt`

**Step 1: Write failing tests**

Create `frontend/composeApp/src/desktopTest/kotlin/com/claudecode/native/ui/viewmodel/ProgressTrackerTest.kt`:
```kotlin
package com.claudecode.native.ui.viewmodel

import app.cash.turbine.test
import com.claudecode.native.ViewModelTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressTrackerTest : ViewModelTestBase() {

    private lateinit var tracker: ProgressTracker

    @BeforeEach
    override fun setup() {
        super.setup()
        tracker = ProgressTracker()
    }

    @AfterEach
    override fun tearDown() {
        tracker.stopAll()
        super.tearDown()
    }

    @Test
    fun `initial progress should be inactive`() = runTest {
        val status = tracker.getProgressForConversation("conv1")
        status.test {
            val item = awaitItem()
            assertFalse(item.isActive)
        }
    }

    @Test
    fun `startTracking should activate progress`() = runTest {
        tracker.startTracking("conv1")

        val status = tracker.getProgressForConversation("conv1")
        status.test {
            val item = awaitItem()
            assertTrue(item.isActive)
        }
    }

    @Test
    fun `stopTracking should deactivate progress`() = runTest {
        tracker.startTracking("conv1")
        tracker.stopTracking("conv1")

        val status = tracker.getProgressForConversation("conv1")
        status.test {
            val item = awaitItem()
            assertFalse(item.isActive)
        }
    }

    @Test
    fun `updateTodos should reflect in progress status`() = runTest {
        tracker.startTracking("conv1")
        tracker.updateTodos("conv1", listOf(
            TodoItem("1", "Task 1", false, "Doing task 1"),
            TodoItem("2", "Task 2", true, "Task 2 done")
        ))

        val status = tracker.getProgressForConversation("conv1")
        status.test {
            val item = awaitItem()
            assertEquals(2, item.todos.size)
            assertEquals(1, item.todos.count { it.isCompleted })
        }
    }
}
```

**Step 2-5:** Follow same pattern - implement ProgressTracker, run tests, commit

---

### Task 2.4: Extract WebSocket Message Handling

**Goal:** Extract WebSocket message handling to WebSocketMessageHandler

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/WebSocketMessageHandler.kt`
- Create: `frontend/composeApp/src/desktopTest/kotlin/com/claudecode/native/ui/viewmodel/WebSocketMessageHandlerTest.kt`

*Implementation follows same TDD pattern*

---

### Task 2.5: Integrate Extracted Components into ChatViewModel

**Goal:** Refactor ChatViewModel to use extracted components

**Files:**
- Modify: `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/ChatViewModel.kt`
- Modify: `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/di/AppModule.kt`

**Step 1: Update AppModule for DI**

```kotlin
// Add to AppModule.kt
single { MessageManager() }
single { QueueManager() }
single { ProgressTracker() }
```

**Step 2: Refactor ChatViewModel constructor**

```kotlin
class ChatViewModel(
    private val apiClient: ApiClient,
    private val webSocketClient: WebSocketClient,
    private val historyWatchClient: HistoryWatchClient,
    private val claudeHistoryApi: ClaudeHistoryApi,
    private val commandApi: CommandApi,
    private val queueApi: QueueApi,
    private val messageManager: MessageManager,  // NEW
    private val queueManager: QueueManager,      // NEW
    private val progressTracker: ProgressTracker // NEW
) : ViewModel() {
    // Delegate to extracted components
    val messages = messageManager.messages
    val hasMoreMessages = messageManager.hasMoreMessages
    val isLoadingMore = messageManager.isLoadingMore
    // ... etc
}
```

**Step 3-5:** Run full test suite, fix any issues, commit

---

## Phase 3: ChatScreen Refactoring (Sequential)

### Task 3.1: Extract Input Section

**Goal:** Extract chat input logic to ChatInputSection composable

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/component/ChatInputSection.kt`

### Task 3.2: Extract Message List

**Goal:** Extract message list to ChatMessageList composable

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/component/ChatMessageList.kt`

### Task 3.3: Extract Dialogs and Menus

**Goal:** Extract dialogs and dropdown menus

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/component/ChatDialogs.kt`

---

## Phase 4: Documentation (Parallel)

### Task 4.1: Add KDoc to Data Layer

**Files:**
- Modify: All files in `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/data/`

### Task 4.2: Add KDoc to ViewModel Layer

**Files:**
- Modify: All files in `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/`

### Task 4.3: Add KDoc to Component Layer

**Files:**
- Modify: All files in `frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/component/`

---

## Summary

### Parallel Execution Groups

**Group A (Tooling - can run in parallel):**
- Task 1.1: Setup Detekt
- Task 1.2: Setup Test Infrastructure

**Group B (ChatViewModel Refactoring - sequential):**
- Task 2.1: Extract MessageManager
- Task 2.2: Extract QueueManager
- Task 2.3: Extract ProgressTracker
- Task 2.4: Extract WebSocketMessageHandler
- Task 2.5: Integrate components

**Group C (ChatScreen Refactoring - sequential, after Group B):**
- Task 3.1: Extract ChatInputSection
- Task 3.2: Extract ChatMessageList
- Task 3.3: Extract ChatDialogs

**Group D (Documentation - can run in parallel after refactoring):**
- Task 4.1: Add KDoc to Data Layer
- Task 4.2: Add KDoc to ViewModel Layer
- Task 4.3: Add KDoc to Component Layer

### Expected Outcomes

| Metric | Before | After |
|--------|--------|-------|
| ChatViewModel LOC | 2727 | ~800 |
| ChatScreen LOC | 1220 | ~400 |
| Test Coverage (Frontend) | 0% | ~60% |
| Linting | None | Detekt configured |
| New Components | 0 | 7 (managers + UI) |

---

**Plan complete and saved to `docs/plans/2026-01-03-code-quality-roadmap.md`. Two execution options:**

**1. Subagent-Driven (this session)** - I dispatch fresh subagent per task, review between tasks, fast iteration

**2. Parallel Session (separate)** - Open new session with executing-plans, batch execution with checkpoints

**Which approach?**
