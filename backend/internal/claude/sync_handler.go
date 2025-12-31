package claude

import (
	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
)

// SyncHandler handles Claude history sync HTTP requests
type SyncHandler struct {
	syncService *SyncService
}

// NewSyncHandler creates a new sync handler
func NewSyncHandler(syncService *SyncService) *SyncHandler {
	return &SyncHandler{
		syncService: syncService,
	}
}

// Sync handles POST /api/v1/sync
// Syncs Claude CLI history to database for the current user
func (h *SyncHandler) Sync(c *fiber.Ctx) error {
	userIDStr, ok := c.Locals("userID").(string)
	if !ok {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "invalid user ID",
		})
	}

	userID, err := uuid.FromString(userIDStr)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "invalid user ID format",
		})
	}

	result, err := h.syncService.SyncForUser(c.Context(), userID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error": "sync failed",
		})
	}

	return c.Status(fiber.StatusOK).JSON(result)
}
