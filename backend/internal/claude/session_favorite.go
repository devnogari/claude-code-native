package claude

import (
	"context"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

// SessionFavorite represents a favorite session entry
type SessionFavorite struct {
	bun.BaseModel `bun:"table:session_favorites,alias:sf"`

	ID          uuid.UUID `bun:"id,pk,type:uuid,default:uuidv7()"`
	UserID      uuid.UUID `bun:"user_id,notnull,type:uuid"`
	SessionID   string    `bun:"session_id,notnull"`
	ProjectPath string    `bun:"project_path,notnull"`
	CreatedAt   time.Time `bun:"created_at,default:now()"`
}

// SessionFavoriteRepository handles session favorite database operations
type SessionFavoriteRepository struct {
	db *bun.DB
}

// NewSessionFavoriteRepository creates a new session favorite repository
func NewSessionFavoriteRepository(db *bun.DB) *SessionFavoriteRepository {
	return &SessionFavoriteRepository{db: db}
}

// IsFavorite checks if a session is favorited by the user
func (r *SessionFavoriteRepository) IsFavorite(ctx context.Context, userID uuid.UUID, sessionID, projectPath string) (bool, error) {
	exists, err := r.db.NewSelect().
		Model((*SessionFavorite)(nil)).
		Where("user_id = ? AND session_id = ? AND project_path = ?", userID, sessionID, projectPath).
		Exists(ctx)
	return exists, err
}

// GetFavorites returns all favorite session IDs for a user and project
func (r *SessionFavoriteRepository) GetFavorites(ctx context.Context, userID uuid.UUID, projectPath string) (map[string]bool, error) {
	var favorites []SessionFavorite
	err := r.db.NewSelect().
		Model(&favorites).
		Where("user_id = ? AND project_path = ?", userID, projectPath).
		Scan(ctx)
	if err != nil {
		return nil, err
	}

	result := make(map[string]bool)
	for _, f := range favorites {
		result[f.SessionID] = true
	}
	return result, nil
}

// GetAllFavorites returns all favorite session IDs for a user (across all projects)
func (r *SessionFavoriteRepository) GetAllFavorites(ctx context.Context, userID uuid.UUID) (map[string]bool, error) {
	var favorites []SessionFavorite
	err := r.db.NewSelect().
		Model(&favorites).
		Where("user_id = ?", userID).
		Scan(ctx)
	if err != nil {
		return nil, err
	}

	result := make(map[string]bool)
	for _, f := range favorites {
		result[f.SessionID] = true
	}
	return result, nil
}

// ToggleFavorite adds or removes a session from favorites
func (r *SessionFavoriteRepository) ToggleFavorite(ctx context.Context, userID uuid.UUID, sessionID, projectPath string) (bool, error) {
	// Check if already favorited
	exists, err := r.IsFavorite(ctx, userID, sessionID, projectPath)
	if err != nil {
		return false, err
	}

	if exists {
		// Remove from favorites
		_, err := r.db.NewDelete().
			Model((*SessionFavorite)(nil)).
			Where("user_id = ? AND session_id = ? AND project_path = ?", userID, sessionID, projectPath).
			Exec(ctx)
		return false, err
	}

	// Add to favorites
	fav := &SessionFavorite{
		UserID:      userID,
		SessionID:   sessionID,
		ProjectPath: projectPath,
	}
	_, err = r.db.NewInsert().Model(fav).Exec(ctx)
	return true, err
}

// SetFavorite explicitly sets the favorite status
func (r *SessionFavoriteRepository) SetFavorite(ctx context.Context, userID uuid.UUID, sessionID, projectPath string, isFavorite bool) error {
	if isFavorite {
		// Add to favorites (ignore conflict)
		fav := &SessionFavorite{
			UserID:      userID,
			SessionID:   sessionID,
			ProjectPath: projectPath,
		}
		_, err := r.db.NewInsert().
			Model(fav).
			On("CONFLICT (user_id, session_id, project_path) DO NOTHING").
			Exec(ctx)
		return err
	}

	// Remove from favorites
	_, err := r.db.NewDelete().
		Model((*SessionFavorite)(nil)).
		Where("user_id = ? AND session_id = ? AND project_path = ?", userID, sessionID, projectPath).
		Exec(ctx)
	return err
}

// Delete removes a favorite entry
func (r *SessionFavoriteRepository) Delete(ctx context.Context, userID uuid.UUID, sessionID, projectPath string) error {
	_, err := r.db.NewDelete().
		Model((*SessionFavorite)(nil)).
		Where("user_id = ? AND session_id = ? AND project_path = ?", userID, sessionID, projectPath).
		Exec(ctx)
	return err
}
