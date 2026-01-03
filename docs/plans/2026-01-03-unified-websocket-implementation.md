# Unified WebSocket Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Change WebSocket from per-conversation connections to a single user connection with subscribe/unsubscribe messages.

**Architecture:** Single WebSocket per user at `/ws/user`. Clients send `subscribe` to switch conversations without reconnecting. Server responds with `subscribed` + full `session_state` (streaming, todos, queue). Existing REST fallback is preserved.

**Tech Stack:** Go (Fiber, gorilla/websocket), Kotlin Multiplatform (Ktor WebSocket)

---

## Phase 1: Backend Message Types

### Task 1: Add New Message Type Constants

**Files:**
- Modify: `/backend/internal/ws/dto.go`

**Step 1: Add new message type constants**

Add after existing constants (around line 15):

```go
const (
	// ... existing constants ...

	// New message types for unified WebSocket
	MessageTypeSubscribe    = "subscribe"     // Client subscribes to conversation
	MessageTypeUnsubscribe  = "unsubscribe"   // Client unsubscribes from current conversation
	MessageTypeSubscribed   = "subscribed"    // Server confirms subscription
	MessageTypeSessionState = "session_state" // Server sends full session state
)
```

**Step 2: Run tests to verify no breakage**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go test ./internal/ws/... -v`
Expected: All existing tests PASS

**Step 3: Commit**

```bash
git add backend/internal/ws/dto.go
git commit -m "feat(ws): add subscribe/session_state message types"
```

---

### Task 2: Add Subscribe/SessionState Payload Structures

**Files:**
- Modify: `/backend/internal/ws/dto.go`

**Step 1: Add payload structures**

Add after existing structs:

```go
// SubscribePayload is sent by client to subscribe to a conversation
type SubscribePayload struct {
	ConversationID string `json:"conversationId"`
	SessionID      string `json:"sessionId,omitempty"`   // For filesystem sessions
	EncodedPath    string `json:"encodedPath,omitempty"` // For filesystem sessions
}

// SubscribedPayload is sent by server to confirm subscription
type SubscribedPayload struct {
	ConversationID string `json:"conversationId"`
}

// SessionStatePayload is sent by server with full session state
type SessionStatePayload struct {
	ConversationID string                  `json:"conversationId"`
	SessionState   string                  `json:"sessionState"` // idle, queued, streaming
	IsStreaming    bool                    `json:"isStreaming"`
	Todos          []TodoItem              `json:"todos"`
	Queue          []queue.QueuedMessage   `json:"queue"`
}

// TodoItem represents a todo from Claude Code's TodoWrite
type TodoItem struct {
	Content    string  `json:"content"`
	Status     string  `json:"status"`
	ActiveForm *string `json:"activeForm,omitempty"`
	Priority   *string `json:"priority,omitempty"`
	ID         *string `json:"id,omitempty"`
}
```

**Step 2: Run tests**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go build ./...`
Expected: Build succeeds

**Step 3: Commit**

```bash
git add backend/internal/ws/dto.go
git commit -m "feat(ws): add subscribe/session_state payload structs"
```

---

## Phase 2: Backend Hub Modifications

### Task 3: Add Subscriptions Map to Hub

**Files:**
- Modify: `/backend/internal/ws/hub.go`

**Step 1: Update Hub struct**

Modify the Hub struct to add subscription tracking:

```go
type Hub struct {
	// Existing fields
	clients       map[uuid.UUID]*Client
	conversations map[uuid.UUID]map[uuid.UUID]*Client
	register      chan *Client
	unregister    chan *Client
	broadcast     chan *BroadcastMessage
	mu            sync.RWMutex

	// New: track current subscription per client
	subscriptions map[uuid.UUID]uuid.UUID // clientID -> conversationID
}
```

**Step 2: Initialize in NewHub**

Update NewHub function:

```go
func NewHub() *Hub {
	return &Hub{
		clients:       make(map[uuid.UUID]*Client),
		conversations: make(map[uuid.UUID]map[uuid.UUID]*Client),
		subscriptions: make(map[uuid.UUID]uuid.UUID), // Add this
		register:      make(chan *Client),
		unregister:    make(chan *Client),
		broadcast:     make(chan *BroadcastMessage),
	}
}
```

