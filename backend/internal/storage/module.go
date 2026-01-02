package storage

import (
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"go.uber.org/fx"
)

// ProvideImageStorage creates an ImageStorage instance using configuration from main config.
func ProvideImageStorage(cfg *config.Config) (ImageStorage, error) {
	return NewLocalFileStorage(cfg.Storage.BasePath, cfg.Storage.BaseURL)
}

var Module = fx.Module("storage",
	fx.Provide(ProvideImageStorage),
)
