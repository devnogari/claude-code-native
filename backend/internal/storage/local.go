package storage

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/gofrs/uuid/v5"
)

// LocalFileStorage implements ImageStorage using the local filesystem.
// Images are stored in a directory structure: basePath/queued/{conversationId}/{imageId}.{ext}
type LocalFileStorage struct {
	basePath string // Directory to store files (e.g., "./uploads")
	baseURL  string // URL prefix for serving files (e.g., "/api/v1/images")
}

// NewLocalFileStorage creates a new LocalFileStorage instance.
func NewLocalFileStorage(basePath, baseURL string) (*LocalFileStorage, error) {
	// Ensure base directory exists
	if err := os.MkdirAll(basePath, 0755); err != nil {
		return nil, fmt.Errorf("failed to create storage directory: %w", err)
	}

	return &LocalFileStorage{
		basePath: basePath,
		baseURL:  strings.TrimSuffix(baseURL, "/"),
	}, nil
}

// Save stores image data and returns the storage path.
// The path format is: queued/{uuid}.{ext}
func (s *LocalFileStorage) Save(ctx context.Context, data []byte, mediaType string) (string, error) {
	if len(data) == 0 {
		return "", fmt.Errorf("%w: empty data", ErrStorageFailed)
	}

	ext := extensionFromMediaType(mediaType)
	if ext == "" {
		return "", fmt.Errorf("%w: unsupported media type: %s", ErrStorageFailed, mediaType)
	}

	// Generate unique ID for the file
	id, err := uuid.NewV7()
	if err != nil {
		return "", fmt.Errorf("%w: failed to generate ID: %v", ErrStorageFailed, err)
	}

	// Create subdirectory for queued images
	subDir := filepath.Join(s.basePath, "queued")
	if err := os.MkdirAll(subDir, 0755); err != nil {
		return "", fmt.Errorf("%w: failed to create subdirectory: %v", ErrStorageFailed, err)
	}

	// Create file path
	fileName := fmt.Sprintf("%s.%s", id.String(), ext)
	filePath := filepath.Join(subDir, fileName)

	// Write file
	if err := os.WriteFile(filePath, data, 0644); err != nil {
		return "", fmt.Errorf("%w: failed to write file: %v", ErrStorageFailed, err)
	}

	// Return relative path for storage in DB
	return filepath.Join("queued", fileName), nil
}

// Get retrieves image data by storage path.
func (s *LocalFileStorage) Get(ctx context.Context, path string) ([]byte, error) {
	if path == "" {
		return nil, ErrInvalidPath
	}

	// Prevent directory traversal
	cleanPath := filepath.Clean(path)
	if strings.Contains(cleanPath, "..") {
		return nil, ErrInvalidPath
	}

	fullPath := filepath.Join(s.basePath, cleanPath)

	data, err := os.ReadFile(fullPath)
	if os.IsNotExist(err) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("%w: failed to read file: %v", ErrStorageFailed, err)
	}

	return data, nil
}

// Delete removes an image from storage.
func (s *LocalFileStorage) Delete(ctx context.Context, path string) error {
	if path == "" {
		return ErrInvalidPath
	}

	// Prevent directory traversal
	cleanPath := filepath.Clean(path)
	if strings.Contains(cleanPath, "..") {
		return ErrInvalidPath
	}

	fullPath := filepath.Join(s.basePath, cleanPath)

	err := os.Remove(fullPath)
	if os.IsNotExist(err) {
		// Already deleted, consider success
		return nil
	}
	if err != nil {
		return fmt.Errorf("%w: failed to delete file: %v", ErrStorageFailed, err)
	}

	return nil
}

// GetURL returns a URL that can be used to access the image.
func (s *LocalFileStorage) GetURL(path string) string {
	if path == "" {
		return ""
	}
	// Convert file path to URL path (use forward slashes)
	urlPath := strings.ReplaceAll(path, string(filepath.Separator), "/")
	return fmt.Sprintf("%s/%s", s.baseURL, urlPath)
}

// extensionFromMediaType returns the file extension for a given media type.
func extensionFromMediaType(mediaType string) string {
	switch strings.ToLower(mediaType) {
	case "image/png":
		return "png"
	case "image/jpeg", "image/jpg":
		return "jpg"
	case "image/gif":
		return "gif"
	case "image/webp":
		return "webp"
	case "image/svg+xml":
		return "svg"
	default:
		return ""
	}
}

// mediaTypeFromExtension returns the media type for a given file extension.
func mediaTypeFromExtension(ext string) string {
	switch strings.ToLower(strings.TrimPrefix(ext, ".")) {
	case "png":
		return "image/png"
	case "jpg", "jpeg":
		return "image/jpeg"
	case "gif":
		return "image/gif"
	case "webp":
		return "image/webp"
	case "svg":
		return "image/svg+xml"
	default:
		return "application/octet-stream"
	}
}
