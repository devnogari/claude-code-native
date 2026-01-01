package claude

import (
	"sort"
	"strconv"

	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
	"go.uber.org/zap"
)

// HistoryHandler handles Claude Code history HTTP requests
type HistoryHandler struct {
	cache   *HistoryCache
	favRepo *SessionFavoriteRepository
	logger  *zap.Logger
}

// NewHistoryHandler creates a new history handler with cache and favorites
func NewHistoryHandler(params HistoryHandlerParams) *HistoryHandler {
	return &HistoryHandler{
		cache:   params.Cache,
		favRepo: params.FavRepo,
		logger:  params.Logger,
	}
}

// ErrorResponse represents an error response
type ErrorResponse struct {
	Error string `json:"error"`
}

// getUserID extracts the user ID from fiber context locals
func getUserIDFromContext(c *fiber.Ctx) (uuid.UUID, error) {
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

// ListProjects handles GET /api/v1/claude-history/projects
func (h *HistoryHandler) ListProjects(c *fiber.Ctx) error {
	h.logger.Info("ListProjects called")

	userID, err := getUserIDFromContext(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{Error: "unauthorized"})
	}

	projects := h.cache.GetProjects()
	h.logger.Info("Got projects from cache", zap.Int("count", len(projects)))

	// Debug: log sessions for claude-code-native
	for _, p := range projects {
		if p.Name == "claude-code-native" {
			h.logger.Info("DEBUG: claude-code-native sessions",
				zap.Int("count", len(p.Sessions)))
			for _, s := range p.Sessions {
				h.logger.Info("DEBUG: session",
					zap.String("id", s.ID[:8]),
					zap.Int("messageCount", s.MessageCount),
					zap.String("firstMsg", truncateString(s.FirstMessage, 30)))
			}
			break
		}
	}

	// Return empty array if no projects found
	if projects == nil {
		projects = []ClaudeProject{}
	}

	// Get all favorites for this user
	favorites, err := h.favRepo.GetAllFavorites(c.Context(), userID)
	if err != nil {
		// Log error but continue - favorites are optional
		h.logger.Warn("Failed to get favorites", zap.Error(err))
		favorites = make(map[string]bool)
	} else {
		h.logger.Info("Loaded favorites", zap.Int("count", len(favorites)), zap.String("userID", userID.String()))
	}

	// Mark favorite sessions and re-sort
	for i := range projects {
		for j := range projects[i].Sessions {
			if favorites[projects[i].Sessions[j].ID] {
				projects[i].Sessions[j].IsFavorite = true
			}
		}
		// Sort sessions: favorites first, then by updatedAt DESC
		sort.Slice(projects[i].Sessions, func(a, b int) bool {
			// Favorites always come first
			if projects[i].Sessions[a].IsFavorite != projects[i].Sessions[b].IsFavorite {
				return projects[i].Sessions[a].IsFavorite
			}
			// Then sort by updatedAt DESC
			return projects[i].Sessions[a].UpdatedAt.After(projects[i].Sessions[b].UpdatedAt)
		})
	}

	// Sort projects by their most recent session's updatedAt DESC
	sort.Slice(projects, func(i, j int) bool {
		var iLatest, jLatest = projects[i].LastAccessed, projects[j].LastAccessed
		// Use most recent session's updatedAt if available
		if len(projects[i].Sessions) > 0 {
			iLatest = projects[i].Sessions[0].UpdatedAt
		}
		if len(projects[j].Sessions) > 0 {
			jLatest = projects[j].Sessions[0].UpdatedAt
		}
		return iLatest.After(jLatest)
	})

	h.logger.Info("Final project order after sorting")
	for i, p := range projects {
		favCount := 0
		for _, s := range p.Sessions {
			if s.IsFavorite {
				favCount++
			}
		}
		h.logger.Info("Project order",
			zap.Int("index", i),
			zap.String("name", p.Name),
			zap.Int("sessions", len(p.Sessions)),
			zap.Int("favorites", favCount))
		// Log session order within project
		for j, s := range p.Sessions {
			h.logger.Info("  Session order",
				zap.Int("index", j),
				zap.String("sessionID", s.ID[:8]),
				zap.Time("updatedAt", s.UpdatedAt),
				zap.String("firstMsg", truncateString(s.FirstMessage, 30)))
		}
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
// Query params: limit (default 50, max 200), offset (default 0), summary (default true - truncate content)
func (h *HistoryHandler) GetSessionMessages(c *fiber.Ctx) error {
	encodedPath := c.Params("encodedPath")
	sessionID := c.Params("sessionId")

	if encodedPath == "" || sessionID == "" {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "encoded path and session ID are required",
		})
	}

	// Parse pagination params
	limit := 50
	if limitStr := c.Query("limit"); limitStr != "" {
		if parsed, err := parseIntParam(limitStr); err == nil && parsed > 0 {
			limit = parsed
			if limit > 200 {
				limit = 200
			}
		}
	}

	offset := 0
	if offsetStr := c.Query("offset"); offsetStr != "" {
		if parsed, err := parseIntParam(offsetStr); err == nil && parsed >= 0 {
			offset = parsed
		}
	}

	// Parse summary param (default true to reduce payload size)
	summary := true
	if summaryStr := c.Query("summary"); summaryStr == "false" {
		summary = false
	}

	// Max content length for summary mode
	maxContentLength := 500
	if maxLenStr := c.Query("maxContentLength"); maxLenStr != "" {
		if parsed, err := parseIntParam(maxLenStr); err == nil && parsed > 0 {
			maxContentLength = parsed
		}
	}

	result, err := h.cache.GetSessionMessagesPaginated(encodedPath, sessionID, limit, offset)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
			Error: "session not found",
		})
	}

	// Return empty array if no messages found
	if result.Messages == nil {
		result.Messages = []ClaudeMessage{}
	}

	// Truncate content in summary mode
	if summary {
		for i := range result.Messages {
			truncateMessageContent(&result.Messages[i], maxContentLength)
		}
	}

	return c.Status(fiber.StatusOK).JSON(result)
}

