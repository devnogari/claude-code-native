package conversation

import (
	"context"
	"fmt"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

type Repository struct {
	db *bun.DB
}

func NewRepository(db *bun.DB) *Repository {
	return &Repository{db: db}
}

func (r *Repository) Create(ctx context.Context, conversation *Conversation) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	if err := conversation.Validate(); err != nil {
		return fmt.Errorf("validation failed: %w", err)
	}

	if conversation.ID == uuid.Nil {
		id, err := uuid.NewV7()
		if err != nil {
			return fmt.Errorf("failed to generate UUID: %w", err)
		}
		conversation.ID = id
	}

	_, err := r.db.NewInsert().Model(conversation).Exec(ctx)
	return err
}

func (r *Repository) FindByID(ctx context.Context, id uuid.UUID) (*Conversation, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	conversation := new(Conversation)
	err := r.db.NewSelect().
		Model(conversation).
		Where("id = ?", id).
		Scan(ctx)

	if err != nil {
		return nil, err
	}
	return conversation, nil
}

func (r *Repository) FindByProjectID(ctx context.Context, projectID uuid.UUID) ([]*Conversation, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	var conversations []*Conversation
	err := r.db.NewSelect().
		Model(&conversations).
		Where("project_id = ?", projectID).
		Order("is_favorite DESC", "updated_at DESC").
		Scan(ctx)

	if err != nil {
		return nil, err
	}
	return conversations, nil
}

func (r *Repository) Update(ctx context.Context, conversation *Conversation) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	if err := conversation.Validate(); err != nil {
		return fmt.Errorf("validation failed: %w", err)
	}

	_, err := r.db.NewUpdate().
		Model(conversation).
		WherePK().
		Set("updated_at = NOW()").
		Exec(ctx)

	return err
}

// UpdateWithTimestamp updates a conversation preserving its UpdatedAt value
// Used for sync operations where the timestamp should reflect the source file's modification time
func (r *Repository) UpdateWithTimestamp(ctx context.Context, conversation *Conversation) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	if err := conversation.Validate(); err != nil {
		return fmt.Errorf("validation failed: %w", err)
	}

	// Use raw table name to avoid any ORM hooks or automatic timestamp handling
	result, err := r.db.NewUpdate().
		TableExpr("conversations").
		Set("message_count = ?", conversation.MessageCount).
		Set("updated_at = ?", conversation.UpdatedAt).
		Where("id = ?", conversation.ID).
		Exec(ctx)

	if err != nil {
		return err
	}

	rowsAffected, _ := result.RowsAffected()
	if rowsAffected == 0 {
		return fmt.Errorf("no rows affected for conversation id=%s", conversation.ID)
	}

	return nil
}

func (r *Repository) Delete(ctx context.Context, id uuid.UUID) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	_, err := r.db.NewDelete().
		Model((*Conversation)(nil)).
		Where("id = ?", id).
		Exec(ctx)

	return err
}

func (r *Repository) FindByClaudeSession(ctx context.Context, projectID uuid.UUID, sessionID string) (*Conversation, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	conversation := new(Conversation)
	err := r.db.NewSelect().
		Model(conversation).
		Where("project_id = ? AND claude_session = ?", projectID, sessionID).
		Scan(ctx)

	if err != nil {
		return nil, err
	}
	return conversation, nil
}

func (r *Repository) IncrementMessageCount(ctx context.Context, id uuid.UUID) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	_, err := r.db.NewUpdate().
		Model((*Conversation)(nil)).
		Set("message_count = message_count + 1").
		Set("updated_at = NOW()").
		Where("id = ?", id).
		Exec(ctx)

	return err
}

func (r *Repository) ToggleFavorite(ctx context.Context, id uuid.UUID) (*Conversation, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	conversation := new(Conversation)
	_, err := r.db.NewUpdate().
		Model(conversation).
		Set("is_favorite = NOT is_favorite").
		Set("updated_at = NOW()").
		Where("id = ?", id).
		Returning("*").
		Exec(ctx)

	if err != nil {
		return nil, err
	}
	return conversation, nil
}

func (r *Repository) SetFavorite(ctx context.Context, id uuid.UUID, isFavorite bool) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	_, err := r.db.NewUpdate().
		Model((*Conversation)(nil)).
		Set("is_favorite = ?", isFavorite).
		Set("updated_at = NOW()").
		Where("id = ?", id).
		Exec(ctx)

	return err
}
