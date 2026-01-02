package queue

import (
	"context"
	"io"
	"strings"

	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
	"go.uber.org/zap"
)

// QueueBroadcaster defines the interface for broadcasting queue updates via WebSocket
type QueueBroadcaster interface {
	BroadcastQueueAdd(convID uuid.UUID, msg *QueuedMessage)
	BroadcastQueueRemove(convID uuid.UUID, messageID uuid.UUID)
}

// ConversationInfo holds minimal conversation data for authorization
type ConversationInfo struct {
	ID        uuid.UUID
	ProjectID uuid.UUID
}

// ProjectInfo holds minimal project data for authorization
type ProjectInfo struct {
	ID     uuid.UUID
	UserID uuid.UUID
}

// ConversationFinder finds conversations by ID (for authorization)
type ConversationFinder interface {
	FindConversationByID(ctx context.Context, id uuid.UUID) (*ConversationInfo, error)
}

// ProjectFinder finds projects by ID (for authorization)
type ProjectFinder interface {
	FindProjectByID(ctx context.Context, id uuid.UUID) (*ProjectInfo, error)
}

// Handler handles HTTP requests for queue operations.
type Handler struct {
	service     Service
	convFinder  ConversationFinder
	projFinder  ProjectFinder
	logger      *zap.Logger
	broadcaster QueueBroadcaster
}

// NewHandler creates a new queue handler.
func NewHandler(service Service, convFinder ConversationFinder, projFinder ProjectFinder, logger *zap.Logger) *Handler {
	return &Handler{
		service:    service,
		convFinder: convFinder,
		projFinder: projFinder,
		logger:     logger,
	}
}

// SetBroadcaster sets the WebSocket broadcaster for queue updates.
// This is called after the ws.Handler is created to avoid circular dependencies.
func (h *Handler) SetBroadcaster(broadcaster QueueBroadcaster) {
	h.broadcaster = broadcaster
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

// verifyConversationAccess checks if the user has access to the conversation
func (h *Handler) verifyConversationAccess(ctx context.Context, conversationID, userID uuid.UUID) error {
	// Find the conversation
	conv, err := h.convFinder.FindConversationByID(ctx, conversationID)
	if err != nil {
		if strings.Contains(err.Error(), "no rows") {
			return fiber.NewError(fiber.StatusNotFound, "conversation not found")
		}
		return fiber.NewError(fiber.StatusInternalServerError, "failed to get conversation")
	}

	// Find the project to verify ownership
	proj, err := h.projFinder.FindProjectByID(ctx, conv.ProjectID)
	if err != nil {
		if strings.Contains(err.Error(), "no rows") {
			return fiber.NewError(fiber.StatusNotFound, "project not found")
		}
		return fiber.NewError(fiber.StatusInternalServerError, "failed to get project")
	}

	// Check if user owns the project
	if proj.UserID != userID {
		return fiber.NewError(fiber.StatusForbidden, "forbidden: you don't have access to this conversation")
	}

	return nil
}

// GetQueue handles GET /api/v1/conversations/:id/queue
func (h *Handler) GetQueue(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "unauthorized",
		})
	}

	conversationID, err := uuid.FromString(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "invalid conversation ID",
		})
	}

	ctx := c.Context()

	// Verify user has access to this conversation
	if err := h.verifyConversationAccess(ctx, conversationID, userID); err != nil {
		if fiberErr, ok := err.(*fiber.Error); ok {
			return c.Status(fiberErr.Code).JSON(fiber.Map{
				"error": fiberErr.Message,
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error": "failed to verify access",
		})
	}

	messages, err := h.service.GetQueue(ctx, conversationID)
	if err != nil {
		h.logger.Error("failed to get queue", zap.Error(err))
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error": "failed to get queue",
		})
	}

	response := QueueListResponse{
		Messages: ToResponseList(messages, h.service.GetImageURL),
	}
	return c.JSON(response)
}

