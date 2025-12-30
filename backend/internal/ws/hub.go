package ws

import (
	"sync"

	"github.com/gofrs/uuid/v5"
)

// BroadcastMessage represents a message to be broadcast to a conversation
type BroadcastMessage struct {
	ConversationID uuid.UUID
	Data           []byte
}

// Hub maintains the set of active clients and broadcasts messages
type Hub struct {
	// clients maps client IDs to their Client instances
	clients map[uuid.UUID]*Client

	// conversations maps conversation IDs to a map of client IDs to Clients
	conversations map[uuid.UUID]map[uuid.UUID]*Client

	// register channel for client registration requests
	register chan *Client

	// unregister channel for client unregistration requests
	unregister chan *Client

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
		register:      make(chan *Client),
		unregister:    make(chan *Client),
		broadcast:     make(chan *BroadcastMessage),
	}
}

// Run starts the hub's main loop processing register, unregister, and broadcast messages
// This should be called as a goroutine
func (h *Hub) Run() {
	for {
		select {
		case client := <-h.register:
			h.registerClient(client)

		case client := <-h.unregister:
			h.unregisterClient(client)

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

// broadcastMessage sends a message to all clients in a conversation
func (h *Hub) broadcastMessage(message *BroadcastMessage) {
	h.mu.RLock()
	defer h.mu.RUnlock()

	clients, exists := h.conversations[message.ConversationID]
	if !exists {
		return
	}

	for _, client := range clients {
		// Non-blocking send to avoid blocking if client's buffer is full
		select {
		case client.Send <- message.Data:
		default:
			// Client's buffer is full, skip this message
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
