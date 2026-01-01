package claude

import (
	"encoding/json"
	"sync"
	"time"

	"github.com/gofiber/contrib/websocket"
	"github.com/gofiber/fiber/v2"
	"go.uber.org/zap"
)

const (
	// watchWriteWait is the time allowed to write a message to the peer
	watchWriteWait = 10 * time.Second

	// watchPongWait is the time allowed to read the next pong message from the peer
	watchPongWait = 60 * time.Second

	// watchPingPeriod is the period for sending ping messages
	watchPingPeriod = (watchPongWait * 9) / 10

	// watchSendTimeout is the maximum time to wait when sending a message to a client
	watchSendTimeout = 100 * time.Millisecond

	// watchMaxSendRetries is the number of times to retry sending a message
	watchMaxSendRetries = 3
)

// WatchMessageType constants for WebSocket communication
const (
	WatchMessageTypeNewMessages = "new_messages"
	WatchMessageTypeError       = "error"
	WatchMessageTypePing        = "ping"
	WatchMessageTypePong        = "pong"
	WatchMessageTypeSubscribed  = "subscribed"
)

// WatchOutgoingMessage represents a message sent to the WebSocket client
type WatchOutgoingMessage struct {
	Type        string          `json:"type"`
	SessionID   string          `json:"session_id,omitempty"`
	EncodedPath string          `json:"encoded_path,omitempty"`
	Messages    []ClaudeMessage `json:"messages,omitempty"`
	Error       string          `json:"error,omitempty"`
}

// WatchClient represents a WebSocket client watching a session
type WatchClient struct {
	conn        *websocket.Conn
	send        chan []byte
	done        chan struct{}
	encodedPath string
	sessionID   string
}

// HistoryWatchHandler handles WebSocket connections for watching session file changes
type HistoryWatchHandler struct {
	cache   *HistoryCache
	logger  *zap.Logger
	clients map[string][]*WatchClient // keyed by "encodedPath/sessionID"
	mu      sync.RWMutex
}

// NewHistoryWatchHandler creates a new history watch handler
func NewHistoryWatchHandler(cache *HistoryCache, logger *zap.Logger) *HistoryWatchHandler {
	return &HistoryWatchHandler{
		cache:   cache,
		logger:  logger,
		clients: make(map[string][]*WatchClient),
	}
}

// Upgrade checks if the request is a WebSocket upgrade request
func (h *HistoryWatchHandler) Upgrade(c *fiber.Ctx) error {
	if websocket.IsWebSocketUpgrade(c) {
		return c.Next()
	}
	return fiber.ErrUpgradeRequired
}

// HandleConnection is the main WebSocket handler for watching session file changes
func (h *HistoryWatchHandler) HandleConnection(c *websocket.Conn) {
	encodedPath := c.Params("encodedPath")
	sessionID := c.Params("sessionId")

	if encodedPath == "" || sessionID == "" {
		h.sendError(c, "encoded path and session ID are required")
		return
	}

	// Note: We no longer require the session to exist beforehand.
	// For new sessions (created from draft mode), the session folder may not exist yet
	// when HistoryWatch connects. The session folder will be created when Claude CLI
	// starts processing the first message.
	// The cache.Subscribe will handle watching for the folder to be created.

	// Create client
	client := &WatchClient{
		conn:        c,
		send:        make(chan []byte, 256),
		done:        make(chan struct{}),
		encodedPath: encodedPath,
		sessionID:   sessionID,
	}

	// Register client
	h.registerClient(client)

	h.logger.Info("watch client connected",
		zap.String("encodedPath", encodedPath),
		zap.String("sessionID", sessionID))

	// Unregister on disconnect
	defer func() {
		h.unregisterClient(client)
		h.logger.Info("watch client disconnected",
			zap.String("encodedPath", encodedPath),
			zap.String("sessionID", sessionID))
	}()

	// Send subscribed confirmation
	h.sendSubscribed(client)

	// Start write pump
	go h.writePump(client)

	// Read pump (blocking)
	h.readPump(client)
}

// registerClient registers a client and subscribes to session changes if needed
func (h *HistoryWatchHandler) registerClient(client *WatchClient) {
	key := client.encodedPath + "/" + client.sessionID

	h.mu.Lock()
	defer h.mu.Unlock()

	// If no clients for this session yet, subscribe to cache
	if len(h.clients[key]) == 0 {
		h.cache.Subscribe(client.encodedPath, client.sessionID, h.handleSessionChange)
	}

	h.clients[key] = append(h.clients[key], client)
}

