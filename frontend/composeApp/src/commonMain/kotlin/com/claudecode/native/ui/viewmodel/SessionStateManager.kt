package com.claudecode.native.ui.viewmodel

import com.claudecode.native.util.DebugLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages session identity and state for chat conversations.
 *
 * This class is responsible for:
 * - Session identity tracking (conversation ID, session ID, project path)
 * - Draft session state management
 * - Session created event handling
 * - Conversation title tracking
 *
 * Thread Safety:
 * - sessionCreatedMutex protects pendingSessionCreatedEmit access
 * - State changes should be coordinated with calling code
 */
class SessionStateManager {
    companion object {
        private const val TAG = "SessionStateManager"
        /** Marker for draft sessions that haven't been created yet. */
        const val DRAFT_SESSION_MARKER = "draft"

        /**
         * Checks if the given conversation ID represents a filesystem-based session.
         * Filesystem sessions use "sessionId?project=encodedPath" format.
         *
         * @param conversationId The conversation ID to check
         * @return true if this is a filesystem-based session ID, false otherwise
         */
        fun isFilesystemSessionId(conversationId: String): Boolean {
            return conversationId.contains("?project=")
        }

        /**
         * Encodes a project path for use in session identifiers.
         * Uses URL encoding to avoid ambiguity with paths containing hyphens.
         *
         * @param path The project path to encode
         * @return The URL-encoded path string
         */
        fun encodeProjectPath(path: String): String {
            // URL encode the path to avoid ambiguity
            // This handles paths with hyphens correctly (e.g., /path/lazy-session)
            return path.encodeURLPathComponent()
        }

        /**
         * URL encodes a path component, replacing unsafe characters.
         * Encodes: / -> %2F, space -> %20, etc.
         */
        private fun String.encodeURLPathComponent(): String {
            val sb = StringBuilder()
            for (char in this) {
                when {
                    char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' || char == '~' -> sb.append(char)
                    else -> {
                        // Encode as %XX
                        val bytes = char.toString().encodeToByteArray()
                        for (byte in bytes) {
                            sb.append('%')
                            sb.append(((byte.toInt() shr 4) and 0xF).toString(16).uppercase())
                            sb.append((byte.toInt() and 0xF).toString(16).uppercase())
                        }
                    }
                }
            }
            return sb.toString()
        }
    }

    // ============================================================================
    // Session Identity Variables
    // ============================================================================

    /** Current conversation ID (either "sessionId?project=encodedPath" or legacy UUID). */
    var currentConversationId: String? = null
        private set

    /** Encoded project path for filesystem-based sessions. */
    var currentEncodedPath: String? = null
        private set

    /** Current Claude session ID (the part before ?project=). */
    var currentClaudeSession: String? = null
        private set

    /** Original project path for API calls (decoded from encodedPath). */
    var currentProjectPath: String? = null
        private set

    /** Track current connect job to cancel on room switch. */
    var currentConnectJob: Job? = null

    /** True when streaming data is coming from history watch. */
    var isStreamingFromHistoryWatch: Boolean = false

    // ============================================================================
    // Session State Flows
    // ============================================================================

    /** Flow for current conversation ID to enable reactive queue filtering. */
    private val _currentConversationIdFlow = MutableStateFlow<String?>(null)
    val currentConversationIdFlow: StateFlow<String?> = _currentConversationIdFlow.asStateFlow()

    private val _conversationTitle = MutableStateFlow<String?>(null)
    /** Current conversation title for display in UI. */
    val conversationTitle: StateFlow<String?> = _conversationTitle.asStateFlow()

    private val _isDraftSession = MutableStateFlow(false)
    /** True when this is a draft session (not yet created on server). */
    val isDraftSession: StateFlow<Boolean> = _isDraftSession.asStateFlow()

    /**
     * Event data for when a new session is created from draft mode.
     */
    data class SessionCreatedInfo(
        val sessionId: String,
        val encodedPath: String
    )

    private val _sessionCreatedEvent = MutableStateFlow<SessionCreatedInfo?>(null)
    /**
     * Emits session info when a draft session is converted to a real session.
     * UI can observe this to update the sidebar/project list with polling.
     */
    val sessionCreatedEvent: StateFlow<SessionCreatedInfo?> = _sessionCreatedEvent.asStateFlow()

    /**
     * Pending session info to emit when first server response arrives (STREAM message).
     * HistoryWatch serves as fallback for edge cases where STREAM may be missed.
     * Protected by sessionCreatedMutex to ensure thread-safe access from multiple handlers.
     */
    private var pendingSessionCreatedEmit: SessionCreatedInfo? = null
    val sessionCreatedMutex = Mutex()

    // ============================================================================
    // Session State Management Methods
    // ============================================================================

    /**
     * Sets the current session identity.
     * Called when connecting to a conversation.
     *
     * @param conversationId The full conversation ID
     * @param encodedPath Optional encoded project path
     * @param claudeSession Optional Claude session ID
     * @param projectPath Optional decoded project path
     */
    fun setSessionIdentity(
        conversationId: String,
        encodedPath: String? = null,
        claudeSession: String? = null,
        projectPath: String? = null
    ) {
        currentConversationId = conversationId
        _currentConversationIdFlow.value = conversationId
        currentEncodedPath = encodedPath
        currentClaudeSession = claudeSession
        currentProjectPath = projectPath
        DebugLogger.d(TAG, "Set session identity: convId=${conversationId.take(30)}, session=${claudeSession?.take(15)}")
    }

