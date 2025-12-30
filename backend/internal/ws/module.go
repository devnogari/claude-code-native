package ws

import (
	"context"

	"go.uber.org/fx"
)

// Module provides WebSocket hub dependencies
var Module = fx.Module("ws",
	fx.Provide(NewHub),
	fx.Invoke(startHub),
)

// startHub starts the Hub goroutine when the application starts
func startHub(lc fx.Lifecycle, hub *Hub) {
	lc.Append(fx.Hook{
		OnStart: func(ctx context.Context) error {
			go hub.Run()
			return nil
		},
		OnStop: func(ctx context.Context) error {
			// Hub.Run() runs forever; the goroutine will be terminated
			// when the process exits. If needed, we could add a stop channel.
			return nil
		},
	})
}