**Step 3: Run tests**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go test ./internal/ws/... -v`
Expected: All tests PASS

**Step 4: Commit**

```bash
git add backend/internal/ws/hub.go
git commit -m "feat(ws): add subscriptions map to Hub"
```

---

### Task 4: Add Subscribe Channel and Request Type

**Files:**
- Modify: `/backend/internal/ws/hub.go`

**Step 1: Add SubscribeRequest type and channel**

Add before Hub struct:

```go
// SubscribeRequest represents a client subscription request
type SubscribeRequest struct {
	Client         *Client
	ConversationID uuid.UUID
	SessionID      string
	EncodedPath    string
	Response       chan error // For sync response
}
```

Update Hub struct:

```go
type Hub struct {
	// ... existing fields ...
	subscribe chan *SubscribeRequest // Add this
}
```

**Step 2: Initialize channel in NewHub**

```go
func NewHub() *Hub {
	return &Hub{
		clients:       make(map[uuid.UUID]*Client),
		conversations: make(map[uuid.UUID]map[uuid.UUID]*Client),
		subscriptions: make(map[uuid.UUID]uuid.UUID),
		register:      make(chan *Client),
		unregister:    make(chan *Client),
		broadcast:     make(chan *BroadcastMessage),
		subscribe:     make(chan *SubscribeRequest), // Add this
	}
}
```

**Step 3: Run tests**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go test ./internal/ws/... -v`
Expected: All tests PASS

**Step 4: Commit**

```bash
git add backend/internal/ws/hub.go
git commit -m "feat(ws): add subscribe channel to Hub"
```

---

### Task 5: Implement handleSubscribe in Hub

**Files:**
- Modify: `/backend/internal/ws/hub.go`

**Step 1: Add handleSubscribe method**

Add after unregisterClient method:

```go
// handleSubscribe processes a subscription request
func (h *Hub) handleSubscribe(req *SubscribeRequest) {
	h.mu.Lock()
	defer h.mu.Unlock()

	client := req.Client
	newConvID := req.ConversationID

	// 1. Unsubscribe from previous conversation if any
	if oldConvID, exists := h.subscriptions[client.ID]; exists {
		if clients, ok := h.conversations[oldConvID]; ok {
			delete(clients, client.ID)
			// Clean up empty conversation map
			if len(clients) == 0 {
				delete(h.conversations, oldConvID)
			}
		}
	}

	// 2. Subscribe to new conversation
	h.subscriptions[client.ID] = newConvID
	if h.conversations[newConvID] == nil {
		h.conversations[newConvID] = make(map[uuid.UUID]*Client)
	}
	h.conversations[newConvID][client.ID] = client

	// 3. Update client's conversation ID
	client.ConversationID = newConvID

	// Signal success
	if req.Response != nil {
		req.Response <- nil
	}
}
```

**Step 2: Update Run() to handle subscribe channel**

In the Run() method, add case for subscribe:

```go
func (h *Hub) Run() {
	for {
		select {
		case client := <-h.register:
			h.registerClient(client)
		case client := <-h.unregister:
			h.unregisterClient(client)
		case req := <-h.subscribe: // Add this case
			h.handleSubscribe(req)
		case message := <-h.broadcast:
			h.broadcastMessage(message)
		}
	}
}
```

**Step 3: Add public Subscribe method**

```go
// Subscribe queues a subscription request
func (h *Hub) Subscribe(req *SubscribeRequest) {
	h.subscribe <- req
}
```

**Step 4: Run tests**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go test ./internal/ws/... -v`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add backend/internal/ws/hub.go
git commit -m "feat(ws): implement handleSubscribe in Hub"
```

---

### Task 6: Write Tests for Subscribe Logic

**Files:**
- Modify: `/backend/internal/ws/hub_test.go`

**Step 1: Write test for subscribe**

```go
func TestHub_Subscribe(t *testing.T) {
	hub := NewHub()
	go hub.Run()
	defer close(hub.register) // Stop the hub

	// Create a client
	clientID := uuid.New()
	userID := uuid.New()
	convID1 := uuid.New()
	convID2 := uuid.New()

	client := &Client{
		ID:             clientID,
		UserID:         userID,
		ConversationID: convID1,
		Send:           make(chan []byte, 256),
		Done:           make(chan struct{}),
	}

	// Register client
	hub.Register(client)
	time.Sleep(10 * time.Millisecond) // Wait for processing

	// Subscribe to first conversation
	resp := make(chan error, 1)
	hub.Subscribe(&SubscribeRequest{
		Client:         client,
		ConversationID: convID1,
		Response:       resp,
	})
	err := <-resp
	assert.NoError(t, err)

	// Verify subscription
	hub.mu.RLock()
	assert.Equal(t, convID1, hub.subscriptions[clientID])
	assert.Contains(t, hub.conversations[convID1], clientID)
	hub.mu.RUnlock()

	// Switch to second conversation
	resp2 := make(chan error, 1)
	hub.Subscribe(&SubscribeRequest{
		Client:         client,
		ConversationID: convID2,
		Response:       resp2,
	})
	err = <-resp2
	assert.NoError(t, err)

	// Verify switched
	hub.mu.RLock()
	assert.Equal(t, convID2, hub.subscriptions[clientID])
	assert.Contains(t, hub.conversations[convID2], clientID)
	assert.NotContains(t, hub.conversations[convID1], clientID)
	hub.mu.RUnlock()
}
```