    /**
     * Updates only the encoded path and claude session.
     * Used when additional session info is discovered after initial connect.
     */
    fun updateSessionInfo(encodedPath: String?, claudeSession: String?) {
        currentEncodedPath = encodedPath
        currentClaudeSession = claudeSession
    }

    /**
     * Updates the project path.
     * Called after fetching project info from API.
     */
    fun setProjectPath(projectPath: String) {
        currentProjectPath = projectPath
    }

    /**
     * Sets the conversation title.
     */
    fun setConversationTitle(title: String?) {
        _conversationTitle.value = title
    }

    /**
     * Sets the draft session state.
     */
    fun setDraftSession(isDraft: Boolean) {
        _isDraftSession.value = isDraft
    }

    /**
     * Gets the current draft session value.
     */
    fun isDraftSessionValue(): Boolean = _isDraftSession.value

    /**
     * Clears all session identity state.
     * Called when disconnecting from a conversation.
     */
    fun clearSessionIdentity() {
        currentConversationId = null
        _currentConversationIdFlow.value = null
        currentEncodedPath = null
        currentClaudeSession = null
        currentProjectPath = null
        isStreamingFromHistoryWatch = false
        DebugLogger.d(TAG, "Cleared session identity")
    }

    /**
     * Clears transient session state without clearing identity.
     * Called when switching conversations (before new identity is set).
     */
    fun clearTransientState() {
        _conversationTitle.value = null
        _isDraftSession.value = false
        _sessionCreatedEvent.value = null
    }

    /**
     * Parses the conversationId to extract session ID and encoded path.
     * @return Pair of (sessionId, encodedPath) or (null, null) for legacy format
     */
    fun parseConversationId(conversationId: String): Pair<String?, String?> {
        if (!isFilesystemSessionId(conversationId)) {
            return Pair(null, null)
        }
        val parts = conversationId.split("?project=")
        if (parts.size != 2) {
            return Pair(null, null)
        }
        return Pair(parts[0], parts[1])
    }

    /**
     * Checks if the given conversation ID is a draft session.
     */
    fun isDraftConversationId(conversationId: String): Boolean {
        val (sessionId, _) = parseConversationId(conversationId)
        return sessionId == DRAFT_SESSION_MARKER
    }

    // ============================================================================
    // Session Created Event Methods
    // ============================================================================

    /**
     * Sets the pending session created info to emit when first response arrives.
     * Must be called within sessionCreatedMutex.withLock { }.
     */
    suspend fun setPendingSessionCreatedEmit(info: SessionCreatedInfo?) {
        sessionCreatedMutex.withLock {
            pendingSessionCreatedEmit = info
        }
    }

    /**
     * Sets the pending session created info without lock (use within existing lock).
     */
    fun setPendingSessionCreatedEmitUnsafe(info: SessionCreatedInfo?) {
        pendingSessionCreatedEmit = info
    }

    /**
     * Gets and clears the pending session created info without lock (use within existing lock).
     * Returns null if no pending info.
     */
    fun consumePendingSessionCreatedEmitUnsafe(): SessionCreatedInfo? {
        val info = pendingSessionCreatedEmit
        pendingSessionCreatedEmit = null
        return info
    }

    /**
     * Gets and clears the pending session created info with lock.
     * Returns null if no pending info.
     */
    suspend fun consumePendingSessionCreatedEmit(): SessionCreatedInfo? {
        return sessionCreatedMutex.withLock {
            val info = pendingSessionCreatedEmit
            pendingSessionCreatedEmit = null
            info
        }
    }

    /**
     * Checks if there's a pending session created event without consuming it.
     */
    suspend fun hasPendingSessionCreatedEmit(): Boolean {
        return sessionCreatedMutex.withLock {
            pendingSessionCreatedEmit != null
        }
    }

    /**
     * Emits the session created event.
     * Called when first server response arrives for a newly created session.
     */
    fun emitSessionCreatedEvent(info: SessionCreatedInfo) {
        _sessionCreatedEvent.value = info
        DebugLogger.d(TAG, "Emitted session created event: sessionId=${info.sessionId.take(15)}")
    }

    /**
     * Clears the session created event after it has been consumed by the UI.
     */
    fun clearSessionCreatedEvent() {
        _sessionCreatedEvent.value = null
    }

    // ============================================================================
    // Guard Check Methods
    // ============================================================================

    /**
     * Checks if we're still connected to the expected conversation.
     * Used as a guard to prevent race conditions during async operations.
     *
     * @param expectedConversationId The conversation ID we expect to be connected to
     * @return true if still connected to the expected conversation
     */
    fun isActiveConversation(expectedConversationId: String): Boolean {
        return currentConversationId == expectedConversationId
    }

    /**
     * Checks if already connected to the given conversation.
     * Used to skip redundant connect calls.
     */
    fun isAlreadyConnected(conversationId: String): Boolean {
        return currentConversationId == conversationId && currentConnectJob?.isActive != true
    }
}
