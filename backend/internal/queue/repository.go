package queue

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

// Repository defines the interface for queue data operations.
type Repository interface {
	// Create adds a new queued message to the database.
	Create(ctx context.Context, msg *QueuedMessage) error

	// CreateImage adds a new image record for a queued message.
	CreateImage(ctx context.Context, img *QueuedMessageImage) error

	// GetByID retrieves a queued message by its ID with images.
	GetByID(ctx context.Context, id uuid.UUID) (*QueuedMessage, error)

	// GetByConversation retrieves all queued messages for a conversation, ordered by queued_at.
	GetByConversation(ctx context.Context, conversationID uuid.UUID) ([]QueuedMessage, error)

	// GetNextInQueue retrieves the oldest queued message for a conversation.
	GetNextInQueue(ctx context.Context, conversationID uuid.UUID) (*QueuedMessage, error)

	// Delete removes a queued message by ID. Images are cascade deleted.
	Delete(ctx context.Context, id uuid.UUID) error

	// DeleteByConversation removes all queued messages for a conversation.
	DeleteByConversation(ctx context.Context, conversationID uuid.UUID) error

	// GetImagesByMessageID retrieves all images for a queued message.
	GetImagesByMessageID(ctx context.Context, messageID uuid.UUID) ([]QueuedMessageImage, error)
}

type repository struct {
	db *bun.DB
}

// NewRepository creates a new queue repository.
func NewRepository(db *bun.DB) Repository {
	return &repository{db: db}
}

func (r *repository) Create(ctx context.Context, msg *QueuedMessage) error {
	if err := msg.Validate(); err != nil {
		return fmt.Errorf("validation failed: %w", err)
	}

	_, err := r.db.NewInsert().Model(msg).Exec(ctx)
	if err != nil {
		return fmt.Errorf("failed to create queued message: %w", err)
	}
	return nil
}

func (r *repository) CreateImage(ctx context.Context, img *QueuedMessageImage) error {
	if err := img.Validate(); err != nil {
		return fmt.Errorf("validation failed: %w", err)
	}

	_, err := r.db.NewInsert().Model(img).Exec(ctx)
	if err != nil {
		return fmt.Errorf("failed to create queued message image: %w", err)
	}
	return nil
}

func (r *repository) GetByID(ctx context.Context, id uuid.UUID) (*QueuedMessage, error) {
	msg := new(QueuedMessage)
	err := r.db.NewSelect().
		Model(msg).
		Relation("Images").
		Where("qm.id = ?", id).
		Scan(ctx)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil // Message not found
		}
		return nil, fmt.Errorf("failed to get queued message: %w", err)
	}
	return msg, nil
}

func (r *repository) GetByConversation(ctx context.Context, conversationID uuid.UUID) ([]QueuedMessage, error) {
	var messages []QueuedMessage
	err := r.db.NewSelect().
		Model(&messages).
		Relation("Images").
		Where("qm.conversation_id = ?", conversationID).
		Order("qm.queued_at ASC").
		Scan(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to get queued messages: %w", err)
	}
	return messages, nil
}

func (r *repository) GetNextInQueue(ctx context.Context, conversationID uuid.UUID) (*QueuedMessage, error) {
	msg := new(QueuedMessage)
	err := r.db.NewSelect().
		Model(msg).
		Relation("Images").
		Where("qm.conversation_id = ?", conversationID).
		Order("qm.queued_at ASC").
		Limit(1).
		Scan(ctx)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil // No messages in queue
		}
		return nil, fmt.Errorf("failed to get next queued message: %w", err)
	}
	return msg, nil
}

func (r *repository) Delete(ctx context.Context, id uuid.UUID) error {
	_, err := r.db.NewDelete().
		Model((*QueuedMessage)(nil)).
		Where("id = ?", id).
		Exec(ctx)
	if err != nil {
		return fmt.Errorf("failed to delete queued message: %w", err)
	}
	return nil
}

func (r *repository) DeleteByConversation(ctx context.Context, conversationID uuid.UUID) error {
	_, err := r.db.NewDelete().
		Model((*QueuedMessage)(nil)).
		Where("conversation_id = ?", conversationID).
		Exec(ctx)
	if err != nil {
		return fmt.Errorf("failed to delete queued messages: %w", err)
	}
	return nil
}

func (r *repository) GetImagesByMessageID(ctx context.Context, messageID uuid.UUID) ([]QueuedMessageImage, error) {
	var images []QueuedMessageImage
	err := r.db.NewSelect().
		Model(&images).
		Where("queued_message_id = ?", messageID).
		Scan(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to get queued message images: %w", err)
	}
	return images, nil
}
