package ws

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/devnogari/claude-code-native/backend/internal/claude"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/conversation"
	"github.com/devnogari/claude-code-native/backend/internal/message"
	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/devnogari/claude-code-native/backend/internal/queue"
	"github.com/gofiber/contrib/websocket"
	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
	"github.com/golang-jwt/jwt/v5"
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
	// Increased to 50MB to support large image attachments (base64 encoded iPad screenshots can be 20MB+)
	maxMessageSize = 50 * 1024 * 1024 // 50MB

	// maxContentLength is the maximum content length for chat messages
	maxContentLength = 100 * 1024 // 100KB

	// dbOperationTimeout is the timeout for database operations
	dbOperationTimeout = 5 * time.Second
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
	hub          *Hub
	config       *config.Config
	logger       *zap.Logger
	claudeMgr    *claude.Manager
	convRepo     ConversationRepository
	projRepo     ProjectRepository
	msgRepo      MessageRepository
	queueService queue.Service
	historyCache *claude.HistoryCache // For unified history watch
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
	queueService queue.Service,
	historyCache *claude.HistoryCache,
) *Handler {
	return &Handler{
		hub:          hub,
		config:       config,
		logger:       logger,
		claudeMgr:    claudeMgr,
		convRepo:     convRepo,
		projRepo:     projRepo,
		msgRepo:      msgRepo,
		queueService: queueService,
		historyCache: historyCache,
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
	convIDStr := c.Params("conversationID")

	// Try to get userID from middleware (query param auth - backward compatibility)
	userIDStr := c.Locals("userID")

	// If no userID from middleware, wait for auth message (more secure approach)
	var userID uuid.UUID
	if userIDStr == nil {
		// Wait for first message which should be auth
		authUserID, err := h.waitForAuthMessage(c)
		if err != nil {
			h.logger.Error("auth failed", zap.Error(err))
			h.sendError(c, "authentication failed: "+err.Error())
			return
		}
		userID = authUserID
	} else {
		userIDString, ok := userIDStr.(string)
		if !ok {
			h.logger.Error("invalid user ID type", zap.Any("userID", userIDStr))
			h.sendError(c, "invalid user ID")
			return
		}

		var err error
		userID, err = uuid.FromString(userIDString)
		if err != nil {
			h.logger.Error("invalid user ID format", zap.String("userID", userIDString), zap.Error(err))
			h.sendError(c, "invalid user ID format")
			return
		}
	}

	if convIDStr == "" {
		h.logger.Error("missing conversation ID")
		h.sendError(c, "missing conversation ID")
		return
	}

	// Check if this is a filesystem-based session
	// Filesystem sessions have a 'project' query parameter with the encoded project path
	var convID uuid.UUID
	var claudeSessionID uuid.UUID
	var projectPath string
	var isFilesystemSession bool

	encodedPath := c.Query("project")
	if encodedPath != "" {
		// Filesystem-based session: sessionId with ?project=encodedPath query param
		isFilesystemSession = true

		// Parse session ID as UUID
		sessionID, err := uuid.FromString(convIDStr)
		if err != nil {
			h.logger.Error("invalid session ID", zap.String("sessionID", convIDStr), zap.Error(err))
			h.sendError(c, "invalid session ID")
			return
		}

		// Decode project path (replace - with /)
		projectPath = decodeProjectPath(encodedPath)

		// Validate decoded path exists
		if _, err := os.Stat(projectPath); os.IsNotExist(err) {
			h.logger.Error("decoded project path does not exist",
				zap.String("encodedPath", encodedPath),
				zap.String("decodedPath", projectPath))
			h.sendError(c, "project path does not exist: "+projectPath)
			return
		}

		// Use session ID for both conversation and claude session
		convID = sessionID
		claudeSessionID = sessionID

		h.logger.Info("filesystem session connected",
			zap.String("sessionID", sessionID.String()),
			zap.String("encodedPath", encodedPath),
			zap.String("projectPath", projectPath))
	} else {
		// Legacy database-based session (UUID only)
		isFilesystemSession = false
		var err error
		convID, err = uuid.FromString(convIDStr)
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

		projectPath = proj.Path

		// Validate project path exists
		if _, err := os.Stat(projectPath); os.IsNotExist(err) {
			h.logger.Error("project path does not exist",
				zap.String("projectID", proj.ID.String()),
				zap.String("projectPath", projectPath))
			h.sendError(c, "project path does not exist: "+projectPath)
			return
		}

		// Determine the Claude session ID to use
		// If conversation was synced from Claude history, use the original session ID
		// Otherwise, use the conversation ID as session ID
		if conv.ClaudeSession != nil && *conv.ClaudeSession != "" {
			parsedID, err := uuid.FromString(*conv.ClaudeSession)
			if err == nil {
				claudeSessionID = parsedID
				h.logger.Debug("using claude session from sync",
					zap.String("conversationID", convID.String()),
					zap.String("claudeSession", claudeSessionID.String()))
			} else {
				claudeSessionID = convID
			}
		} else {
			claudeSessionID = convID
		}
	}

	// Create a new client
	client := NewClient(userID, convID, c)

	// Store project path and claude session ID on client struct for recovery operations
	// This is needed for handleCompactionRecovery to access these values
	client.ProjectPath = projectPath
	client.ClaudeSessionID = claudeSessionID
	client.IsFilesystemSession = isFilesystemSession

	// Also store in Locals for backward compatibility (some handlers may still read from Locals)
	c.Locals("projectPath", projectPath)
	c.Locals("claudeSessionID", claudeSessionID.String())
	c.Locals("isFilesystemSession", isFilesystemSession)

	h.logger.Info("client connected",
		zap.String("clientID", client.ID.String()),
		zap.String("userID", userID.String()),
		zap.String("conversationID", convID.String()),
		zap.String("projectPath", projectPath),
		zap.Bool("isFilesystemSession", isFilesystemSession))

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

	// Send queue sync to client (non-blocking)
	h.sendQueueSyncToClient(client)

	// Start read and write pumps
	go h.writePump(client)
	h.readPump(client, projectPath, claudeSessionID, isFilesystemSession)
}

// readPump reads messages from the WebSocket connection
// Note: Client unregistration is handled by HandleConnection's defer, not here
func (h *Handler) readPump(client *Client, projectPath string, claudeSessionID uuid.UUID, isFilesystemSession bool) {
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

		h.logger.Info("readPump: received WebSocket frame",
			zap.String("clientID", client.ID.String()),
			zap.Int("dataLen", len(data)))

		// Parse incoming message
		var msg IncomingMessage
		if err := json.Unmarshal(data, &msg); err != nil {
			h.logger.Warn("failed to parse message",
				zap.String("clientID", client.ID.String()),
				zap.String("dataPreview", string(data[:min(len(data), 200)])),
				zap.Error(err))
			h.sendErrorToClient(client, "invalid message format")
			continue
		}

		h.logger.Info("readPump: parsed message successfully",
			zap.String("clientID", client.ID.String()),
			zap.String("msgType", msg.Type))

		// Handle message based on type
		h.handleMessage(client, &msg, projectPath, claudeSessionID, isFilesystemSession)
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
func (h *Handler) handleMessage(client *Client, msg *IncomingMessage, projectPath string, claudeSessionID uuid.UUID, isFilesystemSession bool) {
	h.logger.Info("handleMessage() ENTRY",
		zap.String("clientID", client.ID.String()),
		zap.String("msgType", msg.Type),
		zap.Int("contentLen", len(msg.Content)),
		zap.Int("imageCount", len(msg.Images)))

	switch msg.Type {
	case MessageTypeChat:
		h.logger.Info("handleMessage: routing to handleChatMessage")
		h.handleChatMessage(client, msg.Content, msg.Images, projectPath, claudeSessionID, isFilesystemSession)
	case MessageTypeStop:
		h.handleStopMessage(client, claudeSessionID)
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
func (h *Handler) handleChatMessage(client *Client, content string, images []ImageContent, projectPath string, claudeSessionID uuid.UUID, isFilesystemSession bool) {
	if content == "" && len(images) == 0 {
		h.sendErrorToClient(client, "empty message content")
		return
	}

	if len(content) > maxContentLength {
		h.sendErrorToClient(client, "message content too large")
		return
	}

	convID := client.ConversationID

	h.logger.Info("received chat message",
		zap.String("clientID", client.ID.String()),
		zap.String("conversationID", convID.String()),
		zap.String("claudeSessionID", claudeSessionID.String()),
		zap.Int("contentLength", len(content)),
		zap.Int("imageCount", len(images)),
		zap.String("projectPath", projectPath),
		zap.Bool("isFilesystemSession", isFilesystemSession))

	// Save images to temporary files for Claude CLI
	var imagePaths []string
	if len(images) > 0 {
		var err error
		imagePaths, err = h.saveImagesToTemp(images)
		if err != nil {
			h.logger.Error("failed to save images", zap.Error(err))
			h.sendErrorToClient(client, "failed to process images")
			return
		}
	}

	// Track whether streamProcessOutput was started - if not, we must clean up temp files
	// Cleanup is normally done in streamProcessOutput after Claude CLI finishes reading them
	var streamStarted bool
	defer func() {
		if !streamStarted && len(imagePaths) > 0 {
			h.logger.Debug("cleaning up temp images due to early return", zap.Int("count", len(imagePaths)))
			for _, path := range imagePaths {
				if err := os.Remove(path); err != nil {
					h.logger.Warn("failed to remove temp image", zap.String("path", path), zap.Error(err))
				}
			}
		}
	}()

	// For database-based sessions, save the user message
	// For filesystem sessions, Claude CLI manages its own history
	if !isFilesystemSession {
		ctx := context.Background()

		// Get next sequence number
		h.logger.Debug("getting max sequence number")
		maxSeq, err := h.msgRepo.GetMaxSequenceNum(ctx, convID)
		if err != nil {
			h.logger.Error("failed to get max sequence number", zap.Error(err))
			h.sendErrorToClient(client, "internal error")
			return
		}

		// Save the user message (content only, images are not persisted in DB)
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
	}

	// Validate project path exists before starting Claude process
	if _, err := os.Stat(projectPath); os.IsNotExist(err) {
		h.logger.Error("project path does not exist",
			zap.String("projectPath", projectPath),
			zap.String("conversationID", convID.String()))
		h.sendErrorToClient(client, "project path does not exist: "+projectPath)
		return
	}

	// Send status update
	h.sendStatusToClient(client, "processing")
	h.logger.Debug("sent processing status")

	// Get or create Claude process for this conversation
	// Use claudeSessionID (from ClaudeSession if synced, or convID if new)
	h.logger.Debug("creating claude process",
		zap.String("projectPath", projectPath),
		zap.String("claudeSessionID", claudeSessionID.String()))
	process, err := h.claudeMgr.CreateProcess(claudeSessionID, projectPath, nil)
	if err != nil {
		h.logger.Error("failed to create claude process", zap.Error(err))
		h.sendErrorToClient(client, "failed to start Claude")
		return
	}

	// Check if process is already running in interactive mode
	// If so, send message via stdin (queued message support)
	if process.GetStatus() == claude.ProcessStatusRunning {
		if process.IsInteractive() {
			h.logger.Info("sending message to running interactive process (queued)",
				zap.String("claudeSessionID", claudeSessionID.String()),
				zap.Int("contentLength", len(content)))

			// Track images for cleanup when process closes
			process.TrackImages(imagePaths)

			if err := process.SendMessage(content, imagePaths); err != nil {
				h.logger.Error("failed to send message to interactive process", zap.Error(err))
				h.sendErrorToClient(client, "failed to send message: "+err.Error())
				return
			}
			// Message sent successfully - output will be streamed by existing goroutine
			// Image cleanup handled by process.Close() via TrackImages
			streamStarted = true // Prevent early cleanup in defer
			h.logger.Info("queued message sent successfully",
				zap.String("claudeSessionID", claudeSessionID.String()))
			return
		}
		// Non-interactive process still running (legacy case)
		h.logger.Warn("process already running in non-interactive mode",
			zap.String("claudeSessionID", claudeSessionID.String()))
		h.sendErrorToClient(client, "previous request still processing")
		return
	}

	// Start Claude in interactive mode
	// This allows sending additional messages via stdin while streaming
	h.logger.Debug("starting claude in interactive mode", zap.Int("promptLen", len(content)), zap.Int("imageCount", len(imagePaths)))
	if err := process.StartInteractive(); err != nil {
		h.logger.Error("failed to start claude process", zap.Error(err))
		h.sendErrorToClient(client, "failed to start Claude CLI")
		return
	}

	// Track images for cleanup when process closes
	process.TrackImages(imagePaths)

	// Send the first message via stdin
	if err := process.SendMessage(content, imagePaths); err != nil {
		h.logger.Error("failed to send initial message", zap.Error(err))
		// Stop the process that was just started to clean up resources
		if stopErr := h.claudeMgr.StopProcess(claudeSessionID); stopErr != nil {
			h.logger.Warn("failed to stop process after send failure", zap.Error(stopErr))
		}
		h.sendErrorToClient(client, "failed to send message: "+err.Error())
		return
	}
	h.logger.Info("claude process started successfully (interactive mode)",
		zap.String("claudeSessionID", claudeSessionID.String()))

	// Stream process output to client
	// Image cleanup is now handled by process.Close() via TrackImages
	streamStarted = true
	go h.streamProcessOutput(client, process, isFilesystemSession, nil)
}

// handleStopMessage stops the current Claude process
func (h *Handler) handleStopMessage(client *Client, claudeSessionID uuid.UUID) {
	h.logger.Info("stopping claude process",
		zap.String("clientID", client.ID.String()),
		zap.String("claudeSessionID", claudeSessionID.String()))

	if err := h.claudeMgr.StopProcess(claudeSessionID); err != nil {
		h.logger.Warn("failed to stop claude process",
			zap.String("claudeSessionID", claudeSessionID.String()),
			zap.Error(err))
	}

	h.sendStatusToClient(client, "stopped")
}

// handlePingMessage responds with a pong
func (h *Handler) handlePingMessage(client *Client) {
	h.sendPongToClient(client)
}

// CompactionErrorPattern is the error message pattern for conversation too long errors
const CompactionErrorPattern = "Conversation too long"

// streamProcessOutput streams Claude CLI output to the WebSocket client
// It monitors the client.Done channel to detect client disconnection and avoid race conditions
// imagePaths are cleaned up after the process completes (cannot be done in handleChatMessage due to async execution)
func (h *Handler) streamProcessOutput(client *Client, process *claude.Process, isFilesystemSession bool, imagePaths []string) {
	h.streamProcessOutputWithRecovery(client, process, isFilesystemSession, imagePaths, 0)
}

// maxCompactionRetries is the maximum number of times to retry /compact after "Conversation too long" error
const maxCompactionRetries = 1

// streamProcessOutputWithRecovery streams Claude CLI output with automatic recovery for compaction errors
// retryCount tracks the number of recovery attempts to prevent infinite loops
func (h *Handler) streamProcessOutputWithRecovery(client *Client, process *claude.Process, isFilesystemSession bool, imagePaths []string, retryCount int) {
	convID := client.ConversationID
	// Use claudeSessionID for broadcasting - this is the actual session ID from the process
	// Different clients may have different conversationID but watch the same claudeSession
	claudeSessionID := process.ConversationID
	var assistantContent string
	var needsCompactionRecovery bool

	h.logger.Info("streamProcessOutputWithRecovery started",
		zap.String("conversationID", convID.String()),
		zap.String("claudeSessionID", claudeSessionID.String()),
		zap.Bool("isFilesystemSession", isFilesystemSession))

	// Clean up temp image files after Claude CLI finishes (regardless of success/failure)
	defer func() {
		for _, path := range imagePaths {
			if err := os.Remove(path); err != nil {
				h.logger.Warn("failed to remove temp image", zap.String("path", path), zap.Error(err))
			} else {
				h.logger.Debug("cleaned up temp image", zap.String("path", path))
			}
		}
	}()

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

			// Handle stderr - check for compaction errors
			if output.Type == "stderr" {
				if strings.Contains(output.Content, CompactionErrorPattern) {
					h.logger.Warn("detected compaction error, will attempt recovery",
						zap.String("conversationID", convID.String()),
						zap.String("error", output.Content),
						zap.Int("retryCount", retryCount))
					needsCompactionRecovery = true
					// Don't break yet - let the process finish naturally
				}

				// Filter out JSON lines from stderr - these are internal Claude CLI messages
				// that shouldn't be displayed in the UI (e.g., {"type":"system","subtype":"init"})
				trimmedContent := strings.TrimSpace(output.Content)
				if strings.HasPrefix(trimmedContent, "{") && strings.HasSuffix(trimmedContent, "}") {
					// Skip JSON lines - they're internal protocol messages
					continue
				}

				// Forward non-JSON stderr to all clients in the conversation
				// Using broadcast ensures reconnected clients receive stderr output
				h.broadcastStderrToConversation(convID, output.Content)
				continue
			}

			// Parse stream-json output and extract displayable text
			if output.Type == "stdout" {
				text, isDisplayable := claude.ParseStreamJSON(output.Content)
				if isDisplayable && text != "" {
					assistantContent += text

					// Broadcast stream message to all clients watching this Claude session
					// This ensures reconnected clients receive stream output even if
					// they weren't the original client that started the process
					h.broadcastStreamToSession(claudeSessionID, text)
				}
			}

		case err, ok := <-process.Error:
			if !ok {
				continue
			}
			h.logger.Error("claude process error",
				zap.String("claudeSessionID", claudeSessionID.String()),
				zap.Error(err))
			// Broadcast error to all clients watching this Claude session
			h.broadcastErrorToSession(claudeSessionID, "Claude error: "+err.Error())
		}
	}

	// Handle compaction recovery if needed
	if needsCompactionRecovery && retryCount < maxCompactionRetries {
		// Continue with recovery regardless of client connection state
		// The process needs to recover for future clients
		h.handleCompactionRecovery(client, process, isFilesystemSession, retryCount)
		return // Recovery handles completion message
	}

	// Save the assistant message if we received any content (database-based sessions only)
	// For filesystem sessions, Claude CLI manages its own history
	if assistantContent != "" && !isFilesystemSession {
		ctx := context.Background()
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

	// Broadcast completion message to all clients watching this Claude session
	// This ensures all connected clients (including reconnected ones) receive the complete signal
	h.broadcastCompleteToSession(claudeSessionID)
}

// handleCompactionRecovery handles automatic recovery from "Conversation too long" error
// It creates a new session with --resume and retries /compact
func (h *Handler) handleCompactionRecovery(client *Client, oldProcess *claude.Process, isFilesystemSession bool, retryCount int) {
	convID := client.ConversationID
	claudeSessionID := client.ClaudeSessionID
	projectPath := client.ProjectPath

	h.logger.Info("starting compaction recovery",
		zap.String("conversationID", convID.String()),
		zap.String("claudeSessionID", claudeSessionID.String()),
		zap.Int("retryCount", retryCount))

	// Notify client about recovery
	h.sendStatusToClient(client, "recovering")

	// Stop the old process
	if err := h.claudeMgr.StopProcess(claudeSessionID); err != nil {
		h.logger.Warn("failed to stop old process during recovery",
			zap.String("claudeSessionID", claudeSessionID.String()),
			zap.Error(err))
	}

	// Wait for old process to fully terminate using Done channel with timeout
	// This is more robust than a fixed sleep as it adapts to actual process termination time
	select {
	case <-oldProcess.Done:
		h.logger.Debug("old process terminated successfully during recovery",
			zap.String("claudeSessionID", claudeSessionID.String()))
	case <-time.After(2 * time.Second):
		h.logger.Warn("timeout waiting for old process to terminate during recovery",
			zap.String("claudeSessionID", claudeSessionID.String()))
	}

	// Create a new process - it will use --resume automatically since session file exists
	newProcess, err := h.claudeMgr.CreateProcess(claudeSessionID, projectPath, nil)
	if err != nil {
		h.logger.Error("failed to create new process for recovery",
			zap.String("claudeSessionID", claudeSessionID.String()),
			zap.Error(err))
		h.sendErrorToClient(client, "Recovery failed: could not create new session")
		h.sendCompleteToClient(client)
		return
	}

	// Start in interactive mode
	if err := newProcess.StartInteractive(); err != nil {
		h.logger.Error("failed to start new process for recovery",
			zap.String("claudeSessionID", claudeSessionID.String()),
			zap.Error(err))
		h.sendErrorToClient(client, "Recovery failed: could not start new session")
		h.sendCompleteToClient(client)
		return
	}

	// Send /compact command
	compactCommand := "/compact"
	if err := newProcess.SendMessage(compactCommand, nil); err != nil {
		h.logger.Error("failed to send /compact during recovery",
			zap.String("claudeSessionID", claudeSessionID.String()),
			zap.Error(err))
		h.sendErrorToClient(client, "Recovery failed: could not send /compact")
		// Stop the process we just started
		_ = h.claudeMgr.StopProcess(claudeSessionID)
		h.sendCompleteToClient(client)
		return
	}

	h.logger.Info("compaction recovery initiated successfully",
		zap.String("claudeSessionID", claudeSessionID.String()))

	// Continue streaming with the new process (increment retry count)
	h.streamProcessOutputWithRecovery(client, newProcess, isFilesystemSession, nil, retryCount+1)
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

func (h *Handler) sendStderrToClient(client *Client, content string) {
	msg := createOutgoingStderr(client.ConversationID, content)
	data, _ := json.Marshal(msg)
	select {
	case client.Send <- data:
	default:
	}
}

func (h *Handler) sendCompleteToClient(client *Client) {
	msg := createOutgoingComplete(client.ConversationID)
	data, _ := json.Marshal(msg)

	// Complete message is critical - retry with timeout if buffer is full
	select {
	case client.Send <- data:
		return
	default:
		// Buffer full, retry with timeout
	}

	// Retry with timeout to ensure complete message is delivered
	select {
	case client.Send <- data:
	case <-time.After(2 * time.Second):
		h.logger.Warn("failed to send complete message: buffer full after timeout",
			zap.String("clientID", client.ID.String()),
			zap.String("conversationID", client.ConversationID.String()))
	case <-client.Done:
	}
}

// broadcastStreamToConversation broadcasts stream content to all clients in the conversation
// This ensures reconnected clients receive stream output even if a different client started the process
func (h *Handler) broadcastStreamToConversation(convID uuid.UUID, content string) {
	msg := createOutgoingStream(convID, content)
	data, _ := json.Marshal(msg)
	h.hub.BroadcastToConversation(convID, data)
}

// broadcastStderrToConversation broadcasts stderr content to all clients in the conversation
func (h *Handler) broadcastStderrToConversation(convID uuid.UUID, content string) {
	msg := createOutgoingStderr(convID, content)
	data, _ := json.Marshal(msg)
	h.hub.BroadcastToConversation(convID, data)
}

// broadcastCompleteToConversation broadcasts complete message to all clients in the conversation
func (h *Handler) broadcastCompleteToConversation(convID uuid.UUID) {
	msg := createOutgoingComplete(convID)
	data, _ := json.Marshal(msg)
	h.hub.BroadcastToConversation(convID, data)
}

// broadcastErrorToConversation broadcasts error message to all clients in the conversation
func (h *Handler) broadcastErrorToConversation(convID uuid.UUID, errMsg string) {
	msg := createOutgoingError(errMsg)
	data, _ := json.Marshal(msg)
	h.hub.BroadcastToConversation(convID, data)
}

// broadcastStreamToSession broadcasts stream content to all clients watching a Claude session
// This is used for filesystem sessions where clients may have different conversationIDs
// but are all watching the same underlying Claude session
func (h *Handler) broadcastStreamToSession(sessionID uuid.UUID, content string) {
	msg := createOutgoingStream(sessionID, content)
	data, _ := json.Marshal(msg)
	h.hub.BroadcastToClaudeSession(sessionID, data)
}

// broadcastErrorToSession broadcasts error message to all clients watching a Claude session
func (h *Handler) broadcastErrorToSession(sessionID uuid.UUID, errMsg string) {
	msg := createOutgoingError(errMsg)
	data, _ := json.Marshal(msg)
	h.hub.BroadcastToClaudeSession(sessionID, data)
}

// broadcastCompleteToSession broadcasts complete message to all clients watching a Claude session
func (h *Handler) broadcastCompleteToSession(sessionID uuid.UUID) {
	msg := createOutgoingComplete(sessionID)
	data, _ := json.Marshal(msg)
	h.hub.BroadcastToClaudeSession(sessionID, data)
}

// decodeProjectPath converts an encoded path back to the original filesystem path.
// Claude CLI encodes paths by replacing / with -
// This function uses smart decoding that verifies paths exist on the filesystem.
func decodeProjectPath(encoded string) string {
	// Import claude.EncodeProjectPath logic here to avoid circular dependencies
	return claude.DecodeProjectPath(encoded)
}

// waitForAuthMessage waits for the first message to be an auth message with JWT token
// Returns the authenticated user ID or error
func (h *Handler) waitForAuthMessage(c *websocket.Conn) (uuid.UUID, error) {
	// Set a timeout for auth message
	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))

	_, data, err := c.ReadMessage()
	if err != nil {
		return uuid.Nil, fmt.Errorf("failed to read auth message: %w", err)
	}

	// Reset read deadline
	_ = c.SetReadDeadline(time.Time{})

	// Parse the message
	var msg IncomingMessage
	if err := json.Unmarshal(data, &msg); err != nil {
		return uuid.Nil, fmt.Errorf("invalid message format: %w", err)
	}

	if msg.Type != MessageTypeAuth {
		return uuid.Nil, fmt.Errorf("expected auth message, got: %s", msg.Type)
	}

	if msg.Content == "" {
		return uuid.Nil, fmt.Errorf("missing token in auth message")
	}

	// Validate JWT token
	token, err := jwt.Parse(msg.Content, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("invalid signing method")
		}
		return []byte(h.config.Auth.JWTSecret), nil
	})

	if err != nil || !token.Valid {
		return uuid.Nil, fmt.Errorf("invalid or expired token")
	}

	claims, ok := token.Claims.(jwt.MapClaims)
	if !ok {
		return uuid.Nil, fmt.Errorf("invalid token claims")
	}

	userIDStr, ok := claims["sub"].(string)
	if !ok {
		return uuid.Nil, fmt.Errorf("missing user ID in token")
	}

	userID, err := uuid.FromString(userIDStr)
	if err != nil {
		return uuid.Nil, fmt.Errorf("invalid user ID format: %w", err)
	}

	h.logger.Debug("user authenticated via auth message", zap.String("userID", userID.String()))
	return userID, nil
}

