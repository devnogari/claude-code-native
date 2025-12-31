package project

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

func (r *Repository) Create(ctx context.Context, project *Project) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	if err := project.Validate(); err != nil {
		return fmt.Errorf("validation failed: %w", err)
	}

	if project.ID == uuid.Nil {
		id, err := uuid.NewV7()
		if err != nil {
			return fmt.Errorf("failed to generate UUID: %w", err)
		}
		project.ID = id
	}

	_, err := r.db.NewInsert().Model(project).Exec(ctx)
	return err
}

func (r *Repository) FindByID(ctx context.Context, id uuid.UUID) (*Project, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	project := new(Project)
	err := r.db.NewSelect().
		Model(project).
		Where("id = ?", id).
		Scan(ctx)

	if err != nil {
		return nil, err
	}
	return project, nil
}

func (r *Repository) FindByUserID(ctx context.Context, userID uuid.UUID) ([]*Project, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	var projects []*Project
	err := r.db.NewSelect().
		Model(&projects).
		Where("user_id = ?", userID).
		Order("last_accessed DESC NULLS LAST", "created_at DESC").
		Scan(ctx)

	if err != nil {
		return nil, err
	}
	return projects, nil
}

func (r *Repository) Update(ctx context.Context, project *Project) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	if err := project.Validate(); err != nil {
		return fmt.Errorf("validation failed: %w", err)
	}

	_, err := r.db.NewUpdate().
		Model(project).
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
		Model((*Project)(nil)).
		Where("id = ?", id).
		Exec(ctx)

	return err
}

func (r *Repository) FindByPath(ctx context.Context, userID uuid.UUID, path string) (*Project, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	project := new(Project)
	err := r.db.NewSelect().
		Model(project).
		Where("user_id = ? AND path = ?", userID, path).
		Scan(ctx)

	if err != nil {
		return nil, err
	}
	return project, nil
}

func (r *Repository) UpdateLastAccessed(ctx context.Context, id uuid.UUID) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	_, err := r.db.NewUpdate().
		Model((*Project)(nil)).
		Set("last_accessed = NOW()").
		Set("updated_at = NOW()").
		Where("id = ?", id).
		Exec(ctx)

	return err
}
