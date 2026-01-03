package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.model.TodoItem
import com.claudecode.native.ui.component.ProgressStatus
import com.claudecode.native.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Thread-safe progress status tracker for per-conversation streaming progress.
 *
 * Manages progress tracking for the status line UI, including:
 * - Active/inactive status per conversation
 * - Elapsed time counter (increments every second while active)
 * - Status text updates (e.g., "Processing", "Running tests")
 * - Todo items from TodoWrite tool
 * - Token count and thinking time statistics
 *
 * Each conversation has its own independent progress state, allowing
 * multiple concurrent streaming sessions to be tracked separately.
 *
 * Thread-safe through internal mutex synchronization.
 */
class ProgressTracker {

    companion object {
        private const val TAG = "ProgressTracker"
        private const val ELAPSED_TIME_INTERVAL_MS = 1000L
    }

    // Mutex for thread-safe access to mutable state
    private val mutex = Mutex()

    // Per-conversation progress status (Map: conversationId -> StateFlow<ProgressStatus>)
    private val progressStatusMap = mutableMapOf<String, MutableStateFlow<ProgressStatus>>()

    // Per-conversation elapsed time tracking jobs (Map: conversationId -> Job)
    private val elapsedTimeJobs = mutableMapOf<String, Job>()

    // Inactive status for conversations that don't exist yet
    private val inactiveProgressStatus = MutableStateFlow(ProgressStatus()).asStateFlow()

    /**
     * Gets the progress status StateFlow for a conversation.
     *
     * Returns an inactive status flow if the conversation hasn't been tracked yet.
     * The returned StateFlow can be collected to observe progress changes.
     *
     * @param conversationId The conversation ID
     * @return StateFlow of ProgressStatus for the conversation
     */
    fun getProgressStatus(conversationId: String): StateFlow<ProgressStatus> {
        return progressStatusMap[conversationId]?.asStateFlow() ?: inactiveProgressStatus
    }

    /**
     * Checks if progress tracking is active for a conversation.
     *
     * @param conversationId The conversation ID
     * @return true if progress is currently active
     */
    fun isActive(conversationId: String): Boolean {
        return progressStatusMap[conversationId]?.value?.isActive == true
    }

    /**
     * Starts progress tracking for a conversation.
     *
     * Initializes the progress status with the given status text and starts
     * the elapsed time counter. If progress is already active, it will be
     * reset with the new status.
     *
     * Preserves existing todos when restarting progress.
     *
     * @param conversationId The conversation ID
     * @param statusText Initial status text (e.g., "Processing")
     * @param scope CoroutineScope for launching the elapsed time job
     */
    suspend fun startProgressTracking(
        conversationId: String,
        statusText: String,
        scope: CoroutineScope
    ) {
        mutex.withLock {
            DebugLogger.d(TAG, "startProgressTracking: convId=$conversationId, status=$statusText")

            // Get or create progress status flow for this conversation
            val progressFlow = progressStatusMap.getOrPut(conversationId) {
                MutableStateFlow(ProgressStatus())
            }

            // Preserve existing todos when starting/restarting
            val existingTodos = progressFlow.value.todos

            // Initialize progress status
            progressFlow.value = ProgressStatus(
                statusText = statusText,
                elapsedSeconds = 0,
                tokenCount = null,
                thinkingSeconds = null,
                isActive = true,
                todos = existingTodos
            )

            // Cancel any existing elapsed time job
            elapsedTimeJobs[conversationId]?.cancel()

            // Start elapsed time counter job
            val startTime = Clock.System.now().toEpochMilliseconds()
            elapsedTimeJobs[conversationId] = scope.launch {
                while (true) {
                    delay(ELAPSED_TIME_INTERVAL_MS)
                    val elapsed = ((Clock.System.now().toEpochMilliseconds() - startTime) / 1000).toInt()
                    progressFlow.update { current ->
                        current.copy(elapsedSeconds = elapsed)
                    }
                }
            }
        }
    }

