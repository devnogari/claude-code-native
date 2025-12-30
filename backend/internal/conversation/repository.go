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
		Order("created_at DESC").
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
