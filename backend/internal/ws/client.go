package ws

import (
	"github.com/gofrs/uuid/v5"
	"github.com/gofiber/contrib/websocket"
)

// Client represents a WebSocket client connection
type Client struct {
	// ID is the unique identifier for this client connection
	ID uuid.UUID

	// UserID is the authenticated user's ID
	UserID uuid.UUID

	// ConversationID is the conversation this client is connected to
	ConversationID uuid.UUID

	// Conn is the underlying WebSocket connection
	Conn *websocket.Conn

	// Send is a buffered channel for outgoing messages
	Send chan []byte

	// Done is closed when the client is unregistered, signaling goroutines to stop
	Done chan struct{}
}

// NewClient creates a new WebSocket client
func NewClient(userID, conversationID uuid.UUID, conn *websocket.Conn) *Client {
	clientID := uuid.Must(uuid.NewV7())
	return &Client{
		ID:             clientID,
		UserID:         userID,
		ConversationID: conversationID,
		Conn:           conn,
		Send:           make(chan []byte, 256),
		Done:           make(chan struct{}),
	}
}
