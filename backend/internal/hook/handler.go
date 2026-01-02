package hook

import (
	"context"
	"strings"

	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
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
}

// NewHandler creates a new hook handler
func NewHandler(repo ProjectRepository, apiKey string) *Handler {
	return &Handler{
		repo:   repo,
		apiKey: apiKey,
	}
}

// validateAPIKey checks if the request has a valid API key
func (h *Handler) validateAPIKey(c *fiber.Ctx) bool {
	// Get API key from header
	apiKey := c.Get("X-API-Key")
	if apiKey == "" {
		// Also check Authorization header with Bearer prefix
		auth := c.Get("Authorization")
		if strings.HasPrefix(auth, "Bearer ") {
			apiKey = strings.TrimPrefix(auth, "Bearer ")
		}
	}

	// If no API key is configured, allow all requests (development mode)
	if h.apiKey == "" {
		return true
	}

	return apiKey == h.apiKey
}

// SessionComplete handles POST /api/v1/hooks/session-complete
// This endpoint is called by Claude CLI's Stop hook when a session completes
func (h *Handler) SessionComplete(c *fiber.Ctx) error {
	// Validate API key
	if !h.validateAPIKey(c) {
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
		if strings.Contains(err.Error(), "no rows") {
			return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
				Error:   "project not found",
				Details: "no project matches the working directory: " + req.Cwd,
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to find project",
		})
	}

	// Mark project as completed
	if err := h.repo.MarkCompleted(ctx, project.ID, true); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to mark project as completed",
		})
	}

	return c.Status(fiber.StatusOK).JSON(SessionCompleteResponse{
		Success:     true,
		ProjectID:   project.ID.String(),
		ProjectName: project.Name,
		Message:     "project marked as completed",
	})
}

// SessionReopen handles POST /api/v1/hooks/session-reopen
// This endpoint can be used to mark a project as not completed (reopen)
func (h *Handler) SessionReopen(c *fiber.Ctx) error {
	// Validate API key
	if !h.validateAPIKey(c) {
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
		if strings.Contains(err.Error(), "no rows") {
			return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
				Error:   "project not found",
				Details: "no project matches the working directory: " + req.Cwd,
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to find project",
		})
	}

	// Mark project as not completed (reopen)
	if err := h.repo.MarkCompleted(ctx, project.ID, false); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to reopen project",
		})
	}

	return c.Status(fiber.StatusOK).JSON(SessionCompleteResponse{
		Success:     true,
		ProjectID:   project.ID.String(),
		ProjectName: project.Name,
		Message:     "project reopened",
	})
}