// saveImagesToTemp saves base64-encoded images to temporary files
// Returns the file paths and any error encountered
func (h *Handler) saveImagesToTemp(images []ImageContent) ([]string, error) {
	var paths []string

	for i, img := range images {
		if img.Type != "base64" {
			return nil, fmt.Errorf("unsupported image type: %s", img.Type)
		}

		// Decode base64 data
		data, err := base64.StdEncoding.DecodeString(img.Data)
		if err != nil {
			// Clean up any files we've already created
			for _, p := range paths {
				os.Remove(p)
			}
			return nil, fmt.Errorf("failed to decode image %d: %w", i, err)
		}

		// Determine file extension from media type
		ext := getExtensionFromMediaType(img.MediaType)

		// Create temp file
		tmpFile, err := os.CreateTemp("", fmt.Sprintf("claude-image-*%s", ext))
		if err != nil {
			for _, p := range paths {
				os.Remove(p)
			}
			return nil, fmt.Errorf("failed to create temp file: %w", err)
		}

		// Write image data
		if _, err := tmpFile.Write(data); err != nil {
			tmpFile.Close()
			os.Remove(tmpFile.Name())
			for _, p := range paths {
				os.Remove(p)
			}
			return nil, fmt.Errorf("failed to write image data: %w", err)
		}

		tmpFile.Close()
		paths = append(paths, tmpFile.Name())

		h.logger.Debug("saved image to temp file",
			zap.Int("index", i),
			zap.String("path", tmpFile.Name()),
			zap.String("mediaType", img.MediaType),
			zap.Int("size", len(data)))
	}

	return paths, nil
}

