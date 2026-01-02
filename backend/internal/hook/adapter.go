package hook

import (
	"context"

	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/gofrs/uuid/v5"
)

// ProjectRepoAdapter adapts project.Repository to hook.ProjectRepository interface
type ProjectRepoAdapter struct {
	repo *project.Repository
}

// NewProjectRepoAdapter creates a new adapter
func NewProjectRepoAdapter(repo *project.Repository) *ProjectRepoAdapter {
	return &ProjectRepoAdapter{repo: repo}
}

// FindByPathAnyUser implements hook.ProjectRepository
func (a *ProjectRepoAdapter) FindByPathAnyUser(ctx context.Context, path string) (*Project, error) {
	p, err := a.repo.FindByPathAnyUser(ctx, path)
	if err != nil {
		return nil, err
	}
	return &Project{
		ID:          p.ID,
		UserID:      p.UserID,
		Name:        p.Name,
		Path:        p.Path,
		IsCompleted: p.IsCompleted,
	}, nil
}

// MarkCompleted implements hook.ProjectRepository
func (a *ProjectRepoAdapter) MarkCompleted(ctx context.Context, id uuid.UUID, completed bool) error {
	return a.repo.MarkCompleted(ctx, id, completed)
}
