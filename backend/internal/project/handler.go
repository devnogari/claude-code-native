package project

import (
	"context"
	"strings"

	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
	"go.uber.org/zap"
)

// ProjectRepository defines the interface for project data operations
type ProjectRepository interface {
	Create(ctx context.Context, p *Project) error
	FindByID(ctx context.Context, id uuid.UUID) (*Project, error)
	FindByUserID(ctx context.Context, userID uuid.UUID) ([]*Project, error)
	Update(ctx context.Context, p *Project) error
	Delete(ctx context.Context, id uuid.UUID) error
	UpdateLastAccessed(ctx context.Context, id uuid.UUID) error
}

// Handler handles project HTTP requests
type Handler struct {
	repo   ProjectRepository
	logger *zap.Logger
}

// NewHandler creates a new project handler
func NewHandler(repo ProjectRepository, logger *zap.Logger) *Handler {
	return &Handler{
		repo:   repo,
		logger: logger.Named("project"),
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

// Create handles POST /api/v1/projects
func (h *Handler) Create(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "unauthorized",
		})
	}

	var req CreateProjectRequest
	if err := c.BodyParser(&req); err != nil {
		h.logger.Warn("invalid request body", zap.Error(err))
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid request body",
		})
	}

	if err := req.Validate(); err != nil {
		h.logger.Warn("validation failed", zap.Error(err))
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: err.Error(),
		})
	}

	project := &Project{
		UserID: userID,
		Name:   req.Name,
		Path:   req.Path,
	}

	ctx := c.Context()
	if err := h.repo.Create(ctx, project); err != nil {
		h.logger.Error("failed to create project", zap.Error(err))
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to create project",
		})
	}

	h.logger.Info("project created", zap.String("name", project.Name), zap.String("id", project.ID.String()))
	return c.Status(fiber.StatusCreated).JSON(ToResponse(project))
}

// List handles GET /api/v1/projects
func (h *Handler) List(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "unauthorized",
		})
	}

	ctx := c.Context()
	projects, err := h.repo.FindByUserID(ctx, userID)
	if err != nil {
		h.logger.Error("failed to list projects", zap.Error(err))
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to list projects",
		})
	}

	// Return empty array if no projects found
	if projects == nil {
		projects = []*Project{}
	}

	return c.Status(fiber.StatusOK).JSON(ToResponseList(projects))
}

// Get handles GET /api/v1/projects/:id
func (h *Handler) Get(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "unauthorized",
		})
	}

	projectIDStr := c.Params("id")
	projectID, err := uuid.FromString(projectIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid project ID",
		})
	}

	ctx := c.Context()
	project, err := h.repo.FindByID(ctx, projectID)
	if err != nil {
		// Check if it's a "not found" error
		if strings.Contains(err.Error(), "no rows") {
			return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
				Error: "project not found",
			})
		}
		h.logger.Error("failed to get project", zap.Error(err), zap.String("projectId", projectIDStr))
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to get project",
		})
	}

	// Authorization check: ensure project belongs to user
	if project.UserID != userID {
		h.logger.Warn("forbidden access attempt", zap.String("projectId", projectIDStr), zap.String("userId", userID.String()))
		return c.Status(fiber.StatusForbidden).JSON(ErrorResponse{
			Error: "forbidden: you don't have access to this project",
		})
	}

	return c.Status(fiber.StatusOK).JSON(ToResponse(project))
}

// Update handles PUT /api/v1/projects/:id
func (h *Handler) Update(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "unauthorized",
		})
	}

	projectIDStr := c.Params("id")
	projectID, err := uuid.FromString(projectIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid project ID",
		})
	}

	var req UpdateProjectRequest
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

	// Find the project first
	project, err := h.repo.FindByID(ctx, projectID)
	if err != nil {
		if strings.Contains(err.Error(), "no rows") {
			return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
				Error: "project not found",
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to get project",
		})
	}

	// Authorization check
	if project.UserID != userID {
		h.logger.Warn("forbidden access attempt on update", zap.String("projectId", projectIDStr), zap.String("userId", userID.String()))
		return c.Status(fiber.StatusForbidden).JSON(ErrorResponse{
			Error: "forbidden: you don't have access to this project",
		})
	}

	// Update only the name
	project.Name = req.Name

	if err := h.repo.Update(ctx, project); err != nil {
		h.logger.Error("failed to update project", zap.Error(err), zap.String("projectId", projectIDStr))
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to update project",
		})
	}

	h.logger.Info("project updated", zap.String("id", project.ID.String()), zap.String("name", project.Name))
	return c.Status(fiber.StatusOK).JSON(ToResponse(project))
}

// Delete handles DELETE /api/v1/projects/:id
func (h *Handler) Delete(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "unauthorized",
		})
	}

	projectIDStr := c.Params("id")
	projectID, err := uuid.FromString(projectIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid project ID",
		})
	}

	ctx := c.Context()

	// Find the project first to check ownership
	project, err := h.repo.FindByID(ctx, projectID)
	if err != nil {
		if strings.Contains(err.Error(), "no rows") {
			return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
				Error: "project not found",
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to get project",
		})
	}

	// Authorization check
	if project.UserID != userID {
		h.logger.Warn("forbidden access attempt on delete", zap.String("projectId", projectIDStr), zap.String("userId", userID.String()))
		return c.Status(fiber.StatusForbidden).JSON(ErrorResponse{
			Error: "forbidden: you don't have access to this project",
		})
	}

	if err := h.repo.Delete(ctx, projectID); err != nil {
		h.logger.Error("failed to delete project", zap.Error(err), zap.String("projectId", projectIDStr))
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to delete project",
		})
	}

	h.logger.Info("project deleted", zap.String("id", projectIDStr))
	return c.SendStatus(fiber.StatusNoContent)
}