// getExtensionFromMediaType returns the file extension for a given media type
func getExtensionFromMediaType(mediaType string) string {
	// Handle common image types
	switch strings.ToLower(mediaType) {
	case "image/png":
		return ".png"
	case "image/jpeg", "image/jpg":
		return ".jpg"
	case "image/gif":
		return ".gif"
	case "image/webp":
		return ".webp"
	case "image/bmp":
		return ".bmp"
	case "image/svg+xml":
		return ".svg"
	default:
		// Try to extract from media type
		parts := strings.Split(mediaType, "/")
		if len(parts) == 2 {
			return "." + parts[1]
		}
		return ".img"
	}
}

// getMediaTypeFromExtension returns the media type for a given file extension
func getMediaTypeFromExtension(ext string) string {
	switch strings.ToLower(ext) {
	case ".png":
		return "image/png"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".bmp":
		return "image/bmp"
	case ".svg":
		return "image/svg+xml"
	default:
		return "application/octet-stream"
	}
}

// unused but kept for potential future use
var _ = filepath.Base

// Queue-related helper methods

// sendQueueSyncToClient sends the current queue state to a client
func (h *Handler) sendQueueSyncToClient(client *Client) {
	ctx := context.Background()
	messages, err := h.queueService.GetQueue(ctx, client.ConversationID)
	if err != nil {
		h.logger.Warn("failed to get queue for sync",
			zap.String("conversationID", client.ConversationID.String()),
			zap.Error(err))
		return
	}

	payload := queue.QueueSyncPayload{
		Messages: queue.ToResponseList(messages, h.queueService.GetImageURL),
	}

	msg := createQueueSyncMessage(payload)
	data, err := json.Marshal(msg)
	if err != nil {
		h.logger.Error("failed to marshal queue sync message", zap.Error(err))
		return
	}

	select {
	case client.Send <- data:
	default:
		// Buffer full, skip
	}
}

