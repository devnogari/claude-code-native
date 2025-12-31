package message

import (
	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
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

	messages, err := h.repo.FindByConversationID(c.Context(), conversationID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error": "failed to fetch messages",
		})
	}

	// Return empty array instead of null
	if messages == nil {
		messages = []*Message{}
	}

	return c.Status(fiber.StatusOK).JSON(messages)
}
