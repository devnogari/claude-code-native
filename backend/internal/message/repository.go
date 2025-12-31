package message

import (
	"context"
	"fmt"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

// Repository handles database operations for messages
type Repository struct {
	db *bun.DB
}

// NewRepository creates a new message repository
func NewRepository(db *bun.DB) *Repository {
	return &Repository{db: db}
}

// Create inserts a new message into the database
func (r *Repository) Create(ctx context.Context, message *Message) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	if err := message.Validate(); err != nil {
		return fmt.Errorf("validation failed: %w", err)
	}

	if message.ID == uuid.Nil {
		id, err := uuid.NewV7()
		if err != nil {
			return fmt.Errorf("failed to generate UUID: %w", err)
		}
		message.ID = id
	}

	_, err := r.db.NewInsert().Model(message).Exec(ctx)
	return err
}

// FindByID retrieves a message by its ID
func (r *Repository) FindByID(ctx context.Context, id uuid.UUID) (*Message, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	message := new(Message)
	err := r.db.NewSelect().
		Model(message).
		Where("id = ?", id).
		Scan(ctx)

	if err != nil {
		return nil, err
	}
	return message, nil
}

// FindByConversationID retrieves all messages for a conversation, ordered by created_at
func (r *Repository) FindByConversationID(ctx context.Context, conversationID uuid.UUID) ([]*Message, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	var messages []*Message
	err := r.db.NewSelect().
		Model(&messages).
		Where("conversation_id = ?", conversationID).
		Order("created_at ASC").
		Scan(ctx)

	if err != nil {
		return nil, err
	}
	return messages, nil
}

// PaginatedResult contains messages with pagination info
type PaginatedResult struct {
	Messages   []*Message `json:"messages"`
	Total      int        `json:"total"`
	Limit      int        `json:"limit"`
	Offset     int        `json:"offset"`
	HasMore    bool       `json:"has_more"`
}

// FindByConversationIDPaginated retrieves messages with pagination (most recent first)
func (r *Repository) FindByConversationIDPaginated(ctx context.Context, conversationID uuid.UUID, limit, offset int) (*PaginatedResult, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	// Get total count
	total, err := r.db.NewSelect().
		Model((*Message)(nil)).
		Where("conversation_id = ?", conversationID).
		Count(ctx)
	if err != nil {
		return nil, err
	}

	// Get paginated messages (most recent first, then reverse for display)
	var messages []*Message
	err = r.db.NewSelect().
		Model(&messages).
		Where("conversation_id = ?", conversationID).
		Order("sequence_num DESC").
		Limit(limit).
		Offset(offset).
		Scan(ctx)

	if err != nil {
		return nil, err
	}

	// Reverse to show oldest first within the page
	for i, j := 0, len(messages)-1; i < j; i, j = i+1, j-1 {
		messages[i], messages[j] = messages[j], messages[i]
	}

	return &PaginatedResult{
		Messages: messages,
		Total:    total,
		Limit:    limit,
		Offset:   offset,
		HasMore:  offset+len(messages) < total,
	}, nil
}

// Delete removes a message by its ID
func (r *Repository) Delete(ctx context.Context, id uuid.UUID) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	_, err := r.db.NewDelete().
		Model((*Message)(nil)).
		Where("id = ?", id).
		Exec(ctx)

	return err
}

// DeleteByConversationID removes all messages for a conversation
func (r *Repository) DeleteByConversationID(ctx context.Context, conversationID uuid.UUID) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	_, err := r.db.NewDelete().
		Model((*Message)(nil)).
		Where("conversation_id = ?", conversationID).
		Exec(ctx)

	return err
}

// GetMaxSequenceNum returns the highest sequence number for a conversation
func (r *Repository) GetMaxSequenceNum(ctx context.Context, conversationID uuid.UUID) (int, error) {
	if r.db == nil {
		return 0, fmt.Errorf("database not initialized")
	}

	var maxSeq int
	err := r.db.NewSelect().
		Model((*Message)(nil)).
		ColumnExpr("COALESCE(MAX(sequence_num), -1)").
		Where("conversation_id = ?", conversationID).
		Scan(ctx, &maxSeq)

	if err != nil {
		return 0, err
	}
	return maxSeq, nil
}