// BroadcastQueueAdd broadcasts a queue_add message to all clients in a conversation
func (h *Handler) BroadcastQueueAdd(convID uuid.UUID, msg *queue.QueuedMessage) {
	payload := queue.ToAddPayload(msg, h.queueService.GetImageURL)
	wsMsg := createQueueAddMessage(payload)
	data, err := json.Marshal(wsMsg)
	if err != nil {
		h.logger.Error("failed to marshal queue add message", zap.Error(err))
		return
	}
	h.hub.BroadcastToConversation(convID, data)
}

// BroadcastQueueRemove broadcasts a queue_remove message to all clients in a conversation
func (h *Handler) BroadcastQueueRemove(convID uuid.UUID, messageID uuid.UUID) {
	payload := queue.QueueRemovePayload{ID: messageID}
	wsMsg := createQueueRemoveMessage(payload)
	data, err := json.Marshal(wsMsg)
	if err != nil {
		h.logger.Error("failed to marshal queue remove message", zap.Error(err))
		return
	}
	h.hub.BroadcastToConversation(convID, data)
}

// BroadcastQueueSync broadcasts a queue_sync message to all clients in a conversation
func (h *Handler) BroadcastQueueSync(convID uuid.UUID, messages []queue.QueuedMessage) {
	payload := queue.QueueSyncPayload{
		Messages: queue.ToResponseList(messages, h.queueService.GetImageURL),
	}
	wsMsg := createQueueSyncMessage(payload)
	data, err := json.Marshal(wsMsg)
	if err != nil {
		h.logger.Error("failed to marshal queue sync message", zap.Error(err))
		return
	}
	h.hub.BroadcastToConversation(convID, data)
}

