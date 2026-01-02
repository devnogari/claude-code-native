package queue

import (
	"context"

	"github.com/devnogari/claude-code-native/backend/internal/conversation"
	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/gofrs/uuid/v5"
	"go.uber.org/fx"
	"go.uber.org/zap"
)

// conversationFinderAdapter adapts conversation.Repository to ConversationFinder
type conversationFinderAdapter struct {
	repo *conversation.Repository
}

func (a *conversationFinderAdapter) FindConversationByID(ctx context.Context, id uuid.UUID) (*ConversationInfo, error) {
	conv, err := a.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	return &ConversationInfo{
		ID:        conv.ID,
		ProjectID: conv.ProjectID,
	}, nil
}

// projectFinderAdapter adapts project.Repository to ProjectFinder
type projectFinderAdapter struct {
	repo *project.Repository
}

func (a *projectFinderAdapter) FindProjectByID(ctx context.Context, id uuid.UUID) (*ProjectInfo, error) {
	proj, err := a.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	return &ProjectInfo{
		ID:     proj.ID,
		UserID: proj.UserID,
	}, nil
}

var Module = fx.Module("queue",
	fx.Provide(NewRepository),
	fx.Provide(NewService),
	fx.Provide(func(
		service Service,
		convRepo *conversation.Repository,
		projRepo *project.Repository,
		logger *zap.Logger,
	) *Handler {
		convFinder := &conversationFinderAdapter{repo: convRepo}
		projFinder := &projectFinderAdapter{repo: projRepo}
		return NewHandler(service, convFinder, projFinder, logger)
	}),
)
