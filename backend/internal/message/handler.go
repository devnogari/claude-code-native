package message

import (
	"strconv"

	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
)

const (
	DefaultLimit = 50
	MaxLimit     = 200
)

// Handler handles message HTTP requests
type Handler struct {
	repo *Repository
}

// NewHandler creates a new message handler
func NewHandler(repo *Repository) *Handler {
	return &Handler{repo: repo}
}

// ListByConversation handles GET /api/v1/conversations/:conversationId/messages
// Query params: limit (default 50, max 200), offset (default 0)
func (h *Handler) ListByConversation(c *fiber.Ctx) error {
	conversationIDStr := c.Params("conversationId")
	if conversationIDStr == "" {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "conversation ID is required",
		})
	}

	conversationID, err := uuid.FromString(conversationIDStr)
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "invalid conversation ID",
		})
	}

	// Parse pagination params
	limit := DefaultLimit
	if limitStr := c.Query("limit"); limitStr != "" {
		if parsed, err := strconv.Atoi(limitStr); err == nil && parsed > 0 {
			limit = parsed
			if limit > MaxLimit {
				limit = MaxLimit
			}
		}
	}

	offset := 0
	if offsetStr := c.Query("offset"); offsetStr != "" {
		if parsed, err := strconv.Atoi(offsetStr); err == nil && parsed >= 0 {
			offset = parsed
		}
	}

	result, err := h.repo.FindByConversationIDPaginated(c.Context(), conversationID, limit, offset)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error": "failed to fetch messages",
		})
	}

	// Return empty array instead of null
	if result.Messages == nil {
		result.Messages = []*Message{}
	}

	return c.Status(fiber.StatusOK).JSON(result)
}
