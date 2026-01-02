package storage

import (
	"go.uber.org/fx"
)

// StorageConfig holds configuration for the storage module.
type StorageConfig struct {
	BasePath string // Local file storage path (e.g., "./uploads")
	BaseURL  string // URL prefix for serving files (e.g., "/api/v1/images")
}

// NewStorageConfig creates a default storage configuration.
func NewStorageConfig() StorageConfig {
	return StorageConfig{
		BasePath: "./uploads",
		BaseURL:  "/api/v1/images",
	}
}

// ProvideImageStorage creates an ImageStorage instance.
func ProvideImageStorage(config StorageConfig) (ImageStorage, error) {
	return NewLocalFileStorage(config.BasePath, config.BaseURL)
}

var Module = fx.Module("storage",
	fx.Provide(NewStorageConfig),
	fx.Provide(ProvideImageStorage),
)