// HandleUserWebSocket handles the unified user WebSocket connection
// This is the new endpoint: /api/v1/ws/user
func (h *Handler) HandleUserWebSocket(c *websocket.Conn) {
	// Try to get userID from middleware (query param auth - backward compatibility)
	userIDStr := c.Locals("userID")

	// If no userID from middleware, wait for auth message (more secure approach)
	var userID uuid.UUID
	if userIDStr == nil {
		// Wait for first message which should be auth
		authUserID, err := h.waitForAuthMessage(c)
		if err != nil {
			h.logger.Error("auth failed for user WebSocket", zap.Error(err))
			h.sendError(c, "authentication failed: "+err.Error())
			return
		}
		userID = authUserID
	} else {
		userIDString, ok := userIDStr.(string)
		if !ok {
			h.logger.Error("invalid user ID type", zap.Any("userID", userIDStr))
			h.sendError(c, "invalid user ID")
			return
		}

		var err error
		userID, err = uuid.FromString(userIDString)
		if err != nil {
			h.logger.Error("invalid user ID format", zap.String("userID", userIDString), zap.Error(err))
			h.sendError(c, "invalid user ID format")
			return
		}
	}

	// Create client without conversation ID (will be set on subscribe)
	client := &Client{
		ID:     uuid.Must(uuid.NewV7()),
		UserID: userID,
		Conn:   c,
		Send:   make(chan []byte, 256),
		Done:   make(chan struct{}),
	}

	// Register client with hub
	h.hub.Register(client)

	// Send authenticated status to client
	h.sendStatusToClient(client, "authenticated")

	h.logger.Info("User WebSocket authenticated",
		zap.String("clientID", client.ID.String()),
		zap.String("userID", userID.String()))

	// Start read/write pumps
	go h.writePump(client)
	h.readUserPump(client)
}

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

