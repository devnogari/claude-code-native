package ws

import (
	"context"
	"encoding/json"
	"time"

	"github.com/devnogari/claude-code-native/backend/internal/claude"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/conversation"
	"github.com/devnogari/claude-code-native/backend/internal/message"
	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/gofiber/contrib/websocket"
	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
	"go.uber.org/zap"
)

const (
	// writeWait is the time allowed to write a message to the peer
	writeWait = 10 * time.Second

	// pongWait is the time allowed to read the next pong message from the peer
	pongWait = 60 * time.Second

	// pingPeriod is the period for sending ping messages (must be less than pongWait)
	pingPeriod = (pongWait * 9) / 10

	// maxMessageSize is the maximum message size allowed from peer
	maxMessageSize = 512 * 1024 // 512KB

	// maxContentLength is the maximum content length for chat messages
	maxContentLength = 100 * 1024 // 100KB
)

// ConversationRepository defines the interface for conversation data access
type ConversationRepository interface {
	FindByID(ctx context.Context, id uuid.UUID) (*conversation.Conversation, error)
	Update(ctx context.Context, c *conversation.Conversation) error
}

// ProjectRepository defines the interface for project data access
type ProjectRepository interface {
	FindByID(ctx context.Context, id uuid.UUID) (*project.Project, error)
}

// MessageRepository defines the interface for message data access
type MessageRepository interface {
	Create(ctx context.Context, m *message.Message) error
	FindByConversationID(ctx context.Context, conversationID uuid.UUID) ([]*message.Message, error)
	GetMaxSequenceNum(ctx context.Context, conversationID uuid.UUID) (int, error)
}

// Handler handles WebSocket connections and message routing
type Handler struct {
	hub       *Hub
	config    *config.Config
	logger    *zap.Logger
	claudeMgr *claude.Manager
	convRepo  ConversationRepository
	projRepo  ProjectRepository
	msgRepo   MessageRepository
}

// NewHandler creates a new WebSocket handler with all dependencies
func NewHandler(
	hub *Hub,
	config *config.Config,
	logger *zap.Logger,
	claudeMgr *claude.Manager,
	convRepo ConversationRepository,
	projRepo ProjectRepository,
	msgRepo MessageRepository,
) *Handler {
	return &Handler{
		hub:       hub,
		config:    config,
		logger:    logger,
		claudeMgr: claudeMgr,
		convRepo:  convRepo,
		projRepo:  projRepo,
		msgRepo:   msgRepo,
	}
}

// Upgrade checks if the request is a WebSocket upgrade request
// It should be used as middleware before HandleConnection
func (h *Handler) Upgrade(c *fiber.Ctx) error {
	if websocket.IsWebSocketUpgrade(c) {
		return c.Next()
	}
	return fiber.ErrUpgradeRequired
}

// HandleConnection is the main WebSocket handler
// It creates a client, registers with the hub, and manages read/write pumps
func (h *Handler) HandleConnection(c *websocket.Conn) {
	// Extract user ID and conversation ID from context (set by middleware/route params)
	userIDStr := c.Locals("userID")
	convIDStr := c.Params("conversationID")

	if userIDStr == nil || convIDStr == "" {
		h.logger.Error("missing required parameters",
			zap.Any("userID", userIDStr),
			zap.String("conversationID", convIDStr))
		h.sendError(c, "missing required parameters")
		return
	}

	userIDString, ok := userIDStr.(string)
	if !ok {
		h.logger.Error("invalid user ID type", zap.Any("userID", userIDStr))
		h.sendError(c, "invalid user ID")
		return
	}

	userID, err := uuid.FromString(userIDString)
	if err != nil {
		h.logger.Error("invalid user ID format", zap.String("userID", userIDString), zap.Error(err))
		h.sendError(c, "invalid user ID format")
		return
	}

	convID, err := uuid.FromString(convIDStr)
	if err != nil {
		h.logger.Error("invalid conversation ID", zap.String("conversationID", convIDStr), zap.Error(err))
		h.sendError(c, "invalid conversation ID")
		return
	}

	// Verify the conversation exists
	ctx := context.Background()
	conv, err := h.convRepo.FindByID(ctx, convID)
	if err != nil {
		h.logger.Error("conversation not found", zap.String("conversationID", convIDStr), zap.Error(err))
		h.sendError(c, "conversation not found")
		return
	}

	// Verify the project exists and get the working directory
	proj, err := h.projRepo.FindByID(ctx, conv.ProjectID)
	if err != nil {
		h.logger.Error("project not found", zap.String("projectID", conv.ProjectID.String()), zap.Error(err))
		h.sendError(c, "project not found")
		return
	}

	// Verify the user owns the project (authorization check)
	if proj.UserID != userID {
		h.logger.Warn("unauthorized access attempt",
			zap.String("userID", userID.String()),
			zap.String("projectUserID", proj.UserID.String()),
			zap.String("conversationID", convIDStr))
		h.sendError(c, "unauthorized")
		return
	}

	// Create a new client
	client := NewClient(userID, convID, c)

	// Store the project path in client for later use
	// We can use the Client's Conn.Locals for this
	c.Locals("projectPath", proj.Path)

	h.logger.Info("client connected",
		zap.String("clientID", client.ID.String()),
		zap.String("userID", userID.String()),
		zap.String("conversationID", convID.String()),
		zap.String("projectPath", proj.Path))

	// Register client with hub
	h.hub.Register(client)

	// Ensure client is unregistered when connection closes
	defer func() {
		h.hub.Unregister(client)
		h.logger.Info("client disconnected",
			zap.String("clientID", client.ID.String()),
			zap.String("conversationID", convID.String()))
	}()

	// Send connection status (non-blocking)
	h.sendStatusToClient(client, "connected")

	// Start read and write pumps
	go h.writePump(client)
	h.readPump(client, proj.Path)
}

