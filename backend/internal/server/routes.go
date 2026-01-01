package server

import (
	"github.com/gofiber/contrib/websocket"
	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
	"go.uber.org/zap"
)

func (s *Server) setupRoutes() {
	// Health check
	s.app.Get("/health", s.healthCheck)

	// API v1
	api := s.app.Group("/api/v1")

	// Health check (public)
	api.Get("/health", s.healthCheck)

	// Auth routes (public)
	authGroup := api.Group("/auth")
	authGroup.Post("/login", s.authHandler.Login)
	authGroup.Post("/register", s.authHandler.Register)

	// Protected routes
	protected := api.Group("")
	protected.Use(s.authMiddleware.Authenticate)

	// Project routes
	protected.Post("/projects", s.projectHandler.Create)
	protected.Get("/projects", s.projectHandler.List)
	protected.Get("/projects/:id", s.projectHandler.Get)
	protected.Put("/projects/:id", s.projectHandler.Update)
	protected.Delete("/projects/:id", s.projectHandler.Delete)

	// Conversation routes
	protected.Post("/projects/:projectId/conversations", s.convHandler.Create)
	protected.Get("/projects/:projectId/conversations", s.convHandler.List)
	protected.Get("/conversations/:id", s.convHandler.Get)
	protected.Put("/conversations/:id", s.convHandler.Update)
	protected.Delete("/conversations/:id", s.convHandler.Delete)
	protected.Post("/conversations/:id/favorite", s.convHandler.ToggleFavorite)

	// Message routes
	protected.Get("/conversations/:conversationId/messages", s.msgHandler.ListByConversation)

	// WebSocket route for real-time conversation streaming
	// Auth is handled via query params within the handler for WebSocket connections
	protected.Get("/ws/:conversationID", s.wsHandler.Upgrade, websocket.New(s.wsHandler.HandleConnection))

	// Claude Code History routes (local ~/.claude/projects/)
	historyGroup := protected.Group("/claude-history")
	historyGroup.Get("/projects", s.historyHandler.ListProjects)
	historyGroup.Get("/projects/:encodedPath", s.historyHandler.GetProject)
	historyGroup.Get("/projects/:encodedPath/sessions/:sessionId", s.historyHandler.GetSessionMessages)
	historyGroup.Post("/sessions/:sessionId/favorite", s.historyHandler.ToggleSessionFavorite)
	historyGroup.Post("/refresh", s.historyHandler.Refresh)

	// WebSocket route for watching session file changes in real-time
	historyGroup.Get("/ws/:encodedPath/:sessionId", s.historyWatchHandler.Upgrade, websocket.New(s.historyWatchHandler.HandleConnection))

	// Sync route - imports Claude CLI history to database
	protected.Post("/sync", s.syncHandler.Sync)

	// Session management - delete Claude CLI session for a conversation
	protected.Delete("/conversations/:id/session", s.deleteSession)

	// Direct session deletion by session ID and path (for Claude History sessions not in DB)
	protected.Delete("/sessions/:sessionId", s.deleteSessionDirect)
}

func (s *Server) healthCheck(c *fiber.Ctx) error {
	return c.JSON(fiber.Map{
		"status":  "ok",
		"service": "claude-code-native",
	})
}

// deleteSession deletes the Claude CLI session files for a conversation
// This allows starting a fresh session without previous conversation context
func (s *Server) deleteSession(c *fiber.Ctx) error {
	// Get conversation ID from URL
	convIDStr := c.Params("id")
	convID, err := uuid.FromString(convIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "invalid conversation ID",
		})
	}

	// Get user ID from context (set by auth middleware)
	userIDStr, ok := c.Locals("userID").(string)
	if !ok {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "user not authenticated",
		})
	}
	userID, err := uuid.FromString(userIDStr)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "invalid user ID",
		})
	}

	// Get conversation to find project
	conv, err := s.convRepo.FindByID(c.Context(), convID)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{
			"error": "conversation not found",
		})
	}

	// Get project to verify ownership and get path
	proj, err := s.projectRepo.FindByID(c.Context(), conv.ProjectID)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{
			"error": "project not found",
		})
	}

	// Verify user owns this project
	if proj.UserID != userID {
		return c.Status(fiber.StatusForbidden).JSON(fiber.Map{
			"error": "access denied",
		})
	}

	// Delete the Claude CLI session
	// Use ClaudeSession if available, otherwise use database conversation ID
	sessionIDToDelete := convID
	if conv.ClaudeSession != nil && *conv.ClaudeSession != "" {
		if parsedSessionID, err := uuid.FromString(*conv.ClaudeSession); err == nil {
			sessionIDToDelete = parsedSessionID
		}
	}

	if err := s.claudeMgr.DeleteSession(sessionIDToDelete, proj.Path); err != nil {
		s.logger.Warn("failed to delete claude session",
			zap.String("sessionID", sessionIDToDelete.String()),
			zap.String("conversationID", convID.String()),
			zap.Error(err))
		// Don't return error - session might already be deleted or not exist
	}

	s.logger.Info("claude session deleted",
		zap.String("sessionID", sessionIDToDelete.String()),
		zap.String("conversationID", convID.String()),
		zap.String("projectPath", proj.Path))

	return c.JSON(fiber.Map{
		"message": "session deleted successfully",
	})
}

// deleteSessionDirect deletes Claude CLI session files directly by session ID and project path
// This is for Claude History sessions that may not be in the database
func (s *Server) deleteSessionDirect(c *fiber.Ctx) error {
	sessionIDStr := c.Params("sessionId")
	sessionID, err := uuid.FromString(sessionIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "invalid session ID",
		})
	}

	// Get project path from query parameter
	projectPath := c.Query("path")
	if projectPath == "" {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "project path is required",
		})
	}

	// Get user ID from context (set by auth middleware)
	userIDStr, ok := c.Locals("userID").(string)
	if !ok {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "user not authenticated",
		})
	}
	userID, err := uuid.FromString(userIDStr)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "invalid user ID",
		})
	}

	// Delete the Claude CLI session files
	if err := s.claudeMgr.DeleteSession(sessionID, projectPath); err != nil {
		s.logger.Warn("failed to delete claude session files",
			zap.String("sessionID", sessionID.String()),
			zap.Error(err))
	}

	// Also delete the conversation from database if it exists
	// First find the project by path
	proj, err := s.projectRepo.FindByPath(c.Context(), userID, projectPath)
	if err == nil && proj != nil {
		// Find conversation by claude_session
		conv, err := s.convRepo.FindByClaudeSession(c.Context(), proj.ID, sessionIDStr)
		if err == nil && conv != nil {
			// Delete the conversation from database
			if err := s.convRepo.Delete(c.Context(), conv.ID); err != nil {
				s.logger.Warn("failed to delete conversation from database",
					zap.String("conversationID", conv.ID.String()),
					zap.Error(err))
			} else {
				s.logger.Info("conversation deleted from database",
					zap.String("conversationID", conv.ID.String()))
			}
		}
	}

	s.logger.Info("claude session deleted directly",
		zap.String("sessionID", sessionID.String()),
		zap.String("projectPath", projectPath))

	return c.JSON(fiber.Map{
		"message": "session deleted successfully",
	})
}
