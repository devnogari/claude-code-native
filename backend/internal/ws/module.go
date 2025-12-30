package ws

import (
	"context"

	"github.com/devnogari/claude-code-native/backend/internal/claude"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/conversation"
	"github.com/devnogari/claude-code-native/backend/internal/message"
	"github.com/devnogari/claude-code-native/backend/internal/project"
	"go.uber.org/fx"
	"go.uber.org/zap"
)

// Module provides WebSocket hub and handler dependencies
var Module = fx.Module("ws",
	fx.Provide(NewHub),
	fx.Provide(func(
		hub *Hub,
		config *config.Config,
		logger *zap.Logger,
		claudeMgr *claude.Manager,
		convRepo *conversation.Repository,
		projRepo *project.Repository,
		msgRepo *message.Repository,
	) *Handler {
		return NewHandler(hub, config, logger, claudeMgr, convRepo, projRepo, msgRepo)
	}),
	fx.Invoke(func(lc fx.Lifecycle, hub *Hub) {
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
	}),
)
