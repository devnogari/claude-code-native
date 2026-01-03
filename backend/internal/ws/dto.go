package ws

// Message type constants for WebSocket communication
const (
	// MessageTypeAuth is sent by client as first message to authenticate
	// More secure than query parameter as tokens aren't logged in URLs
	MessageTypeAuth = "auth"
	// MessageTypeChat is sent by client to send a chat message
	MessageTypeChat = "chat"
	// MessageTypeStream is sent by server with streaming response content
	MessageTypeStream = "stream"
	// MessageTypeStatus is sent by server to indicate processing status
	MessageTypeStatus = "status"
	// MessageTypeError is sent by server when an error occurs
	MessageTypeError = "error"
	// MessageTypeStop is sent by client to stop the current Claude process
	MessageTypeStop = "stop"
	// MessageTypeComplete is sent by server when Claude response is complete
	MessageTypeComplete = "complete"
	// MessageTypePing is sent by client to check connection
	MessageTypePing = "ping"
	// MessageTypePong is sent by server in response to ping
	MessageTypePong = "pong"
	// MessageTypeQueueAdd is sent by server when a message is added to the queue
	MessageTypeQueueAdd = "queue_add"
	// MessageTypeQueueRemove is sent by server when a message is removed from the queue
	MessageTypeQueueRemove = "queue_remove"
	// MessageTypeQueueSync is sent by server to sync the full queue state
	MessageTypeQueueSync = "queue_sync"

	// New message types for unified WebSocket
	// MessageTypeSubscribe is sent by client to subscribe to a conversation
	MessageTypeSubscribe = "subscribe"
	// MessageTypeUnsubscribe is sent by client to unsubscribe from current conversation
	MessageTypeUnsubscribe = "unsubscribe"
	// MessageTypeSubscribed is sent by server to confirm subscription
	MessageTypeSubscribed = "subscribed"
	// MessageTypeSessionState is sent by server with full session state
	MessageTypeSessionState = "session_state"
)

// ImageContent represents an image attachment in a chat message
type ImageContent struct {
	// Type is the image source type (currently only "base64" is supported)
	Type string `json:"type"`
	// MediaType is the MIME type of the image (e.g., "image/png", "image/jpeg")
	MediaType string `json:"media_type"`
	// Data is the base64-encoded image data
	Data string `json:"data"`
}

// IncomingMessage represents a message received from the WebSocket client
type IncomingMessage struct {
	// Type is the message type (chat, stop, ping, subscribe, unsubscribe, auth)
	Type string `json:"type"`
	// Content is the message content (optional, used for chat messages)
	Content string `json:"content,omitempty"`
	// Images is a list of image attachments (optional, used for chat messages with images)
	Images []ImageContent `json:"images,omitempty"`
	// Subscribe fields (for subscribe message type)
	// ConversationID is the conversation to subscribe to
	ConversationID string `json:"conversation_id,omitempty"`
	// SessionID is the Claude session ID (for filesystem sessions)
	SessionID string `json:"session_id,omitempty"`
	// EncodedPath is the encoded project path (for filesystem sessions)
	EncodedPath string `json:"encoded_path,omitempty"`
}

// OutgoingMessage represents a message sent to the WebSocket client
type OutgoingMessage struct {
	// Type is the message type (stream, status, error, complete, pong, subscribed, session_state)
	Type string `json:"type"`
	// ConversationID identifies which conversation this message belongs to
	ConversationID string `json:"conversation_id,omitempty"`
	// Content is the message content (used for stream messages)
	Content string `json:"content,omitempty"`
	// Error contains the error message (used for error type)
	Error string `json:"error,omitempty"`
	// Status contains the status message (used for status type)
	Status string `json:"status,omitempty"`
	// Payload is the structured payload for subscribed, session_state messages
	Payload interface{} `json:"payload,omitempty"`
}

// Helper functions to create outgoing messages

// createOutgoingError creates an error message
func createOutgoingError(errMsg string) *OutgoingMessage {
	return &OutgoingMessage{
		Type:  MessageTypeError,
		Error: errMsg,
	}
}

// createOutgoingStatus creates a status message
func createOutgoingStatus(status string) *OutgoingMessage {
	return &OutgoingMessage{
		Type:   MessageTypeStatus,
		Status: status,
	}
}

// createOutgoingPong creates a pong message
func createOutgoingPong() *OutgoingMessage {
	return &OutgoingMessage{
		Type: MessageTypePong,
	}
}

// createOutgoingStream creates a stream message with content
func createOutgoingStream(convID interface{ String() string }, content string) *OutgoingMessage {
	return &OutgoingMessage{
		Type:           MessageTypeStream,
		ConversationID: convID.String(),
		Content:        content,
	}
}

// createOutgoingComplete creates a completion message
func createOutgoingComplete(convID interface{ String() string }) *OutgoingMessage {
	return &OutgoingMessage{
		Type:           MessageTypeComplete,
		ConversationID: convID.String(),
	}
}

// QueueMessage represents a WebSocket message for queue operations
type QueueMessage struct {
	Type    string      `json:"type"`
	Payload interface{} `json:"payload"`
}

// createQueueAddMessage creates a queue_add message
func createQueueAddMessage(payload interface{}) *QueueMessage {
	return &QueueMessage{
		Type:    MessageTypeQueueAdd,
		Payload: payload,
	}
}

// createQueueRemoveMessage creates a queue_remove message
func createQueueRemoveMessage(payload interface{}) *QueueMessage {
	return &QueueMessage{
		Type:    MessageTypeQueueRemove,
		Payload: payload,
	}
}

// createQueueSyncMessage creates a queue_sync message
func createQueueSyncMessage(payload interface{}) *QueueMessage {
	return &QueueMessage{
		Type:    MessageTypeQueueSync,
		Payload: payload,
	}
}

// SubscribedPayload is sent by server to confirm subscription
type SubscribedPayload struct {
	ConversationID string `json:"conversationId"`
}

// SessionStateType represents the state of a Claude session
type SessionStateType string

const (
	// SessionStateIdle indicates the session is idle (no active processing)
	SessionStateIdle SessionStateType = "idle"
	// SessionStateQueued indicates there are queued messages waiting
	SessionStateQueued SessionStateType = "queued"
	// SessionStateStreaming indicates the session is actively streaming a response
	SessionStateStreaming SessionStateType = "streaming"
)

// SessionStatePayload is sent by server with full session state
type SessionStatePayload struct {
	ConversationID string           `json:"conversationId"`
	SessionState   SessionStateType `json:"sessionState"`
	IsStreaming    bool             `json:"isStreaming"`
	Todos          []TodoItem       `json:"todos"`
	Queue          []interface{}    `json:"queue"` // Use interface{} to avoid circular import with queue package
}

// TodoItem represents a todo from Claude Code's TodoWrite
type TodoItem struct {
	Content    string  `json:"content"`
	Status     string  `json:"status"`
	ActiveForm *string `json:"activeForm,omitempty"`
	Priority   *string `json:"priority,omitempty"`
	ID         *string `json:"id,omitempty"`
}