    /**
     * Stops progress tracking for a conversation.
     *
     * Cancels the elapsed time counter and sets isActive to false.
     * Preserves existing todos.
     *
     * @param conversationId The conversation ID
     */
    suspend fun stopProgressTracking(conversationId: String) {
        mutex.withLock {
            DebugLogger.d(TAG, "stopProgressTracking: convId=$conversationId")

            // Cancel elapsed time job
            elapsedTimeJobs[conversationId]?.cancel()
            elapsedTimeJobs.remove(conversationId)

            // Update progress status to inactive, preserving todos
            val progressFlow = progressStatusMap[conversationId]
            progressFlow?.let { flow ->
                val existingTodos = flow.value.todos
                flow.value = ProgressStatus(isActive = false, todos = existingTodos)
            }
        }
    }

    /**
     * Updates the todo list for a conversation's progress status.
     *
     * Only updates if the todos have actually changed to avoid
     * unnecessary recomposition.
     *
     * @param conversationId The conversation ID
     * @param todos New list of todo items
     * @return true if todos were updated, false if unchanged
     */
    suspend fun updateTodos(conversationId: String, todos: List<TodoItem>): Boolean {
        return mutex.withLock {
            val progressFlow = progressStatusMap.getOrPut(conversationId) {
                MutableStateFlow(ProgressStatus())
            }

            val current = progressFlow.value
            if (current.todos != todos) {
                DebugLogger.d(TAG, "updateTodos: convId=$conversationId, count=${todos.size}")
                progressFlow.value = current.copy(todos = todos)
                true
            } else {
                false
            }
        }
    }

    /**
     * Updates the token count for a conversation's progress status.
     *
     * @param conversationId The conversation ID
     * @param tokenCount Number of tokens used
     */
    suspend fun updateTokens(conversationId: String, tokenCount: Int) {
        mutex.withLock {
            val progressFlow = progressStatusMap[conversationId] ?: return@withLock
            progressFlow.update { current ->
                current.copy(tokenCount = tokenCount)
            }
        }
    }

    /**
     * Updates the thinking time for a conversation's progress status.
     *
     * @param conversationId The conversation ID
     * @param thinkingSeconds Thinking time in seconds
     */
    suspend fun updateThinkingTime(conversationId: String, thinkingSeconds: Int) {
        mutex.withLock {
            val progressFlow = progressStatusMap[conversationId] ?: return@withLock
            progressFlow.update { current ->
                current.copy(thinkingSeconds = thinkingSeconds)
            }
        }
    }

    /**
     * Updates the status text for a conversation's progress status.
     *
     * @param conversationId The conversation ID
     * @param statusText New status text
     */
    suspend fun updateStatusText(conversationId: String, statusText: String) {
        mutex.withLock {
            val progressFlow = progressStatusMap[conversationId] ?: return@withLock
            progressFlow.update { current ->
                current.copy(statusText = statusText)
            }
        }
    }

    /**
     * Clears all progress-related state for a specific conversation.
     *
     * Unlike stopProgressTracking(), this completely removes the progress entry,
     * including todos and all other state.
     *
     * @param conversationId The conversation ID
     */
    suspend fun clearProgressState(conversationId: String) {
        mutex.withLock {
            DebugLogger.d(TAG, "clearProgressState: convId=$conversationId")

            // Cancel elapsed time job
            elapsedTimeJobs[conversationId]?.cancel()
            elapsedTimeJobs.remove(conversationId)

            // Remove progress status entry entirely
            progressStatusMap.remove(conversationId)
        }
    }

    /**
     * Cleans up all resources.
     *
     * Cancels all elapsed time jobs and clears all progress state.
     * Should be called when the ProgressTracker is no longer needed.
     */
    fun cleanup() {
        DebugLogger.d(TAG, "cleanup: Cancelling all jobs and clearing state")

        // Cancel all elapsed time jobs
        elapsedTimeJobs.values.forEach { it.cancel() }
        elapsedTimeJobs.clear()

        // Reset all progress to inactive
        progressStatusMap.values.forEach { flow ->
            flow.value = ProgressStatus(isActive = false)
        }
        progressStatusMap.clear()
    }
}
