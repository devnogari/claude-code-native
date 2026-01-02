package queue

import (
	"context"
	"fmt"

	"github.com/gofrs/uuid/v5"
	"go.uber.org/zap"

	"github.com/devnogari/claude-code-native/backend/internal/storage"
)

// Service handles queue business logic.
type Service interface {
	// AddToQueue creates a new queued message with optional images.
	AddToQueue(ctx context.Context, conversationID, userID uuid.UUID, content string, images []ImageData) (*QueuedMessage, error)

	// GetQueue retrieves all queued messages for a conversation.
	GetQueue(ctx context.Context, conversationID uuid.UUID) ([]QueuedMessage, error)

	// GetNextMessage retrieves the next message to process from the queue.
	GetNextMessage(ctx context.Context, conversationID uuid.UUID) (*QueuedMessage, error)

	// RemoveFromQueue removes a message from the queue and cleans up images.
	RemoveFromQueue(ctx context.Context, messageID uuid.UUID) error

	// ClearQueue removes all messages from a conversation's queue.
	ClearQueue(ctx context.Context, conversationID uuid.UUID) error

	// GetImageURL returns the URL for accessing an image.
	GetImageURL(storagePath string) string

	// GetImageData retrieves image data for serving.
	GetImageData(ctx context.Context, storagePath string) ([]byte, error)
}

// ImageData represents uploaded image data.
type ImageData struct {
	Data      []byte
	MediaType string
	FileName  string
	Width     *int
	Height    *int
}

type service struct {
	repo    Repository
	storage storage.ImageStorage
	logger  *zap.Logger
}

// NewService creates a new queue service.
func NewService(repo Repository, storage storage.ImageStorage, logger *zap.Logger) Service {
	return &service{
		repo:    repo,
		storage: storage,
		logger:  logger,
	}
}

func (s *service) AddToQueue(ctx context.Context, conversationID, userID uuid.UUID, content string, images []ImageData) (*QueuedMessage, error) {
	// Create the queued message
	msg := NewQueuedMessage(conversationID, userID, content)

	if err := s.repo.Create(ctx, msg); err != nil {
		return nil, fmt.Errorf("failed to create queued message: %w", err)
	}

	// Save and attach images
	for _, imgData := range images {
		storagePath, err := s.storage.Save(ctx, imgData.Data, imgData.MediaType)
		if err != nil {
			s.logger.Error("failed to save image", zap.Error(err))
			continue // Continue with other images
		}

		fileName := &imgData.FileName
		if imgData.FileName == "" {
			fileName = nil
		}

		img := &QueuedMessageImage{
			QueuedMessageID: msg.ID,
			StoragePath:     storagePath,
			MediaType:       imgData.MediaType,
			FileName:        fileName,
			Width:           imgData.Width,
			Height:          imgData.Height,
		}

		if err := s.repo.CreateImage(ctx, img); err != nil {
			s.logger.Error("failed to create image record", zap.Error(err))
			// Try to clean up the saved file
			if delErr := s.storage.Delete(ctx, storagePath); delErr != nil {
				s.logger.Warn("failed to clean up orphaned image file",
					zap.String("path", storagePath),
					zap.Error(delErr))
			}
			continue
		}

		msg.Images = append(msg.Images, *img)
	}

	s.logger.Info("added message to queue",
		zap.String("message_id", msg.ID.String()),
		zap.String("conversation_id", conversationID.String()),
		zap.Int("image_count", len(msg.Images)),
	)

	return msg, nil
}

func (s *service) GetQueue(ctx context.Context, conversationID uuid.UUID) ([]QueuedMessage, error) {
	messages, err := s.repo.GetByConversation(ctx, conversationID)
	if err != nil {
		return nil, fmt.Errorf("failed to get queue: %w", err)
	}
	return messages, nil
}

func (s *service) GetNextMessage(ctx context.Context, conversationID uuid.UUID) (*QueuedMessage, error) {
	msg, err := s.repo.GetNextInQueue(ctx, conversationID)
	if err != nil {
		return nil, err
	}
	return msg, nil
}

func (s *service) RemoveFromQueue(ctx context.Context, messageID uuid.UUID) error {
	// Get the message first to clean up images
	msg, err := s.repo.GetByID(ctx, messageID)
	if err != nil {
		return fmt.Errorf("failed to get message for removal: %w", err)
	}
	if msg == nil {
		// Message not found, might have been already deleted
		s.logger.Debug("message not found for removal", zap.String("message_id", messageID.String()))
		return nil
	}

	// Delete images from storage
	for _, img := range msg.Images {
		if err := s.storage.Delete(ctx, img.StoragePath); err != nil {
			s.logger.Warn("failed to delete image from storage",
				zap.String("path", img.StoragePath),
				zap.Error(err),
			)
		}
	}

	// Delete the message (cascade deletes image records)
	if err := s.repo.Delete(ctx, messageID); err != nil {
		return fmt.Errorf("failed to delete queued message: %w", err)
	}

	s.logger.Info("removed message from queue", zap.String("message_id", messageID.String()))
	return nil
}

func (s *service) ClearQueue(ctx context.Context, conversationID uuid.UUID) error {
	// Get all messages to clean up images
	messages, err := s.repo.GetByConversation(ctx, conversationID)
	if err != nil {
		return fmt.Errorf("failed to get queue for clearing: %w", err)
	}

	// Delete images from storage
	for _, msg := range messages {
		for _, img := range msg.Images {
			if err := s.storage.Delete(ctx, img.StoragePath); err != nil {
				s.logger.Warn("failed to delete image from storage",
					zap.String("path", img.StoragePath),
					zap.Error(err),
				)
			}
		}
	}

	// Delete all messages (cascade deletes image records)
	if err := s.repo.DeleteByConversation(ctx, conversationID); err != nil {
		return fmt.Errorf("failed to clear queue: %w", err)
	}

	s.logger.Info("cleared queue",
		zap.String("conversation_id", conversationID.String()),
		zap.Int("message_count", len(messages)),
	)
	return nil
}

func (s *service) GetImageURL(storagePath string) string {
	return s.storage.GetURL(storagePath)
}

func (s *service) GetImageData(ctx context.Context, storagePath string) ([]byte, error) {
	return s.storage.Get(ctx, storagePath)
}
