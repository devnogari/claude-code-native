package claude

import (
	"context"

	"go.uber.org/fx"
)

// Module provides Claude CLI process management dependencies
var Module = fx.Module("claude",
	fx.Provide(NewManager),
	fx.Provide(NewHistoryHandler),
	fx.Provide(NewSyncService),
	fx.Provide(NewSyncHandler),
	fx.Invoke(registerShutdownHook),
)

// registerShutdownHook registers a lifecycle hook to stop all processes on shutdown
func registerShutdownHook(lc fx.Lifecycle, manager *Manager) {
	lc.Append(fx.Hook{
		OnStop: func(ctx context.Context) error {
			manager.StopAll()
			return nil
		},
	})
}
