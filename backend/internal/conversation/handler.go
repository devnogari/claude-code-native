package conversation

import (
	"context"
	"strings"

	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
)

// ConversationRepository defines the interface for conversation data operations
type ConversationRepository interface {
	Create(ctx context.Context, c *Conversation) error
	FindByID(ctx context.Context, id uuid.UUID) (*Conversation, error)
	FindByProjectID(ctx context.Context, projectID uuid.UUID) ([]*Conversation, error)
	Update(ctx context.Context, c *Conversation) error
	Delete(ctx context.Context, id uuid.UUID) error
}

// ProjectRepository defines the interface for project lookup (used for authorization)
type ProjectRepository interface {
	FindByID(ctx context.Context, id uuid.UUID) (*project.Project, error)
}

// Handler handles conversation HTTP requests
type Handler struct {
	convRepo    ConversationRepository
	projectRepo ProjectRepository
}

// NewHandler creates a new conversation handler
func NewHandler(convRepo ConversationRepository, projectRepo ProjectRepository) *Handler {
	return &Handler{
		convRepo:    convRepo,
		projectRepo: projectRepo,
	}
}

// getUserID extracts the user ID from fiber context locals
func getUserID(c *fiber.Ctx) (uuid.UUID, error) {
	userIDStr, ok := c.Locals("userID").(string)
	if !ok || userIDStr == "" {
		return uuid.Nil, fiber.NewError(fiber.StatusUnauthorized, "unauthorized")
	}

	userID, err := uuid.FromString(userIDStr)
	if err != nil {
		return uuid.Nil, fiber.NewError(fiber.StatusUnauthorized, "invalid user ID")
	}

	return userID, nil
}

// verifyProjectOwnership checks if the user owns the project
func (h *Handler) verifyProjectOwnership(ctx context.Context, projectID, userID uuid.UUID) (*project.Project, error) {
	proj, err := h.projectRepo.FindByID(ctx, projectID)
	if err != nil {
		if strings.Contains(err.Error(), "no rows") {
			return nil, fiber.NewError(fiber.StatusNotFound, "project not found")
		}
		return nil, fiber.NewError(fiber.StatusInternalServerError, "failed to get project")
	}

	if proj.UserID != userID {
		return nil, fiber.NewError(fiber.StatusForbidden, "forbidden: you don't have access to this project")
	}

	return proj, nil
}

// Create handles POST /api/v1/projects/:projectId/conversations
func (h *Handler) Create(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "unauthorized",
		})
	}

	// Get projectId from URL params
	projectIDStr := c.Params("projectId")
	projectID, err := uuid.FromString(projectIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid project ID",
		})
	}

	ctx := c.Context()

	// Verify project ownership
	_, err = h.verifyProjectOwnership(ctx, projectID, userID)
	if err != nil {
		if fiberErr, ok := err.(*fiber.Error); ok {
			return c.Status(fiberErr.Code).JSON(ErrorResponse{
				Error: fiberErr.Message,
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to verify project ownership",
		})
	}

	var req CreateConversationRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid request body",
		})
	}

	conv := &Conversation{
		ProjectID: projectID,
		Title:     req.Title,
	}

	if err := h.convRepo.Create(ctx, conv); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to create conversation",
		})
	}

	return c.Status(fiber.StatusCreated).JSON(ToResponse(conv))
}

// List handles GET /api/v1/projects/:projectId/conversations
func (h *Handler) List(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "unauthorized",
		})
	}

	// Get projectId from URL params
	projectIDStr := c.Params("projectId")
	projectID, err := uuid.FromString(projectIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid project ID",
		})
	}

	ctx := c.Context()

	// Verify project ownership
	_, err = h.verifyProjectOwnership(ctx, projectID, userID)
	if err != nil {
		if fiberErr, ok := err.(*fiber.Error); ok {
			return c.Status(fiberErr.Code).JSON(ErrorResponse{
				Error: fiberErr.Message,
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to verify project ownership",
		})
	}

	conversations, err := h.convRepo.FindByProjectID(ctx, projectID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to list conversations",
		})
	}

	// Return empty array if no conversations found
	if conversations == nil {
		conversations = []*Conversation{}
	}

	return c.Status(fiber.StatusOK).JSON(ToResponseList(conversations))
}

// Get handles GET /api/v1/conversations/:id
func (h *Handler) Get(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "unauthorized",
		})
	}

	convIDStr := c.Params("id")
	convID, err := uuid.FromString(convIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid conversation ID",
		})
	}

	ctx := c.Context()

	// Find the conversation first
	conv, err := h.convRepo.FindByID(ctx, convID)
	if err != nil {
		if strings.Contains(err.Error(), "no rows") {
			return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
				Error: "conversation not found",
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to get conversation",
		})
	}

	// Verify project ownership (via conversation.ProjectID)
	_, err = h.verifyProjectOwnership(ctx, conv.ProjectID, userID)
	if err != nil {
		if fiberErr, ok := err.(*fiber.Error); ok {
			return c.Status(fiberErr.Code).JSON(ErrorResponse{
				Error: fiberErr.Message,
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to verify project ownership",
		})
	}

	return c.Status(fiber.StatusOK).JSON(ToResponse(conv))
}

// Update handles PUT /api/v1/conversations/:id
func (h *Handler) Update(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "unauthorized",
		})
	}

	convIDStr := c.Params("id")
	convID, err := uuid.FromString(convIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid conversation ID",
		})
	}

	var req UpdateConversationRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid request body",
		})
	}

	ctx := c.Context()

	// Find the conversation first
	conv, err := h.convRepo.FindByID(ctx, convID)
	if err != nil {
		if strings.Contains(err.Error(), "no rows") {
			return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
				Error: "conversation not found",
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to get conversation",
		})
	}

	// Verify project ownership
	_, err = h.verifyProjectOwnership(ctx, conv.ProjectID, userID)
	if err != nil {
		if fiberErr, ok := err.(*fiber.Error); ok {
			return c.Status(fiberErr.Code).JSON(ErrorResponse{
				Error: fiberErr.Message,
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to verify project ownership",
		})
	}

	// Update only the title
	conv.Title = req.Title

	if err := h.convRepo.Update(ctx, conv); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to update conversation",
		})
	}

	return c.Status(fiber.StatusOK).JSON(ToResponse(conv))
}

// Delete handles DELETE /api/v1/conversations/:id
func (h *Handler) Delete(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "unauthorized",
		})
	}

	convIDStr := c.Params("id")
	convID, err := uuid.FromString(convIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid conversation ID",
		})
	}

	ctx := c.Context()

	// Find the conversation first to check ownership
	conv, err := h.convRepo.FindByID(ctx, convID)
	if err != nil {
		if strings.Contains(err.Error(), "no rows") {
			return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
				Error: "conversation not found",
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to get conversation",
		})
	}

	// Verify project ownership
	_, err = h.verifyProjectOwnership(ctx, conv.ProjectID, userID)
	if err != nil {
		if fiberErr, ok := err.(*fiber.Error); ok {
			return c.Status(fiberErr.Code).JSON(ErrorResponse{
				Error: fiberErr.Message,
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to verify project ownership",
		})
	}

	if err := h.convRepo.Delete(ctx, convID); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to delete conversation",
		})
	}

	return c.SendStatus(fiber.StatusNoContent)
}
