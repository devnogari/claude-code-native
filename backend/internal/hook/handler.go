package hook

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"errors"

	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
	"go.uber.org/zap"
)

// Project is a minimal struct for hook handler results
type Project struct {
	ID          uuid.UUID
	UserID      uuid.UUID
	Name        string
	Path        string
	IsCompleted bool
}

// ProjectRepository defines the interface for project data operations needed by hook handler
type ProjectRepository interface {
	FindByPathAnyUser(ctx context.Context, path string) (*Project, error)
	MarkCompleted(ctx context.Context, id uuid.UUID, completed bool) error
}

// Handler handles hook HTTP requests
type Handler struct {
	repo   ProjectRepository
	apiKey string
	logger *zap.Logger
}

// NewHandler creates a new hook handler
func NewHandler(repo ProjectRepository, apiKey string, logger *zap.Logger) *Handler {
	return &Handler{
		repo:   repo,
		apiKey: apiKey,
		logger: logger,
	}
}

// validateAPIKey checks if the request has a valid API key using constant-time comparison
func (h *Handler) validateAPIKey(c *fiber.Ctx) bool {
	// Get API key from header
	apiKey := c.Get("X-API-Key")
	if apiKey == "" {
		// Also check Authorization header with Bearer prefix
		auth := c.Get("Authorization")
		if len(auth) > 7 && auth[:7] == "Bearer " {
			apiKey = auth[7:]
		}
	}

	// If no API key is configured, allow all requests (development mode)
	if h.apiKey == "" {
		return true
	}

	// Use constant-time comparison to prevent timing attacks
	return subtle.ConstantTimeCompare([]byte(apiKey), []byte(h.apiKey)) == 1
}

// handleSessionStatus handles the common logic for session completion/reopening
func (h *Handler) handleSessionStatus(c *fiber.Ctx, completed bool) error {
	// Validate API key
	if !h.validateAPIKey(c) {
		h.logger.Warn("hook API key validation failed",
			zap.String("remoteIP", c.IP()))
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "invalid or missing API key",
		})
	}

	var req SessionCompleteRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid request body",
		})
	}

	if err := req.Validate(); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: err.Error(),
		})
	}

	ctx := c.Context()

	// Find project by path (cwd from hook)
	project, err := h.repo.FindByPathAnyUser(ctx, req.Cwd)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			h.logger.Debug("project not found for hook",
				zap.String("cwd", req.Cwd),
				zap.String("sessionId", req.SessionID))
			return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
				Error:   "project not found",
				Details: "no project matches the working directory: " + req.Cwd,
			})
		}
		h.logger.Error("failed to find project for hook",
			zap.String("cwd", req.Cwd),
			zap.Error(err))
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to find project",
		})
	}

	// Mark project as completed/reopened
	if err := h.repo.MarkCompleted(ctx, project.ID, completed); err != nil {
		action := "mark as completed"
		if !completed {
			action = "reopen"
		}
		h.logger.Error("failed to "+action+" project",
			zap.String("projectID", project.ID.String()),
			zap.Error(err))
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to " + action + " project",
		})
	}

	// Log successful operation
	action := "completed"
	message := "project marked as completed"
	if !completed {
		action = "reopened"
		message = "project reopened"
	}

	h.logger.Info("session hook processed successfully",
		zap.String("action", action),
		zap.String("projectID", project.ID.String()),
		zap.String("projectName", project.Name),
		zap.String("cwd", req.Cwd),
		zap.String("sessionId", req.SessionID))

	return c.Status(fiber.StatusOK).JSON(SessionCompleteResponse{
		Success:     true,
		ProjectID:   project.ID.String(),
		ProjectName: project.Name,
		Message:     message,
	})
}

// SessionComplete handles POST /api/v1/hooks/session-complete
// This endpoint is called by Claude CLI's Stop hook when a session completes
func (h *Handler) SessionComplete(c *fiber.Ctx) error {
	return h.handleSessionStatus(c, true)
}

// SessionReopen handles POST /api/v1/hooks/session-reopen
// This endpoint can be used to mark a project as not completed (reopen)
func (h *Handler) SessionReopen(c *fiber.Ctx) error {
	return h.handleSessionStatus(c, false)
}
