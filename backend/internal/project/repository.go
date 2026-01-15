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

	// If UpdatedAt is provided (from sync), use raw SQL to preserve it
	// Otherwise use ORM defaults
	if !project.UpdatedAt.IsZero() {
		_, err := r.db.NewRaw(`
			INSERT INTO projects (id, user_id, name, path, claude_id, last_accessed, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
		`,
			project.ID,
			project.UserID,
			project.Name,
			project.Path,
			project.ClaudeID,
			project.LastAccessed,
			project.UpdatedAt, // Use UpdatedAt as CreatedAt for sync
			project.UpdatedAt,
		).Exec(ctx)
		if err != nil {
			return fmt.Errorf("create project with timestamp: %w", err)
		}
		return nil
	}

	_, err := r.db.NewInsert().Model(project).Exec(ctx)
	if err != nil {
		return fmt.Errorf("create project: %w", err)
	}
	return nil
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
		return nil, fmt.Errorf("find project by id: %w", err)
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
		return nil, fmt.Errorf("find projects by user id: %w", err)
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

	if err != nil {
		return fmt.Errorf("update project: %w", err)
	}
	return nil
}

func (r *Repository) Delete(ctx context.Context, id uuid.UUID) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	_, err := r.db.NewDelete().
		Model((*Project)(nil)).
		Where("id = ?", id).
		Exec(ctx)

	if err != nil {
		return fmt.Errorf("delete project: %w", err)
	}
	return nil
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
		return nil, fmt.Errorf("find project by path: %w", err)
	}
	return project, nil
}

// UpdateWithTimestamp updates a project preserving its UpdatedAt value
// Used for sync operations where the timestamp should reflect the source file's modification time
func (r *Repository) UpdateWithTimestamp(ctx context.Context, project *Project) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	if err := project.Validate(); err != nil {
		return fmt.Errorf("validation failed: %w", err)
	}

	// Use raw table name to avoid any ORM hooks or automatic timestamp handling
	result, err := r.db.NewUpdate().
		TableExpr("projects").
		Set("name = ?", project.Name).
		Set("claude_id = ?", project.ClaudeID).
		Set("last_accessed = ?", project.LastAccessed).
		Set("updated_at = ?", project.UpdatedAt).
		Where("id = ?", project.ID).
		Exec(ctx)

	if err != nil {
		return fmt.Errorf("update project with timestamp: %w", err)
	}

	rowsAffected, _ := result.RowsAffected()
	if rowsAffected == 0 {
		return fmt.Errorf("no rows affected for project id=%s", project.ID)
	}

	return nil
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

	if err != nil {
		return fmt.Errorf("update last accessed: %w", err)
	}
	return nil
}

// FindByPathAnyUser finds a project by path regardless of user
// Used for hook operations where we only have the path
func (r *Repository) FindByPathAnyUser(ctx context.Context, path string) (*Project, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	project := new(Project)
	err := r.db.NewSelect().
		Model(project).
		Where("path = ?", path).
		Scan(ctx)

	if err != nil {
		return nil, fmt.Errorf("find project by path any user: %w", err)
	}
	return project, nil
}

// MarkCompleted updates the is_completed status of a project
func (r *Repository) MarkCompleted(ctx context.Context, id uuid.UUID, completed bool) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	_, err := r.db.NewUpdate().
		Model((*Project)(nil)).
		Set("is_completed = ?", completed).
		Set("updated_at = NOW()").
		Where("id = ?", id).
		Exec(ctx)

	if err != nil {
		return fmt.Errorf("mark completed: %w", err)
	}
	return nil
}