// readPump reads messages from the WebSocket connection
// Note: Client unregistration is handled by HandleConnection's defer, not here
func (h *Handler) readPump(client *Client, projectPath string) {
	client.Conn.SetReadLimit(maxMessageSize)
	_ = client.Conn.SetReadDeadline(time.Now().Add(pongWait))
	client.Conn.SetPongHandler(func(string) error {
		_ = client.Conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	for {
		_, data, err := client.Conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				h.logger.Error("websocket read error",
					zap.String("clientID", client.ID.String()),
					zap.Error(err))
			}
			break
		}

		// Parse incoming message
		var msg IncomingMessage
		if err := json.Unmarshal(data, &msg); err != nil {
			h.logger.Warn("failed to parse message",
				zap.String("clientID", client.ID.String()),
				zap.Error(err))
			h.sendErrorToClient(client, "invalid message format")
			continue
		}

		// Handle message based on type
		h.handleMessage(client, &msg, projectPath)
	}
}

// writePump writes messages to the WebSocket connection
func (h *Handler) writePump(client *Client) {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		_ = client.Conn.Close()
	}()

	for {
		select {
		case message, ok := <-client.Send:
			_ = client.Conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				// Channel closed
				_ = client.Conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			if err := client.Conn.WriteMessage(websocket.TextMessage, message); err != nil {
				h.logger.Error("websocket write error",
					zap.String("clientID", client.ID.String()),
					zap.Error(err))
				return
			}

		case <-ticker.C:
			_ = client.Conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := client.Conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// handleMessage routes incoming messages to appropriate handlers
func (h *Handler) handleMessage(client *Client, msg *IncomingMessage, projectPath string) {
	switch msg.Type {
	case MessageTypeChat:
		h.handleChatMessage(client, msg.Content, projectPath)
	case MessageTypeStop:
		h.handleStopMessage(client)
	case MessageTypePing:
		h.handlePingMessage(client)
	default:
		h.logger.Warn("unknown message type",
			zap.String("clientID", client.ID.String()),
			zap.String("type", msg.Type))
		h.sendErrorToClient(client, "unknown message type: "+msg.Type)
	}
}

// handleChatMessage processes a chat message from the client
func (h *Handler) handleChatMessage(client *Client, content, projectPath string) {
	if content == "" {
		h.sendErrorToClient(client, "empty message content")
		return
	}

	if len(content) > maxContentLength {
		h.sendErrorToClient(client, "message content too large")
		return
	}

	ctx := context.Background()
	convID := client.ConversationID

	h.logger.Info("received chat message",
		zap.String("clientID", client.ID.String()),
		zap.String("conversationID", convID.String()),
		zap.Int("contentLength", len(content)))

	// Get next sequence number
	maxSeq, err := h.msgRepo.GetMaxSequenceNum(ctx, convID)
	if err != nil {
		h.logger.Error("failed to get max sequence number", zap.Error(err))
		h.sendErrorToClient(client, "internal error")
		return
	}

	// Save the user message
	userMsg := &message.Message{
		ConversationID: convID,
		Role:           message.RoleUser,
		Content:        content,
		SequenceNum:    maxSeq + 1,
	}

	if err := h.msgRepo.Create(ctx, userMsg); err != nil {
		h.logger.Error("failed to save user message", zap.Error(err))
		h.sendErrorToClient(client, "failed to save message")
		return
	}

	// Send status update
	h.sendStatusToClient(client, "processing")

	// Get or create Claude process for this conversation
	process, err := h.claudeMgr.CreateProcess(convID, projectPath, nil)
	if err != nil {
		h.logger.Error("failed to create claude process", zap.Error(err))
		h.sendErrorToClient(client, "failed to start Claude")
		return
	}

	// Check if process is already running, if not it needs to be started
	// Note: The actual CLI starting logic will be implemented in Task 3.5
	if process.GetStatus() == claude.ProcessStatusIdle {
		process.SetStatus(claude.ProcessStatusRunning)
		// TODO: Task 3.5 will implement the actual process.Start() method
		// that launches the claude CLI subprocess
	}

	// Send the message to Claude's input channel
	process.SendInput(content)

	// Stream process output to client
	go h.streamProcessOutput(client, process)
}

// handleStopMessage stops the current Claude process
func (h *Handler) handleStopMessage(client *Client) {
	convID := client.ConversationID

	h.logger.Info("stopping claude process",
		zap.String("clientID", client.ID.String()),
		zap.String("conversationID", convID.String()))

	if err := h.claudeMgr.StopProcess(convID); err != nil {
		h.logger.Warn("failed to stop claude process",
			zap.String("conversationID", convID.String()),
			zap.Error(err))
	}

	h.sendStatusToClient(client, "stopped")
}

// handlePingMessage responds with a pong
func (h *Handler) handlePingMessage(client *Client) {
	h.sendPongToClient(client)
}

// streamProcessOutput streams Claude CLI output to the WebSocket client
// It monitors the client.Done channel to detect client disconnection and avoid race conditions
func (h *Handler) streamProcessOutput(client *Client, process *claude.Process) {
	ctx := context.Background()
	convID := client.ConversationID
	var assistantContent string

	// Use labeled loop for clean exit without goto
streamLoop:
	for {
		select {
		case <-client.Done:
			// Client disconnected, stop streaming to avoid race condition
			h.logger.Info("client disconnected during streaming",
				zap.String("conversationID", convID.String()))
			break streamLoop

		case <-process.Done:
			// Process finished
			break streamLoop

		case output, ok := <-process.Output:
			if !ok {
				// Channel closed, process finished
				break streamLoop
			}

			// Only accumulate stdout content as the response
			if output.Type == "stdout" {
				assistantContent += output.Content
			}

			// Send stream message to client with the content (check Done first)
			select {
			case <-client.Done:
				break streamLoop
			default:
				h.sendStreamToClient(client, output.Content)
			}

		case err, ok := <-process.Error:
			if !ok {
				continue
			}
			h.logger.Error("claude process error",
				zap.String("conversationID", convID.String()),
				zap.Error(err))
			// Check if client is still connected before sending error
			select {
			case <-client.Done:
				break streamLoop
			default:
				h.sendErrorToClient(client, "Claude error: "+err.Error())
			}
		}
	}

	// Save the assistant message if we received any content
	if assistantContent != "" {
		maxSeq, err := h.msgRepo.GetMaxSequenceNum(ctx, convID)
		if err != nil {
			h.logger.Error("failed to get max sequence number", zap.Error(err))
		} else {
			assistantMsg := &message.Message{
				ConversationID: convID,
				Role:           message.RoleAssistant,
				Content:        assistantContent,
				SequenceNum:    maxSeq + 1,
			}

			if err := h.msgRepo.Create(ctx, assistantMsg); err != nil {
				h.logger.Error("failed to save assistant message", zap.Error(err))
			}
		}
	}

	// Send completion message only if client is still connected
	select {
	case <-client.Done:
		// Client already disconnected, don't try to send
	default:
		h.sendCompleteToClient(client)
	}
}

// Helper methods for sending messages

func (h *Handler) sendError(c *websocket.Conn, errMsg string) {
	msg := createOutgoingError(errMsg)
	data, _ := json.Marshal(msg)
	_ = c.WriteMessage(websocket.TextMessage, data)
}

func (h *Handler) sendErrorToClient(client *Client, errMsg string) {
	msg := createOutgoingError(errMsg)
	data, _ := json.Marshal(msg)
	select {
	case client.Send <- data:
	default:
		// Buffer full, skip
	}
}

func (h *Handler) sendStatusToClient(client *Client, status string) {
	msg := createOutgoingStatus(status)
	data, _ := json.Marshal(msg)
	select {
	case client.Send <- data:
	default:
	}
}

func (h *Handler) sendPongToClient(client *Client) {
	msg := createOutgoingPong()
	data, _ := json.Marshal(msg)
	select {
	case client.Send <- data:
	default:
	}
}

func (h *Handler) sendStreamToClient(client *Client, content string) {
	msg := createOutgoingStream(client.ConversationID, content)
	data, _ := json.Marshal(msg)
	select {
	case client.Send <- data:
	default:
	}
}

func (h *Handler) sendCompleteToClient(client *Client) {
	msg := createOutgoingComplete(client.ConversationID)
	data, _ := json.Marshal(msg)
	select {
	case client.Send <- data:
	default:
	}
}
