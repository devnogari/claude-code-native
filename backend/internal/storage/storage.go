package storage

import (
	"context"
	"errors"
)

var (
	ErrNotFound      = errors.New("file not found")
	ErrInvalidPath   = errors.New("invalid storage path")
	ErrStorageFailed = errors.New("storage operation failed")
)

// ImageStorage defines the interface for image storage operations.
// Implementations can store images locally or in cloud storage (S3, GCS, etc.)
type ImageStorage interface {
	// Save stores image data and returns the storage path
	Save(ctx context.Context, data []byte, mediaType string) (path string, err error)

	// Get retrieves image data by storage path
	Get(ctx context.Context, path string) ([]byte, error)

	// Delete removes an image from storage
	Delete(ctx context.Context, path string) error

	// GetURL returns a URL that can be used to access the image
	GetURL(path string) string
}
