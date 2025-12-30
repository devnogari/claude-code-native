package user

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

func (r *Repository) Create(ctx context.Context, user *User) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	if user.ID == uuid.Nil {
		id, err := uuid.NewV7()
		if err != nil {
			return fmt.Errorf("failed to generate UUID: %w", err)
		}
		user.ID = id
	}

	_, err := r.db.NewInsert().Model(user).Exec(ctx)
	return err
}

func (r *Repository) FindByID(ctx context.Context, id uuid.UUID) (*User, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	user := new(User)
	err := r.db.NewSelect().
		Model(user).
		Where("id = ?", id).
		Scan(ctx)

	if err != nil {
		return nil, err
	}
	return user, nil
}

func (r *Repository) FindByUsername(ctx context.Context, username string) (*User, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	user := new(User)
	err := r.db.NewSelect().
		Model(user).
		Where("username = ?", username).
		Scan(ctx)

	if err != nil {
		return nil, err
	}
	return user, nil
}

func (r *Repository) UpdateLastLogin(ctx context.Context, id uuid.UUID) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	_, err := r.db.NewUpdate().
		Model((*User)(nil)).
		Set("last_login_at = NOW()").
		Set("updated_at = NOW()").
		Where("id = ?", id).
		Exec(ctx)

	return err
}