// handleUserMessage routes messages for the unified user WebSocket
func (h *Handler) handleUserMessage(client *Client, msg *IncomingMessage) {
	h.logger.Info("handleUserMessage received",
		zap.String("clientID", client.ID.String()),
		zap.String("type", msg.Type),
		zap.String("content", msg.Content),
		zap.String("sessionID", msg.SessionID),
		zap.String("encodedPath", msg.EncodedPath),
		zap.String("conversationID", msg.ConversationID))

	switch msg.Type {
	case MessageTypeAuth:
		// Auth is handled by middleware, but we need to acknowledge the message
		// to avoid "Unknown message type" error
		h.sendStatusToClient(client, "authenticated")
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
	case MessageTypeHistorySubscribe:
		h.handleHistorySubscribeMessage(client, msg)
	case MessageTypeHistoryUnsubscribe:
		h.handleHistoryUnsubscribeMessage(client)
	default:
		h.sendErrorToClient(client, "Unknown message type: "+msg.Type)
	}
}

// handleSubscribeMessage handles subscribe requests
func (h *Handler) handleSubscribeMessage(client *Client, msg *IncomingMessage) {
	// Check if this is a filesystem-based session (sessionId + encodedPath provided)
	if msg.SessionID != "" && msg.EncodedPath != "" {
		h.handleFilesystemSubscribe(client, msg)
		return
	}

	// Read subscribe fields directly from msg (frontend sends fields at top level)
	convID, err := uuid.FromString(msg.ConversationID)
	if err != nil || msg.ConversationID == "" {
		h.sendErrorToClient(client, "Invalid or missing conversation ID")
		return
	}

	// Validate user has access to this conversation
	// Use timeout context to prevent indefinite DB waits
	ctx, cancel := context.WithTimeout(context.Background(), dbOperationTimeout)
	defer cancel()

	conv, err := h.convRepo.FindByID(ctx, convID)
	if err != nil {
		h.sendErrorToClient(client, "Conversation not found")
		return
	}

	// Get project to check user ownership
	proj, err := h.projRepo.FindByID(ctx, conv.ProjectID)
	if err != nil {
		h.sendErrorToClient(client, "Project not found")
		return
	}

	if proj.UserID != client.UserID {
		h.sendErrorToClient(client, "Access denied to conversation")
		return
	}

	// Cache project path and Claude session ID to avoid DB lookups on each message
	client.ProjectPath = proj.Path
	client.ClaudeSessionID = h.getClaudeSessionID(conv, convID)

	// Subscribe to conversation
	resp := make(chan error, 1)
	h.hub.Subscribe(&SubscribeRequest{
		Client:         client,
		ConversationID: convID,
		SessionID:      msg.SessionID,
		EncodedPath:    msg.EncodedPath,
		Response:       resp,
	})
	<-resp

	// Send subscribed confirmation
	h.sendSubscribedToClient(client, convID)

	// Send session state
	h.sendSessionStateToClient(client, convID, msg.SessionID, msg.EncodedPath)

	// Send queue sync
	h.sendQueueSyncToClient(client)

	h.logger.Info("Client subscribed to conversation",
		zap.String("clientID", client.ID.String()),
		zap.String("conversationID", convID.String()))
}

// handleFilesystemSubscribe handles subscription for filesystem-based Claude sessions
// These don't have a DB conversation - they use sessionId + encodedPath directly
func (h *Handler) handleFilesystemSubscribe(client *Client, msg *IncomingMessage) {
	// Decode project path using the same scheme as HandleConnection
	// This uses smart decoding that verifies paths exist on the filesystem
	projectPath := decodeProjectPath(msg.EncodedPath)

	// Validate the project path format
	cleanPath := filepath.Clean(projectPath)
	if !filepath.IsAbs(cleanPath) {
		h.logger.Warn("Project path is not absolute", zap.String("path", projectPath))
		h.sendErrorToClient(client, "Invalid project path: must be absolute")
		return
	}

	// Check if path exists - but don't fail if it doesn't (Docker environment may not have source mounted)
	info, err := os.Stat(cleanPath)
	if err != nil {
		h.logger.Warn("Project path does not exist on this host (may be running in Docker)",
			zap.String("path", cleanPath))
		// Continue anyway - the session files are what matter, not the source directory
	} else if !info.IsDir() {
		h.logger.Warn("Project path is not a directory", zap.String("path", cleanPath))
		h.sendErrorToClient(client, "Invalid project path: not a directory")
		return
	}
	projectPath = cleanPath

	// Parse sessionId as UUID
	sessionUUID, err := uuid.FromString(msg.SessionID)
	if err != nil {
		h.logger.Warn("Failed to parse session ID as UUID", zap.Error(err), zap.String("sessionID", msg.SessionID))
		h.sendErrorToClient(client, "Invalid session ID format")
		return
	}

	// Generate a deterministic UUID from sessionId + encodedPath for subscription tracking
	// This allows consistent subscription management without a DB conversation
	virtualConvID := uuid.NewV5(uuid.NamespaceOID, msg.SessionID+":"+msg.EncodedPath)

	// Cache project path and Claude session ID
	client.ProjectPath = projectPath
	client.ClaudeSessionID = sessionUUID
	client.IsFilesystemSession = true

	// Subscribe to the virtual conversation
	resp := make(chan error, 1)
	h.hub.Subscribe(&SubscribeRequest{
		Client:         client,
		ConversationID: virtualConvID,
		SessionID:      msg.SessionID,
		EncodedPath:    msg.EncodedPath,
		Response:       resp,
	})
	<-resp

	// Send subscribed confirmation with the virtual conversation ID
	h.sendSubscribedToClient(client, virtualConvID)

	// Send session state for filesystem session (no DB queue lookup)
	h.sendFilesystemSessionStateToClient(client, virtualConvID, msg.SessionID, msg.EncodedPath)

	// Send empty queue sync for filesystem sessions (they don't use DB queues)
	h.sendEmptyQueueSyncToClient(client)

	h.logger.Info("Client subscribed to filesystem session",
		zap.String("clientID", client.ID.String()),
		zap.String("sessionID", msg.SessionID),
		zap.String("projectPath", projectPath),
		zap.String("virtualConvID", virtualConvID.String()))
}