// AddToQueue handles POST /api/v1/conversations/:id/queue
// Accepts multipart/form-data with:
// - content: string (required)
// - images[]: file (optional, multiple)
func (h *Handler) AddToQueue(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "unauthorized",
		})
	}

	conversationID, err := uuid.FromString(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "invalid conversation ID",
		})
	}

	ctx := c.Context()

	// Verify user has access to this conversation
	if err := h.verifyConversationAccess(ctx, conversationID, userID); err != nil {
		if fiberErr, ok := err.(*fiber.Error); ok {
			return c.Status(fiberErr.Code).JSON(fiber.Map{
				"error": fiberErr.Message,
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error": "failed to verify access",
		})
	}

	// Get content from form
	content := strings.TrimSpace(c.FormValue("content"))
	if content == "" {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "content is required",
		})
	}

	// Process uploaded images
	var images []ImageData
	form, err := c.MultipartForm()
	if err == nil && form != nil && form.File != nil {
		files := form.File["images[]"]
		if files == nil {
			files = form.File["images"]
		}

		const maxImageSize = 10 * 1024 * 1024 // 10MB

		for _, fileHeader := range files {
			// Check file size
			if fileHeader.Size > maxImageSize {
				h.logger.Warn("skipping image: file too large",
					zap.String("filename", fileHeader.Filename),
					zap.Int64("size", fileHeader.Size))
				continue
			}

			// Validate media type
			mediaType := fileHeader.Header.Get("Content-Type")
			if !isValidImageType(mediaType) {
				h.logger.Warn("skipping invalid image type", zap.String("type", mediaType))
				continue
			}

			// Read file content - close immediately after reading
			file, err := fileHeader.Open()
			if err != nil {
				h.logger.Warn("failed to open uploaded file", zap.Error(err))
				continue
			}

			data, err := io.ReadAll(file)
			file.Close() // Close immediately after reading to avoid leak
			if err != nil {
				h.logger.Warn("failed to read uploaded file", zap.Error(err))
				continue
			}

			images = append(images, ImageData{
				Data:      data,
				MediaType: mediaType,
				FileName:  fileHeader.Filename,
			})
		}
	}

	// Add to queue
	msg, err := h.service.AddToQueue(ctx, conversationID, userID, content, images)
	if err != nil {
		h.logger.Error("failed to add to queue", zap.Error(err))
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error": "failed to add to queue",
		})
	}

	// Broadcast queue_add to all clients in this conversation
	if h.broadcaster != nil {
		h.broadcaster.BroadcastQueueAdd(conversationID, msg)
	}

	return c.Status(fiber.StatusCreated).JSON(fiber.Map{
		"message": ToResponse(msg, h.service.GetImageURL),
	})
}

// RemoveFromQueue handles DELETE /api/v1/conversations/:id/queue/:messageId
func (h *Handler) RemoveFromQueue(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "unauthorized",
		})
	}

	conversationID, err := uuid.FromString(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "invalid conversation ID",
		})
	}

	messageID, err := uuid.FromString(c.Params("messageId"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "invalid message ID",
		})
	}

	ctx := c.Context()

	// Verify user has access to this conversation
	if err := h.verifyConversationAccess(ctx, conversationID, userID); err != nil {
		if fiberErr, ok := err.(*fiber.Error); ok {
			return c.Status(fiberErr.Code).JSON(fiber.Map{
				"error": fiberErr.Message,
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error": "failed to verify access",
		})
	}

	if err := h.service.RemoveFromQueue(ctx, messageID); err != nil {
		h.logger.Error("failed to remove from queue", zap.Error(err))
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error": "failed to remove from queue",
		})
	}

	// Broadcast queue_remove to all clients in this conversation
	if h.broadcaster != nil {
		h.broadcaster.BroadcastQueueRemove(conversationID, messageID)
	}

	return c.SendStatus(fiber.StatusNoContent)
}

// ClearQueue handles DELETE /api/v1/conversations/:id/queue
func (h *Handler) ClearQueue(c *fiber.Ctx) error {
	userID, err := getUserID(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "unauthorized",
		})
	}

	conversationID, err := uuid.FromString(c.Params("id"))
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "invalid conversation ID",
		})
	}

	ctx := c.Context()

	// Verify user has access to this conversation
	if err := h.verifyConversationAccess(ctx, conversationID, userID); err != nil {
		if fiberErr, ok := err.(*fiber.Error); ok {
			return c.Status(fiberErr.Code).JSON(fiber.Map{
				"error": fiberErr.Message,
			})
		}
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error": "failed to verify access",
		})
	}

	if err := h.service.ClearQueue(ctx, conversationID); err != nil {
		h.logger.Error("failed to clear queue", zap.Error(err))
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error": "failed to clear queue",
		})
	}

	return c.SendStatus(fiber.StatusNoContent)
}

// ServeImage handles GET /api/v1/images/*
// Serves image data from storage with appropriate content type.
func (h *Handler) ServeImage(c *fiber.Ctx) error {
	path := c.Params("*")
	if path == "" {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
			"error": "image path required",
		})
	}

	// Retrieve image data from storage
	data, err := h.service.GetImageData(c.Context(), path)
	if err != nil {
		h.logger.Debug("image not found", zap.String("path", path), zap.Error(err))
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{
			"error": "image not found",
		})
	}

	// Determine content type from path extension
	contentType := "application/octet-stream"
	lowerPath := strings.ToLower(path)
	switch {
	case strings.HasSuffix(lowerPath, ".png"):
		contentType = "image/png"
	case strings.HasSuffix(lowerPath, ".jpg"), strings.HasSuffix(lowerPath, ".jpeg"):
		contentType = "image/jpeg"
	case strings.HasSuffix(lowerPath, ".gif"):
		contentType = "image/gif"
	case strings.HasSuffix(lowerPath, ".webp"):
		contentType = "image/webp"
	}

	c.Set("Content-Type", contentType)
	c.Set("Cache-Control", "public, max-age=31536000") // Cache for 1 year (images are immutable)
	return c.Send(data)
}

// isValidImageType checks if the media type is a supported image format.
func isValidImageType(mediaType string) bool {
	validTypes := []string{
		"image/png",
		"image/jpeg",
		"image/jpg",
		"image/gif",
		"image/webp",
	}
	for _, t := range validTypes {
		if strings.EqualFold(mediaType, t) {
			return true
		}
	}
	return false
}
