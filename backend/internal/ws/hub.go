package ws

import (
	"log"
	"sync"
	"time"

	"github.com/gofrs/uuid/v5"
)

const (
	// sendTimeout is the maximum time to wait when sending a message to a client
	sendTimeout = 100 * time.Millisecond
	// maxSendRetries is the number of times to retry sending a message
	maxSendRetries = 3
)

// BroadcastMessage represents a message to be broadcast to a conversation
type BroadcastMessage struct {
	ConversationID uuid.UUID
	Data           []byte
}

// SubscribeRequest represents a client subscription request
type SubscribeRequest struct {
	Client         *Client
	ConversationID uuid.UUID
	SessionID      string
	EncodedPath    string
	Response       chan error // For sync response
}

// Hub maintains the set of active clients and broadcasts messages
type Hub struct {
	// clients maps client IDs to their Client instances
	clients map[uuid.UUID]*Client

	// conversations maps conversation IDs to a map of client IDs to Clients
	conversations map[uuid.UUID]map[uuid.UUID]*Client

	// subscriptions tracks current subscription per client (clientID -> conversationID)
	subscriptions map[uuid.UUID]uuid.UUID

	// register channel for client registration requests
	register chan *Client

	// unregister channel for client unregistration requests
	unregister chan *Client

	// subscribe channel for subscription requests
	subscribe chan *SubscribeRequest

	// broadcast channel for messages to be broadcast to conversations
	broadcast chan *BroadcastMessage

	// mu protects concurrent access to clients and conversations maps
	mu sync.RWMutex
}

// NewHub creates a new Hub with initialized channels and maps
func NewHub() *Hub {
	return &Hub{
		clients:       make(map[uuid.UUID]*Client),
		conversations: make(map[uuid.UUID]map[uuid.UUID]*Client),
		subscriptions: make(map[uuid.UUID]uuid.UUID),
		register:      make(chan *Client),
		unregister:    make(chan *Client),
		subscribe:     make(chan *SubscribeRequest),
		broadcast:     make(chan *BroadcastMessage),
	}
}

// Run starts the hub's main loop processing register, unregister, subscribe, and broadcast messages
// This should be called as a goroutine
func (h *Hub) Run() {
	for {
		select {
		case client := <-h.register:
			h.registerClient(client)

		case client := <-h.unregister:
			h.unregisterClient(client)

		case req := <-h.subscribe:
			h.handleSubscribe(req)

		case message := <-h.broadcast:
			h.broadcastMessage(message)
		}
	}
}

// registerClient adds a client to the hub
func (h *Hub) registerClient(client *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()

	// Add to clients map
	h.clients[client.ID] = client

	// Add to conversations map
	if h.conversations[client.ConversationID] == nil {
		h.conversations[client.ConversationID] = make(map[uuid.UUID]*Client)
	}
	h.conversations[client.ConversationID][client.ID] = client
}

// unregisterClient removes a client from the hub and closes its channels
func (h *Hub) unregisterClient(client *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()

	// Check if client exists
	if _, exists := h.clients[client.ID]; !exists {
		return
	}

	// Remove from clients map
	delete(h.clients, client.ID)

	// Remove from subscriptions map to prevent memory leak
	delete(h.subscriptions, client.ID)

	// Remove from conversations map
	if convClients, exists := h.conversations[client.ConversationID]; exists {
		delete(convClients, client.ID)

		// Clean up empty conversation entries
		if len(convClients) == 0 {
			delete(h.conversations, client.ConversationID)
		}
	}

	// Close the client's Done channel to signal goroutines to stop
	close(client.Done)

	// Close the client's Send channel
	close(client.Send)
}

// removeClientFromConversation removes a client from a conversation's client map.
// Must be called with h.mu held.
func (h *Hub) removeClientFromConversation(clientID, conversationID uuid.UUID) {
	if clients, ok := h.conversations[conversationID]; ok {
		delete(clients, clientID)
		// Clean up empty conversation map
		if len(clients) == 0 {
			delete(h.conversations, conversationID)
		}
	}
}

// handleSubscribe processes a subscription request
func (h *Hub) handleSubscribe(req *SubscribeRequest) {
	h.mu.Lock()
	defer h.mu.Unlock()

	client := req.Client
	newConvID := req.ConversationID

	// 1. Unsubscribe from previous conversation if any
	if oldConvID, exists := h.subscriptions[client.ID]; exists {
		h.removeClientFromConversation(client.ID, oldConvID)
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

// broadcastMessage sends a message to all clients in a conversation
// Uses retry logic with timeout to avoid silent message drops
func (h *Hub) broadcastMessage(message *BroadcastMessage) {
	h.mu.RLock()
	defer h.mu.RUnlock()

	clients, exists := h.conversations[message.ConversationID]
	if !exists {
		return
	}

	for _, client := range clients {
		// Try to send with retry logic to avoid silent drops
		sent := false
		for retry := 0; retry < maxSendRetries && !sent; retry++ {
			select {
			case client.Send <- message.Data:
				sent = true
			case <-time.After(sendTimeout):
				// Timeout, will retry if retries remaining
				if retry == maxSendRetries-1 {
					log.Printf("[WARN] Hub: dropped message for client %s (buffer full after %d retries), conv=%s, msgLen=%d",
						client.ID, maxSendRetries, message.ConversationID, len(message.Data))
				}
			case <-client.Done:
				// Client is disconnecting, skip
				sent = true // Mark as sent to break retry loop
			}
		}
	}
}

// Register queues a client for registration
func (h *Hub) Register(client *Client) {
	h.register <- client
}

// Unregister queues a client for unregistration
func (h *Hub) Unregister(client *Client) {
	h.unregister <- client
}

// Subscribe queues a subscription request
func (h *Hub) Subscribe(req *SubscribeRequest) {
	h.subscribe <- req
}

// BroadcastToConversation queues a message to be broadcast to all clients in a conversation
func (h *Hub) BroadcastToConversation(convID uuid.UUID, data []byte) {
	h.broadcast <- &BroadcastMessage{
		ConversationID: convID,
		Data:           data,
	}
}

// GetClientsForConversation returns all clients connected to a specific conversation
func (h *Hub) GetClientsForConversation(convID uuid.UUID) []*Client {
	h.mu.RLock()
	defer h.mu.RUnlock()

	clients, exists := h.conversations[convID]
	if !exists {
		return []*Client{}
	}

	result := make([]*Client, 0, len(clients))
	for _, client := range clients {
		result = append(result, client)
	}

	return result
}

// UnsubscribeClient removes a client from their current subscription
func (h *Hub) UnsubscribeClient(client *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if oldConvID, exists := h.subscriptions[client.ID]; exists {
		h.removeClientFromConversation(client.ID, oldConvID)
		delete(h.subscriptions, client.ID)
	}
	client.ConversationID = uuid.Nil
}

// BroadcastToClaudeSession sends a message to all clients watching a specific Claude session
// This is used for streaming output to ensure all connected clients receive the response
func (h *Hub) BroadcastToClaudeSession(sessionID uuid.UUID, data []byte) {
	h.mu.RLock()
	defer h.mu.RUnlock()

	sessionStr := sessionID.String()
	for _, client := range h.clients {
		// Match by ClaudeSessionID (UUID) or HistorySessionID (string)
		if client.ClaudeSessionID == sessionID || client.HistorySessionID == sessionStr {
			select {
			case client.Send <- data:
			default:
				// Skip if buffer is full
			}
		}
	}
}