**Step 2: Run tests**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go test ./internal/ws/... -v -run TestHub_Subscribe`
Expected: PASS

**Step 3: Commit**

```bash
git add backend/internal/ws/hub_test.go
git commit -m "test(ws): add tests for Hub subscribe logic"
```

---

## Phase 3: Backend Handler for User WebSocket

### Task 7: Add HandleUserWebSocket Endpoint

**Files:**
- Modify: `/backend/internal/ws/handler.go`

**Step 1: Add HandleUserWebSocket method**

Add new handler method:

```go
// HandleUserWebSocket handles the unified user WebSocket connection
// This is the new endpoint: /api/v1/ws/user
func (h *Handler) HandleUserWebSocket(c *websocket.Conn) {
	// Get user ID from context (set by auth middleware)
	userIDStr := c.Locals("userID")
	if userIDStr == nil {
		h.logger.Error("No user ID in context for user WebSocket")
		c.Close()
		return
	}

	userID, err := uuid.Parse(userIDStr.(string))
	if err != nil {
		h.logger.Error("Invalid user ID", zap.Error(err))
		c.Close()
		return
	}

	// Create client without conversation ID (will be set on subscribe)
	client := &Client{
		ID:     uuid.New(),
		UserID: userID,
		Conn:   c,
		Send:   make(chan []byte, 256),
		Done:   make(chan struct{}),
	}

	// Register client with hub
	h.hub.Register(client)

	// Start read/write pumps
	go h.writeUserPump(client)
	h.readUserPump(client)
}
```

**Step 2: Run build**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go build ./...`
Expected: Build succeeds (will fail on missing methods - that's expected)

**Step 3: Commit partial**

```bash
git add backend/internal/ws/handler.go
git commit -m "feat(ws): add HandleUserWebSocket endpoint stub"
```

---

### Task 8: Implement readUserPump

**Files:**
- Modify: `/backend/internal/ws/handler.go`

**Step 1: Add readUserPump method**

```go
// readUserPump reads messages from the unified user WebSocket
func (h *Handler) readUserPump(client *Client) {
	defer func() {
		h.hub.Unregister(client)
		client.Conn.Close()
	}()

	client.Conn.SetReadLimit(maxMessageSize)
	client.Conn.SetReadDeadline(time.Now().Add(pongWait))
	client.Conn.SetPongHandler(func(string) error {
		client.Conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	for {
		_, message, err := client.Conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				h.logger.Error("WebSocket read error", zap.Error(err))
			}
			break
		}

		var msg IncomingMessage
		if err := json.Unmarshal(message, &msg); err != nil {
			h.logger.Error("Failed to parse message", zap.Error(err))
			h.sendErrorToClient(client, "Invalid message format")
			continue
		}

		h.handleUserMessage(client, &msg)
	}
}
```

**Step 2: Run build**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go build ./...`
Expected: Build succeeds (missing handleUserMessage - expected)

**Step 3: Commit**

```bash
git add backend/internal/ws/handler.go
git commit -m "feat(ws): implement readUserPump for user WebSocket"
```

---

### Task 9: Implement handleUserMessage

**Files:**
- Modify: `/backend/internal/ws/handler.go`

**Step 1: Add handleUserMessage method**

```go
// handleUserMessage routes messages for the unified user WebSocket
func (h *Handler) handleUserMessage(client *Client, msg *IncomingMessage) {
	switch msg.Type {
	case MessageTypeSubscribe:
		h.handleSubscribeMessage(client, msg)
	case MessageTypeUnsubscribe:
		h.handleUnsubscribeMessage(client)
	case MessageTypeChat:
		h.handleUserChatMessage(client, msg)
	case MessageTypeStop:
		h.handleUserStopMessage(client)
	case MessageTypePing:
		h.handlePingMessage(client)
	default:
		h.sendErrorToClient(client, "Unknown message type: "+msg.Type)
	}
}
```

**Step 2: Run build**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go build ./...`
Expected: Missing methods - we'll add them next

**Step 3: Commit**

```bash
git add backend/internal/ws/handler.go
git commit -m "feat(ws): implement handleUserMessage router"
```

---

### Task 10: Implement handleSubscribeMessage

**Files:**
- Modify: `/backend/internal/ws/handler.go`

**Step 1: Add handleSubscribeMessage method**

```go
// handleSubscribeMessage handles subscribe requests
func (h *Handler) handleSubscribeMessage(client *Client, msg *IncomingMessage) {
	// Parse subscribe payload from content
	var payload SubscribePayload
	if err := json.Unmarshal([]byte(msg.Content), &payload); err != nil {
		h.sendErrorToClient(client, "Invalid subscribe payload")
		return
	}

	convID, err := uuid.Parse(payload.ConversationID)
	if err != nil {
		h.sendErrorToClient(client, "Invalid conversation ID")
		return
	}

	// Validate user has access to this conversation
	conv, err := h.convRepo.GetByID(context.Background(), convID)
	if err != nil {
		h.sendErrorToClient(client, "Conversation not found")
		return
	}
	if conv.UserID != client.UserID {
		h.sendErrorToClient(client, "Access denied to conversation")
		return
	}

	// Subscribe to conversation
	resp := make(chan error, 1)
	h.hub.Subscribe(&SubscribeRequest{
		Client:         client,
		ConversationID: convID,
		SessionID:      payload.SessionID,
		EncodedPath:    payload.EncodedPath,
		Response:       resp,
	})
	<-resp

	// Send subscribed confirmation
	h.sendSubscribedToClient(client, convID)

	// Send session state
	h.sendSessionStateToClient(client, convID, payload.SessionID, payload.EncodedPath)

	// Send queue sync
	h.sendQueueSyncToClient(client)

	h.logger.Info("Client subscribed to conversation",
		zap.String("clientID", client.ID.String()),
		zap.String("conversationID", convID.String()))
}
```

**Step 2: Run build**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go build ./...`
Expected: Missing helper methods

**Step 3: Commit**

```bash
git add backend/internal/ws/handler.go
git commit -m "feat(ws): implement handleSubscribeMessage"
```

---

### Task 11: Implement sendSubscribedToClient and sendSessionStateToClient

**Files:**
- Modify: `/backend/internal/ws/handler.go`

**Step 1: Add helper methods**

```go
// sendSubscribedToClient sends subscription confirmation
func (h *Handler) sendSubscribedToClient(client *Client, convID uuid.UUID) {
	payload := SubscribedPayload{
		ConversationID: convID.String(),
	}
	payloadBytes, _ := json.Marshal(payload)

	msg := OutgoingMessage{
		Type:    MessageTypeSubscribed,
		Content: string(payloadBytes),
	}
	data, _ := json.Marshal(msg)

	select {
	case client.Send <- data:
	default:
		h.logger.Warn("Failed to send subscribed message - buffer full")
	}
}

// sendSessionStateToClient sends full session state
func (h *Handler) sendSessionStateToClient(client *Client, convID uuid.UUID, sessionID, encodedPath string) {
	// Get queue from service
	queueMsgs, _ := h.queueService.GetQueue(context.Background(), convID)

	// Determine session state and todos
	var sessionState string = "idle"
	var isStreaming bool = false
	var todos []TodoItem

	// Check if there's an active Claude process
	if h.claudeMgr != nil {
		process := h.claudeMgr.GetProcess(convID)
		if process != nil && process.IsRunning() {
			sessionState = "streaming"
			isStreaming = true
		}
	}

	// If filesystem session, try to get state from history
	if sessionID != "" && encodedPath != "" {
		// This would need integration with claude history service
		// For now, default to idle
	}

	payload := SessionStatePayload{
		ConversationID: convID.String(),
		SessionState:   sessionState,
		IsStreaming:    isStreaming,
		Todos:          todos,
		Queue:          queueMsgs,
	}
	payloadBytes, _ := json.Marshal(payload)

	msg := OutgoingMessage{
		Type:    MessageTypeSessionState,
		Content: string(payloadBytes),
	}
	data, _ := json.Marshal(msg)

	select {
	case client.Send <- data:
	default:
		h.logger.Warn("Failed to send session state - buffer full")
	}
}
```

**Step 2: Run build**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go build ./...`
Expected: Build succeeds or minor issues

**Step 3: Commit**

```bash
git add backend/internal/ws/handler.go
git commit -m "feat(ws): implement sendSubscribed and sendSessionState"
```

---

### Task 12: Implement remaining user message handlers

**Files:**
- Modify: `/backend/internal/ws/handler.go`

**Step 1: Add handleUnsubscribeMessage**

```go
// handleUnsubscribeMessage handles unsubscribe requests
func (h *Handler) handleUnsubscribeMessage(client *Client) {
	// Just clear the subscription - client stays connected
	h.hub.mu.Lock()
	if oldConvID, exists := h.hub.subscriptions[client.ID]; exists {
		if clients, ok := h.hub.conversations[oldConvID]; ok {
			delete(clients, client.ID)
			if len(clients) == 0 {
				delete(h.hub.conversations, oldConvID)
			}
		}
		delete(h.hub.subscriptions, client.ID)
	}
	client.ConversationID = uuid.Nil
	h.hub.mu.Unlock()

	h.logger.Info("Client unsubscribed", zap.String("clientID", client.ID.String()))
}
```

**Step 2: Add handleUserChatMessage and handleUserStopMessage**

```go
// handleUserChatMessage handles chat messages in user WebSocket
func (h *Handler) handleUserChatMessage(client *Client, msg *IncomingMessage) {
	if client.ConversationID == uuid.Nil {
		h.sendErrorToClient(client, "Not subscribed to any conversation")
		return
	}

	// Delegate to existing chat handler logic
	// Note: This reuses the existing handleChatMessage logic
	// We need to create the appropriate context
	h.handleChatMessage(client, msg, "", uuid.Nil, false)
}

// handleUserStopMessage handles stop messages in user WebSocket
func (h *Handler) handleUserStopMessage(client *Client) {
	if client.ConversationID == uuid.Nil {
		h.sendErrorToClient(client, "Not subscribed to any conversation")
		return
	}

	h.handleStopMessage(client)
}
```

**Step 3: Add writeUserPump (reuse writePump)**

```go
// writeUserPump writes messages to the unified user WebSocket
// This is the same as writePump but for clarity
func (h *Handler) writeUserPump(client *Client) {
	h.writePump(client)
}
```

**Step 4: Run build**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go build ./...`
Expected: Build succeeds

**Step 5: Commit**

```bash
git add backend/internal/ws/handler.go
git commit -m "feat(ws): implement remaining user WebSocket handlers"
```

---

### Task 13: Add Route for /ws/user

**Files:**
- Modify: `/backend/internal/server/routes.go`

**Step 1: Add new route**

Find the WebSocket routes section and add:

```go
// Unified user WebSocket (new)
api.Use("/ws/user", s.wsHandler.Upgrade)
api.Get("/ws/user", websocket.New(s.wsHandler.HandleUserWebSocket))

// Existing per-conversation WebSocket (keep for backward compatibility)
api.Use("/ws/:conversationID", s.wsHandler.Upgrade)
api.Get("/ws/:conversationID", websocket.New(s.wsHandler.HandleConnection))
```

**Step 2: Run build**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go build ./...`
Expected: Build succeeds

**Step 3: Run all backend tests**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go test ./... -v`
Expected: All tests PASS

**Step 4: Commit**

```bash
git add backend/internal/server/routes.go
git commit -m "feat(ws): add /ws/user route for unified WebSocket"
```

---

## Phase 4: Frontend UnifiedWebSocketClient

### Task 14: Create UnifiedWebSocketClient

**Files:**
- Create: `/frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/data/websocket/UnifiedWebSocketClient.kt`

**Step 1: Create the file**

```kotlin
package com.claudecode.native.data.websocket

import com.claudecode.native.data.config.UrlConfig
import com.claudecode.native.util.DebugLogger
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class UnifiedWebSocketConfig(
    val maxReconnectAttempts: Int = 5,
    val enableAutoReconnect: Boolean = true,
    val initialDelayMs: Long = 1000L,
    val maxDelayMs: Long = 30000L
)

data class SubscriptionInfo(
    val conversationId: String,
    val sessionId: String? = null,
    val encodedPath: String? = null
)

class UnifiedWebSocketClient(
    private val httpClient: HttpClient,
    private val config: UnifiedWebSocketConfig = UnifiedWebSocketConfig()
) {
    companion object {
        private const val TAG = "UnifiedWebSocketClient"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Current subscription
    private val _currentSubscription = MutableStateFlow<SubscriptionInfo?>(null)
    val currentSubscription: StateFlow<SubscriptionInfo?> = _currentSubscription.asStateFlow()

    // Incoming messages
    private val _messages = MutableSharedFlow<IncomingMessage>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<IncomingMessage> = _messages.asSharedFlow()

    // Session state (received on subscribe)
    private val _sessionState = MutableStateFlow<SessionStatePayload?>(null)
    val sessionState: StateFlow<SessionStatePayload?> = _sessionState.asStateFlow()

    // Internal state
    private var session: WebSocketSession? = null
    private var receiveJob: Job? = null
    private var reconnectJob: Job? = null
    private val mutex = Mutex()
    private var currentToken: String? = null
    private var reconnectAttempt = 0
    private var isManualDisconnect = false

    /** Connect to the unified WebSocket endpoint */
    suspend fun connect(token: String) {
        mutex.withLock {
            if (_connectionState.value == ConnectionState.Connected) {
                DebugLogger.d(TAG, "Already connected")
                return
            }
            currentToken = token
            isManualDisconnect = false
            reconnectAttempt = 0
        }
        connectInternal()
    }

    private suspend fun connectInternal() {
        _connectionState.value = ConnectionState.Connecting

        try {
            val wsUrl = UrlConfig.getWebSocketUrl("/ws/user")
            DebugLogger.d(TAG, "Connecting to $wsUrl")

            session = httpClient.webSocketSession(wsUrl)
            _connectionState.value = ConnectionState.Connected
            reconnectAttempt = 0

            // Send auth message
            sendAuth(currentToken!!)

            // Start receive loop
            receiveJob = CoroutineScope(Dispatchers.Default).launch {
                receiveMessages()
            }

            // Re-subscribe if we had a previous subscription
            _currentSubscription.value?.let { sub ->
                DebugLogger.d(TAG, "Re-subscribing to ${sub.conversationId}")
                subscribe(sub.conversationId, sub.sessionId, sub.encodedPath)
            }

        } catch (e: Exception) {
            DebugLogger.e(TAG, "Connection failed", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            scheduleReconnect()
        }
    }

    private suspend fun receiveMessages() {
        try {
            session?.let { ws ->
                for (frame in ws.incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            handleMessage(text)
                        }
                        is Frame.Close -> {
                            DebugLogger.d(TAG, "Received close frame")
                            break
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: CancellationException) {
            DebugLogger.d(TAG, "Receive cancelled")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Receive error", e)
        } finally {
            handleDisconnect()
        }
    }

    private suspend fun handleMessage(text: String) {
        try {
            val msg = json.decodeFromString<IncomingMessage>(text)

            // Handle special message types
            when (msg.type) {
                MessageType.SUBSCRIBED -> {
                    DebugLogger.d(TAG, "Subscription confirmed: ${msg.content}")
                }
                MessageType.SESSION_STATE -> {
                    msg.content?.let { content ->
                        val state = json.decodeFromString<SessionStatePayload>(content)
                        _sessionState.value = state
                    }
                }
                else -> {}
            }

            // Emit all messages to observers
            _messages.emit(msg)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to parse message: $text", e)
        }
    }

    private suspend fun handleDisconnect() {
        mutex.withLock {
            session = null
            receiveJob = null
        }

        if (!isManualDisconnect) {
            _connectionState.value = ConnectionState.Disconnected
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (isManualDisconnect || !config.enableAutoReconnect) return
        if (reconnectAttempt >= config.maxReconnectAttempts) {
            _connectionState.value = ConnectionState.Error("Max reconnection attempts reached")
            return
        }

        reconnectAttempt++
        val delayMs = minOf(
            config.initialDelayMs * (1L shl (reconnectAttempt - 1)),
            config.maxDelayMs
        )

        _connectionState.value = ConnectionState.Reconnecting(reconnectAttempt)
        DebugLogger.d(TAG, "Scheduling reconnect attempt $reconnectAttempt in ${delayMs}ms")

        reconnectJob = CoroutineScope(Dispatchers.Default).launch {
            delay(delayMs)
            connectInternal()
        }
    }

    /** Subscribe to a conversation */
    suspend fun subscribe(
        conversationId: String,
        sessionId: String? = null,
        encodedPath: String? = null
    ) {
        val payload = SubscribePayload(
            conversationId = conversationId,
            sessionId = sessionId,
            encodedPath = encodedPath
        )
        val msg = OutgoingMessage(
            type = MessageType.SUBSCRIBE,
            content = json.encodeToString(payload)
        )
        send(msg)

        _currentSubscription.value = SubscriptionInfo(conversationId, sessionId, encodedPath)
        DebugLogger.d(TAG, "Subscribed to conversation: $conversationId")
    }

    /** Unsubscribe from current conversation */
    suspend fun unsubscribe() {
        val msg = OutgoingMessage(type = MessageType.UNSUBSCRIBE)
        send(msg)
        _currentSubscription.value = null
        _sessionState.value = null
    }

    /** Send a chat message */
    suspend fun sendChat(content: String) {
        val msg = OutgoingMessage.chat(content)
        send(msg)
    }

    /** Send a chat message with images */
    suspend fun sendChatWithImages(content: String, images: List<ImageContentDto>) {
        val msg = OutgoingMessage.chatWithImages(content, images)
        send(msg)
    }

    /** Send stop command */
    suspend fun sendStop() {
        val msg = OutgoingMessage.stop()
        send(msg)
    }

    /** Send ping */
    suspend fun sendPing() {
        val msg = OutgoingMessage.ping()
        send(msg)
    }

    private suspend fun sendAuth(token: String) {
        val msg = OutgoingMessage.auth(token)
        send(msg)
    }

    private suspend fun send(message: OutgoingMessage) {
        val currentSession = mutex.withLock { session }
        if (currentSession == null) {
            DebugLogger.e(TAG, "Cannot send - not connected")
            throw IllegalStateException("Not connected")
        }

        try {
            val text = json.encodeToString(message)
            currentSession.send(Frame.Text(text))
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Send failed", e)
            throw e
        }
    }

    /** Disconnect from WebSocket */
    suspend fun disconnect() {
        mutex.withLock {
            isManualDisconnect = true
            reconnectJob?.cancelAndJoin()
            reconnectJob = null
            receiveJob?.cancelAndJoin()
            receiveJob = null

            try {
                session?.close(CloseReason(CloseReason.Codes.NORMAL, "User disconnect"))
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Error closing session", e)
            }
            session = null
        }

        _connectionState.value = ConnectionState.Disconnected
        _currentSubscription.value = null
        _sessionState.value = null
    }

    /** Check if connected */
    fun isConnected(): Boolean = _connectionState.value == ConnectionState.Connected
}
```

**Step 2: Run build**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/frontend && ./gradlew composeApp:compileKotlinDesktop`
Expected: Build succeeds or shows missing types

**Step 3: Commit**

```bash
git add frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/data/websocket/UnifiedWebSocketClient.kt
git commit -m "feat(ws): create UnifiedWebSocketClient"
```

---

### Task 15: Add New Message Types to WebSocketMessage.kt

**Files:**
- Modify: `/frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/data/websocket/WebSocketMessage.kt`

**Step 1: Add new message type constants**

In the MessageType object, add:

```kotlin
object MessageType {
    // ... existing types ...
    const val SUBSCRIBE = "subscribe"
    const val UNSUBSCRIBE = "unsubscribe"
    const val SUBSCRIBED = "subscribed"
    const val SESSION_STATE = "session_state"
}
```

**Step 2: Add SubscribePayload and SessionStatePayload**

```kotlin
@Serializable
data class SubscribePayload(
    val conversationId: String,
    val sessionId: String? = null,
    val encodedPath: String? = null
)

@Serializable
data class SessionStatePayload(
    val conversationId: String,
    val sessionState: String,
    val isStreaming: Boolean,
    val todos: List<TodoItemPayload> = emptyList(),
    val queue: List<QueueMessagePayload> = emptyList()
)

@Serializable
data class TodoItemPayload(
    val content: String,
    val status: String,
    val activeForm: String? = null,
    val priority: String? = null,
    val id: String? = null
)
```

**Step 3: Run build**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/frontend && ./gradlew composeApp:compileKotlinDesktop`
Expected: Build succeeds

**Step 4: Commit**

```bash
git add frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/data/websocket/WebSocketMessage.kt
git commit -m "feat(ws): add subscribe/session_state message types to frontend"
```

---

### Task 16: Update AppModule DI

**Files:**
- Modify: `/frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/di/AppModule.kt`

**Step 1: Add UnifiedWebSocketClient as singleton**

Find the websocket client registration and add:

```kotlin
// Unified WebSocket client (new - singleton)
single { UnifiedWebSocketClient(get()) }

// Keep existing WebSocketClient for backward compatibility during migration
single { WebSocketClient(get()) }
```

**Step 2: Run build**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/frontend && ./gradlew composeApp:compileKotlinDesktop`
Expected: Build succeeds

**Step 3: Commit**

```bash
git add frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/di/AppModule.kt
git commit -m "feat(di): register UnifiedWebSocketClient singleton"
```

---

### Task 17: Update ChatViewModel to Use UnifiedWebSocketClient

**Files:**
- Modify: `/frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/ChatViewModel.kt`

**Step 1: Update constructor to inject UnifiedWebSocketClient**

Change the constructor parameter:

```kotlin
class ChatViewModel(
    private val unifiedWebSocketClient: UnifiedWebSocketClient,  // Changed
    private val claudeHistoryApi: ClaudeHistoryApi,
    private val queueApi: QueueApi,
    private val conversationApi: ConversationApi,
    private val favoriteApi: FavoriteApi
) : ViewModel() {
```

**Step 2: Update connection logic**

Replace the `connect()` method to use subscribe:

```kotlin
suspend fun connect(
    conversationId: String,
    sessionId: String?,
    encodedPath: String?,
    token: String
) {
    // Connect if not already connected
    if (!unifiedWebSocketClient.isConnected()) {
        unifiedWebSocketClient.connect(token)
    }

    // Subscribe to conversation
    unifiedWebSocketClient.subscribe(conversationId, sessionId, encodedPath)
}
```

**Step 3: Update message observation**

Change message collection to use unified client:

```kotlin
private fun observeMessages() {
    viewModelScope.launch {
        unifiedWebSocketClient.messages.collect { message ->
            handleIncomingMessage(message)
        }
    }

    viewModelScope.launch {
        unifiedWebSocketClient.sessionState.collect { state ->
            state?.let { handleSessionState(it) }
        }
    }
}

private fun handleSessionState(state: SessionStatePayload) {
    streamingMutex.withLock {
        _isStreaming.value = state.isStreaming
    }
    // Update todos
    // Update queue
}
```

**Step 4: Run build**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/frontend && ./gradlew composeApp:compileKotlinDesktop`
Expected: Build succeeds (may have minor issues to fix)

**Step 5: Commit**

```bash
git add frontend/composeApp/src/commonMain/kotlin/com/claudecode/native/ui/viewmodel/ChatViewModel.kt
git commit -m "feat(viewmodel): update ChatViewModel to use UnifiedWebSocketClient"
```

---

## Phase 5: Cleanup and Testing

### Task 18: Run Full Backend Tests

**Step 1: Run all tests**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go test ./... -v`
Expected: All tests PASS

**Step 2: Fix any failures**

If tests fail, fix the issues.

**Step 3: Commit if fixes needed**

```bash
git add -A
git commit -m "fix(ws): fix test failures"
```

---

### Task 19: Run Full Frontend Build

**Step 1: Run desktop build**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/frontend && ./gradlew composeApp:compileKotlinDesktop`
Expected: Build succeeds

**Step 2: Fix any failures**

If build fails, fix the issues.

**Step 3: Commit if fixes needed**

```bash
git add -A
git commit -m "fix(ws): fix frontend build issues"
```

---

### Task 20: Integration Test (Manual)

**Step 1: Start backend**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/backend && go run ./cmd/server`

**Step 2: Run frontend**

Run: `cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket/frontend && ./gradlew composeApp:desktopRun`

**Step 3: Test flow**

1. Login
2. Open a conversation
3. Verify WebSocket connects to /ws/user
4. Verify subscribe message sent
5. Verify session_state received
6. Send a chat message
7. Switch to another conversation
8. Verify subscribe sent (no reconnect)
9. Disconnect and verify reconnect

**Step 4: Document any issues**

Create issues or fix immediately.

---

### Task 21: Create PR

**Step 1: Push branch**

```bash
cd /Users/probe/git/devnogari/claude-code-native/.worktrees/unified-websocket
git push -u origin feature/unified-websocket
```

**Step 2: Create PR**

```bash
gh pr create --title "feat: unified WebSocket connection per user" --body "$(cat <<'EOF'
## Summary
- Change WebSocket from per-conversation to per-user connection
- Add subscribe/unsubscribe messages for room switching
- Add session_state message for full state sync on subscribe
- Integrate HistoryWatch functionality into unified WebSocket

## Changes
- Backend: New /ws/user endpoint, Hub subscribe handling
- Frontend: UnifiedWebSocketClient, updated ChatViewModel

## Test Plan
- [ ] Backend tests pass
- [ ] Frontend builds successfully
- [ ] Manual E2E test: connect → subscribe → chat → switch room → chat
- [ ] Reconnection works with auto re-subscribe
EOF
)"
```

---

## Summary

| Phase | Tasks | Estimated Steps |
|-------|-------|-----------------|
| 1. Backend Message Types | 2 | 6 |
| 2. Backend Hub | 4 | 16 |
| 3. Backend Handler | 7 | 28 |
| 4. Frontend Client | 4 | 16 |
| 5. Cleanup & Testing | 4 | 12 |
| **Total** | **21** | **~78** |