// sendSubscribedToClient sends subscription confirmation
func (h *Handler) sendSubscribedToClient(client *Client, convID uuid.UUID) {
	payload := SubscribedPayload{
		ConversationID: convID.String(),
	}

	msg := OutgoingMessage{
		Type:           MessageTypeSubscribed,
		ConversationID: convID.String(), // Also set at top level for frontend compatibility
		Payload:        payload,
	}
	data, err := json.Marshal(msg)
	if err != nil {
		h.logger.Error("Failed to marshal subscribed message", zap.Error(err))
		return
	}

	select {
	case client.Send <- data:
	default:
		h.logger.Warn("Failed to send subscribed message - buffer full")
	}
}

// sendSessionStateToClient sends full session state
func (h *Handler) sendSessionStateToClient(client *Client, convID uuid.UUID, sessionID, encodedPath string) {
	// Get queue from service
	queueMsgs, err := h.queueService.GetQueue(context.Background(), convID)
	if err != nil {
		h.logger.Error("Failed to get queue for session state",
			zap.String("conversationID", convID.String()),
			zap.Error(err))
		// Continue with empty queue rather than failing
	}

	// Determine session state and todos
	var sessionState SessionStateType = SessionStateIdle
	var isStreaming bool = false
	// TODO: Fetch todos from Claude session state when available.
	// Currently empty as Claude CLI doesn't expose todos via API.
	var todos []TodoItem

	// Check if there's an active Claude process
	if h.claudeMgr != nil {
		process := h.claudeMgr.GetProcess(convID)
		if process != nil && process.GetStatus() == claude.ProcessStatusRunning {
			sessionState = SessionStateStreaming
			isStreaming = true
		}
	}

	// Convert queue to interface slice (pre-allocate to avoid repeated reallocation)
	queueInterface := make([]interface{}, len(queueMsgs))
	for i, q := range queueMsgs {
		queueInterface[i] = q
	}

	payload := SessionStatePayload{
		ConversationID: convID.String(),
		SessionState:   sessionState,
		IsStreaming:    isStreaming,
		Todos:          todos,
		Queue:          queueInterface,
	}

	msg := OutgoingMessage{
		Type:    MessageTypeSessionState,
		Payload: payload,
	}
	data, err := json.Marshal(msg)
	if err != nil {
		h.logger.Error("Failed to marshal session state message", zap.Error(err))
		return
	}

	select {
	case client.Send <- data:
	default:
		h.logger.Warn("Failed to send session state - buffer full")
	}
}

// sendFilesystemSessionStateToClient sends session state for filesystem-based sessions
// Unlike sendSessionStateToClient, this doesn't query the database for queue messages
func (h *Handler) sendFilesystemSessionStateToClient(client *Client, convID uuid.UUID, sessionID, encodedPath string) {
	// Determine session state and todos
	var sessionState SessionStateType = SessionStateIdle
	var isStreaming bool = false
	var todos []TodoItem

	// Check if there's an active Claude process
	if h.claudeMgr != nil {
		process := h.claudeMgr.GetProcess(convID)
		if process != nil && process.GetStatus() == claude.ProcessStatusRunning {
			sessionState = SessionStateStreaming
			isStreaming = true
		}
	}

	// Build payload with empty queue (filesystem sessions don't use DB queues)
	payload := SessionStatePayload{
		ConversationID: convID.String(),
		SessionState:   sessionState,
		IsStreaming:    isStreaming,
		Todos:          todos,
		Queue:          []interface{}{}, // Empty queue for filesystem sessions
	}

	msg := OutgoingMessage{
		Type:    MessageTypeSessionState,
		Payload: payload,
	}
	data, err := json.Marshal(msg)
	if err != nil {
		h.logger.Error("Failed to marshal filesystem session state message", zap.Error(err))
		return
	}

	select {
	case client.Send <- data:
	default:
		h.logger.Warn("Failed to send filesystem session state - buffer full")
	}
}

// sendEmptyQueueSyncToClient sends an empty queue sync to a client
// Used for filesystem sessions that don't have DB-backed queues
func (h *Handler) sendEmptyQueueSyncToClient(client *Client) {
	payload := queue.QueueSyncPayload{
		Messages: []queue.QueuedMessageResponse{},
	}

	msg := createQueueSyncMessage(payload)
	data, err := json.Marshal(msg)
	if err != nil {
		h.logger.Error("failed to marshal empty queue sync message", zap.Error(err))
		return
	}

	select {
	case client.Send <- data:
	default:
		// Buffer full, skip
	}
}

// handleUnsubscribeMessage handles unsubscribe requests
func (h *Handler) handleUnsubscribeMessage(client *Client) {
	h.hub.UnsubscribeClient(client)
	h.logger.Info("Client unsubscribed", zap.String("clientID", client.ID.String()))
}

// getClaudeSessionID determines the Claude session ID to use for a conversation
// If the conversation has a synced ClaudeSession, it uses that; otherwise, falls back to defaultID
func (h *Handler) getClaudeSessionID(conv *conversation.Conversation, defaultID uuid.UUID) uuid.UUID {
	if conv.ClaudeSession != nil && *conv.ClaudeSession != "" {
		if parsedID, err := uuid.FromString(*conv.ClaudeSession); err == nil {
			return parsedID
		}
	}
	return defaultID
}

