package claude

import (
	"context"

	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/uptrace/bun"
	"go.uber.org/fx"
	"go.uber.org/zap"
)

// Module provides Claude CLI process management dependencies
var Module = fx.Module("claude",
	fx.Provide(NewManager),
	fx.Provide(provideHistoryCache),
	fx.Provide(NewSessionFavoriteRepository),
	fx.Provide(NewHistoryHandler),
	fx.Provide(NewHistoryWatchHandler),
	fx.Provide(NewSyncService),
	fx.Provide(NewSyncHandler),
	fx.Invoke(registerShutdownHook),
)

// HistoryHandlerParams defines dependencies for the history handler
type HistoryHandlerParams struct {
	fx.In

	Cache       *HistoryCache
	FavRepo     *SessionFavoriteRepository
	ProjectRepo *project.Repository
	DB          *bun.DB
	Logger      *zap.Logger
}

// provideHistoryCache creates a new history cache for fx
func provideHistoryCache(cfg *config.Config, logger *zap.Logger) (*HistoryCache, error) {
	return NewHistoryCache(logger, cfg.Claude.ProjectsPath)
}

// registerShutdownHook registers a lifecycle hook to stop all processes on shutdown
func registerShutdownHook(lc fx.Lifecycle, manager *Manager, cache *HistoryCache) {
	lc.Append(fx.Hook{
		OnStop: func(ctx context.Context) error {
			manager.StopAll()
			cache.Close()
			return nil
		},
	})
}
