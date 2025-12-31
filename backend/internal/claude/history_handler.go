package claude

import (
	"github.com/gofiber/fiber/v2"
)

// HistoryHandler handles Claude Code history HTTP requests
type HistoryHandler struct {
	cache *HistoryCache
}

// NewHistoryHandlerWithCache creates a new history handler with cache
func NewHistoryHandlerWithCache(cache *HistoryCache) *HistoryHandler {
	return &HistoryHandler{
		cache: cache,
	}
}

// ErrorResponse represents an error response
type ErrorResponse struct {
	Error string `json:"error"`
}

// ListProjects handles GET /api/v1/claude-history/projects
func (h *HistoryHandler) ListProjects(c *fiber.Ctx) error {
	projects := h.cache.GetProjects()

	// Return empty array if no projects found
	if projects == nil {
		projects = []ClaudeProject{}
	}

	return c.Status(fiber.StatusOK).JSON(projects)
}

// GetProject handles GET /api/v1/claude-history/projects/:encodedPath
func (h *HistoryHandler) GetProject(c *fiber.Ctx) error {
	encodedPath := c.Params("encodedPath")
	if encodedPath == "" {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "encoded path is required",
		})
	}

	project, ok := h.cache.GetProject(encodedPath)
	if !ok {
		return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
			Error: "project not found",
		})
	}

	return c.Status(fiber.StatusOK).JSON(project)
}

// GetSessionMessages handles GET /api/v1/claude-history/projects/:encodedPath/sessions/:sessionId
func (h *HistoryHandler) GetSessionMessages(c *fiber.Ctx) error {
	encodedPath := c.Params("encodedPath")
	sessionID := c.Params("sessionId")

	if encodedPath == "" || sessionID == "" {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "encoded path and session ID are required",
		})
	}

	messages, err := h.cache.GetSessionMessages(encodedPath, sessionID)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
			Error: "session not found",
		})
	}

	// Return empty array if no messages found
	if messages == nil {
		messages = []ClaudeMessage{}
	}

	return c.Status(fiber.StatusOK).JSON(messages)
}

// Refresh handles POST /api/v1/claude-history/refresh - force refresh cache
func (h *HistoryHandler) Refresh(c *fiber.Ctx) error {
	h.cache.Refresh()
	return c.Status(fiber.StatusOK).JSON(fiber.Map{
		"message": "cache refreshed",
	})
}
