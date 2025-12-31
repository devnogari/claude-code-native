package claude

import (
	"context"

	"go.uber.org/fx"
	"go.uber.org/zap"
)

// Module provides Claude CLI process management dependencies
var Module = fx.Module("claude",
	fx.Provide(NewManager),
	fx.Provide(provideHistoryCache),
	fx.Provide(NewHistoryHandlerWithCache),
	fx.Provide(NewSyncService),
	fx.Provide(NewSyncHandler),
	fx.Invoke(registerShutdownHook),
)

// provideHistoryCache creates a new history cache for fx
func provideHistoryCache(logger *zap.Logger) (*HistoryCache, error) {
	return NewHistoryCache(logger)
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