// unregisterClient removes a client and unsubscribes from session changes if no more clients
func (h *HistoryWatchHandler) unregisterClient(client *WatchClient) {
	key := client.encodedPath + "/" + client.sessionID

	h.mu.Lock()
	defer h.mu.Unlock()

	// Remove client from list
	clients := h.clients[key]
	for i, c := range clients {
		if c == client {
			h.clients[key] = append(clients[:i], clients[i+1:]...)
			break
		}
	}

	// Close client channels
	close(client.done)
	close(client.send)

	// If no more clients for this session, unsubscribe from cache
	if len(h.clients[key]) == 0 {
		delete(h.clients, key)
		h.cache.Unsubscribe(client.encodedPath, client.sessionID)
	}
}

// handleSessionChange is called when session file changes are detected
func (h *HistoryWatchHandler) handleSessionChange(encodedPath, sessionID string, newMessages []ClaudeMessage) {
	key := encodedPath + "/" + sessionID

	h.mu.RLock()
	clients := h.clients[key]
	h.mu.RUnlock()

	if len(clients) == 0 {
		return
	}

	// Create outgoing message
	msg := &WatchOutgoingMessage{
		Type:        WatchMessageTypeNewMessages,
		SessionID:   sessionID,
		EncodedPath: encodedPath,
		Messages:    newMessages,
	}

	data, err := json.Marshal(msg)
	if err != nil {
		h.logger.Error("failed to marshal watch message", zap.Error(err))
		return
	}

	h.logger.Debug("broadcasting new messages to watch clients",
		zap.String("sessionID", sessionID),
		zap.Int("messageCount", len(newMessages)),
		zap.Int("clientCount", len(clients)))

	// Broadcast to all clients watching this session with retry logic
	for _, client := range clients {
		sent := false
		for retry := 0; retry < watchMaxSendRetries && !sent; retry++ {
			select {
			case client.send <- data:
				sent = true
			case <-time.After(watchSendTimeout):
				// Timeout, will retry if retries remaining
				if retry == watchMaxSendRetries-1 {
					h.logger.Warn("dropped message for watch client (buffer full after retries)",
						zap.String("sessionID", sessionID),
						zap.Int("retries", watchMaxSendRetries),
						zap.Int("msgLen", len(data)))
				}
			case <-client.done:
				// Client is disconnecting, skip
				sent = true
			}
		}
	}
}

// readPump reads messages from the WebSocket connection
func (h *HistoryWatchHandler) readPump(client *WatchClient) {
	client.conn.SetReadLimit(512)
	_ = client.conn.SetReadDeadline(time.Now().Add(watchPongWait))
	client.conn.SetPongHandler(func(string) error {
		_ = client.conn.SetReadDeadline(time.Now().Add(watchPongWait))
		return nil
	})

	for {
		_, data, err := client.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				h.logger.Error("websocket read error", zap.Error(err))
			}
			break
		}

		// Parse incoming message (only ping is expected)
		var msg struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal(data, &msg); err != nil {
			continue
		}

		if msg.Type == WatchMessageTypePing {
			h.sendPong(client)
		}
	}
}

// writePump writes messages to the WebSocket connection
func (h *HistoryWatchHandler) writePump(client *WatchClient) {
	ticker := time.NewTicker(watchPingPeriod)
	defer func() {
		ticker.Stop()
		_ = client.conn.Close()
	}()

	for {
		select {
		case message, ok := <-client.send:
			_ = client.conn.SetWriteDeadline(time.Now().Add(watchWriteWait))
			if !ok {
				// Channel closed
				_ = client.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			if err := client.conn.WriteMessage(websocket.TextMessage, message); err != nil {
				h.logger.Error("websocket write error", zap.Error(err))
				return
			}

		case <-ticker.C:
			_ = client.conn.SetWriteDeadline(time.Now().Add(watchWriteWait))
			if err := client.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}

		case <-client.done:
			return
		}
	}
}

// Helper methods

func (h *HistoryWatchHandler) sendError(c *websocket.Conn, errMsg string) {
	msg := &WatchOutgoingMessage{
		Type:  WatchMessageTypeError,
		Error: errMsg,
	}
	data, _ := json.Marshal(msg)
	_ = c.WriteMessage(websocket.TextMessage, data)
}

func (h *HistoryWatchHandler) sendSubscribed(client *WatchClient) {
	msg := &WatchOutgoingMessage{
		Type:        WatchMessageTypeSubscribed,
		SessionID:   client.sessionID,
		EncodedPath: client.encodedPath,
	}
	data, _ := json.Marshal(msg)
	select {
	case client.send <- data:
	default:
	}
}

func (h *HistoryWatchHandler) sendPong(client *WatchClient) {
	msg := &WatchOutgoingMessage{
		Type: WatchMessageTypePong,
	}
	data, _ := json.Marshal(msg)
	select {
	case client.send <- data:
	default:
	}
}