// handleUserChatMessage handles chat messages in user WebSocket
func (h *Handler) handleUserChatMessage(client *Client, msg *IncomingMessage) {
	if client.ConversationID == uuid.Nil {
		h.sendErrorToClient(client, "Not subscribed to any conversation")
		return
	}

	// Use cached values from subscription (no DB lookup needed)
	if client.ProjectPath == "" {
		h.sendErrorToClient(client, "Subscription state invalid - please resubscribe")
		return
	}

	// Delegate to existing chat handler logic, using cached IsFilesystemSession flag
	h.handleChatMessage(client, msg.Content, msg.Images, client.ProjectPath, client.ClaudeSessionID, client.IsFilesystemSession)
}

// handleUserStopMessage handles stop messages in user WebSocket
func (h *Handler) handleUserStopMessage(client *Client) {
	if client.ConversationID == uuid.Nil {
		h.sendErrorToClient(client, "Not subscribed to any conversation")
		return
	}

	// Use cached ClaudeSessionID from subscription (no DB lookup needed)
	h.handleStopMessage(client, client.ClaudeSessionID)
}

// handleHistorySubscribeMessage handles history watch subscription via unified WebSocket
func (h *Handler) handleHistorySubscribeMessage(client *Client, msg *IncomingMessage) {
	if h.historyCache == nil {
		h.sendErrorToClient(client, "History watch not available")
		return
	}

	encodedPath := msg.EncodedPath
	sessionID := msg.SessionID

	if encodedPath == "" || sessionID == "" {
		h.sendErrorToClient(client, "encoded_path and session_id are required for history_subscribe")
		return
	}

	// Unsubscribe from previous history watch if any
	if client.HistoryEncodedPath != "" && client.HistorySessionID != "" {
		h.historyCache.Unsubscribe(client.HistoryEncodedPath, client.HistorySessionID)
	}

	// Update client's history subscription state
	client.HistoryEncodedPath = encodedPath
	client.HistorySessionID = sessionID

	// Subscribe to history cache with a callback that sends to this client
	h.historyCache.Subscribe(encodedPath, sessionID, func(ep, sid string, newMessages []claude.ClaudeMessage) {
		h.sendHistoryNewMessages(client, ep, sid, newMessages)
	})

	h.logger.Info("Client subscribed to history watch via unified WebSocket",
		zap.String("clientID", client.ID.String()),
		zap.String("encodedPath", encodedPath),
		zap.String("sessionID", sessionID))

	// Send confirmation
	h.sendHistorySubscribedToClient(client, encodedPath, sessionID)
}

// handleHistoryUnsubscribeMessage handles history watch unsubscription via unified WebSocket
func (h *Handler) handleHistoryUnsubscribeMessage(client *Client) {
	if client.HistoryEncodedPath == "" || client.HistorySessionID == "" {
		// Not subscribed to history watch, send confirmation anyway
		h.sendHistoryUnsubscribedToClient(client)
		return
	}

	// Unsubscribe from history cache
	if h.historyCache != nil {
		h.historyCache.Unsubscribe(client.HistoryEncodedPath, client.HistorySessionID)
	}

	h.logger.Info("Client unsubscribed from history watch via unified WebSocket",
		zap.String("clientID", client.ID.String()),
		zap.String("encodedPath", client.HistoryEncodedPath),
		zap.String("sessionID", client.HistorySessionID))

	// Clear client's history subscription state
	client.HistoryEncodedPath = ""
	client.HistorySessionID = ""

	// Send confirmation
	h.sendHistoryUnsubscribedToClient(client)
}

// NewMessagesPayload is the payload for new_messages WebSocket message
type NewMessagesPayload struct {
	SessionID    string                 `json:"session_id"`
	EncodedPath  string                 `json:"encoded_path"`
	Messages     []claude.ClaudeMessage `json:"messages"`
	SessionState claude.SessionState    `json:"session_state"`
	Todos        []claude.TodoItem      `json:"todos"`
}

// sendHistoryNewMessages sends new messages from history watch to the client
func (h *Handler) sendHistoryNewMessages(client *Client, encodedPath, sessionID string, newMessages []claude.ClaudeMessage) {
	// Get all messages to determine session state
	allMessages, err := h.historyCache.GetSessionMessages(encodedPath, sessionID)
	sessionState := claude.SessionStateIdle
	if err == nil {
		sessionState = claude.GetSessionState(allMessages)
	}

	// Get todos for this session
	todos, _ := claude.GetSessionTodos(sessionID)

	// Create payload
	payload := NewMessagesPayload{
		SessionID:    sessionID,
		EncodedPath:  encodedPath,
		Messages:     newMessages,
		SessionState: sessionState,
		Todos:        todos,
	}

	// Wrap in OutgoingMessage with payload field
	msg := OutgoingMessage{
		Type:    MessageTypeNewMessages,
		Payload: payload,
	}

	data, err := json.Marshal(msg)
	if err != nil {
		h.logger.Error("failed to marshal history new_messages", zap.Error(err))
		return
	}

	select {
	case client.Send <- data:
	case <-client.Done:
		// Client disconnected
	default:
		h.logger.Warn("failed to send history new_messages - buffer full",
			zap.String("clientID", client.ID.String()))
	}
}

// HistorySubscribedPayload is the payload for history_subscribed WebSocket message
type HistorySubscribedPayload struct {
	SessionID   string `json:"session_id"`
	EncodedPath string `json:"encoded_path"`
}

// sendHistorySubscribedToClient sends history subscription confirmation
func (h *Handler) sendHistorySubscribedToClient(client *Client, encodedPath, sessionID string) {
	payload := HistorySubscribedPayload{
		SessionID:   sessionID,
		EncodedPath: encodedPath,
	}

	msg := OutgoingMessage{
		Type:    MessageTypeHistorySubscribed,
		Payload: payload,
	}

	data, err := json.Marshal(msg)
	if err != nil {
		h.logger.Error("failed to marshal history_subscribed", zap.Error(err))
		return
	}

	select {
	case client.Send <- data:
	default:
		h.logger.Warn("failed to send history_subscribed - buffer full")
	}
}

// sendHistoryUnsubscribedToClient sends history unsubscription confirmation
func (h *Handler) sendHistoryUnsubscribedToClient(client *Client) {
	msg := OutgoingMessage{
		Type: MessageTypeHistoryUnsubscribed,
	}

	data, err := json.Marshal(msg)
	if err != nil {
		h.logger.Error("failed to marshal history_unsubscribed", zap.Error(err))
		return
	}

	select {
	case client.Send <- data:
	default:
		h.logger.Warn("failed to send history_unsubscribed - buffer full")
	}
}