// parseIntParam safely parses an integer from string
func parseIntParam(s string) (int, error) {
	return strconv.Atoi(s)
}

// Refresh handles POST /api/v1/claude-history/refresh - force refresh cache
func (h *HistoryHandler) Refresh(c *fiber.Ctx) error {
	h.cache.Refresh()
	return c.Status(fiber.StatusOK).JSON(fiber.Map{
		"message": "cache refreshed",
	})
}

// ToggleSessionFavoriteRequest represents the request body for toggling favorite
type ToggleSessionFavoriteRequest struct {
	ProjectPath string `json:"project_path"`
}

// ToggleSessionFavoriteResponse represents the response for toggling favorite
type ToggleSessionFavoriteResponse struct {
	SessionID  string `json:"session_id"`
	IsFavorite bool   `json:"is_favorite"`
}

// ToggleSessionFavorite handles POST /api/v1/claude-history/sessions/:sessionId/favorite
func (h *HistoryHandler) ToggleSessionFavorite(c *fiber.Ctx) error {
	userID, err := getUserIDFromContext(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{Error: "unauthorized"})
	}

	sessionID := c.Params("sessionId")
	if sessionID == "" {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{Error: "session ID is required"})
	}

	var req ToggleSessionFavoriteRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{Error: "invalid request body"})
	}

	if req.ProjectPath == "" {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{Error: "project_path is required"})
	}

	isFavorite, err := h.favRepo.ToggleFavorite(c.Context(), userID, sessionID, req.ProjectPath)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{Error: "failed to toggle favorite"})
	}

	return c.Status(fiber.StatusOK).JSON(ToggleSessionFavoriteResponse{
		SessionID:  sessionID,
		IsFavorite: isFavorite,
	})
}
