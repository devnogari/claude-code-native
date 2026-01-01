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
)

// IncomingMessage represents a message received from the WebSocket client
type IncomingMessage struct {
	// Type is the message type (chat, stop, ping)
	Type string `json:"type"`
	// Content is the message content (optional, used for chat messages)
	Content string `json:"content,omitempty"`
}

// OutgoingMessage represents a message sent to the WebSocket client
type OutgoingMessage struct {
	// Type is the message type (stream, status, error, complete, pong)
	Type string `json:"type"`
	// ConversationID identifies which conversation this message belongs to
	ConversationID string `json:"conversation_id,omitempty"`
	// Content is the message content (used for stream messages)
	Content string `json:"content,omitempty"`
	// Error contains the error message (used for error type)
	Error string `json:"error,omitempty"`
	// Status contains the status message (used for status type)
	Status string `json:"status,omitempty"`
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
