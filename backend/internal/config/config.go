package config

import (
	"os"
	"strconv"

	"github.com/joho/godotenv"
)

// Config holds all application configuration
type Config struct {
	Server     ServerConfig
	Database   DatabaseConfig
	Auth       AuthConfig
	Claude     ClaudeConfig
	Storage    StorageConfig
	HookAPIKey string // API key for hook endpoints (optional, empty allows all)
}

// StorageConfig holds file storage configuration
type StorageConfig struct {
	BasePath string // Local file storage path (e.g., "./uploads")
	BaseURL  string // URL prefix for serving files (e.g., "/api/v1/images")
}

// ServerConfig holds HTTP server configuration
type ServerConfig struct {
	Port      string
	LogLevel  string
	LogFormat string // "console" (default) or "json"
}

// DatabaseConfig holds database connection configuration
type DatabaseConfig struct {
	URL string
}

// AuthConfig holds authentication configuration
type AuthConfig struct {
	JWTSecret     string
	JWTExpiryDays int
}

// ClaudeConfig holds Claude-specific configuration
type ClaudeConfig struct {
	ProjectsPath string
}

// New creates a new Config instance by loading from environment variables
func New() (*Config, error) {
	// Load .env file if exists (ignore error if not found)
	_ = godotenv.Load()

	cfg := &Config{
		Server: ServerConfig{
			Port:      getEnv("PORT", "8080"),
			LogLevel:  getEnv("LOG_LEVEL", "info"),
			LogFormat: getEnv("LOG_FORMAT", "console"), // "console" or "json"
		},
		Database: DatabaseConfig{
			URL: getEnv("DATABASE_URL", ""),
		},
		Auth: AuthConfig{
			JWTSecret:     getEnv("JWT_SECRET", ""),
			JWTExpiryDays: getEnvInt("JWT_EXPIRY_DAYS", 7),
		},
		Claude: ClaudeConfig{
			ProjectsPath: getEnv("CLAUDE_PROJECTS_PATH", "~/.claude"),
		},
		Storage: StorageConfig{
			BasePath: getEnv("STORAGE_PATH", "./uploads"),
			BaseURL:  getEnv("STORAGE_URL", "/api/v1/images"),
		},
		HookAPIKey: getEnv("HOOK_API_KEY", ""), // Empty allows all requests
	}

	return cfg, nil
}

// getEnv retrieves an environment variable with a default fallback
func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

// getEnvInt retrieves an environment variable as an integer with a default fallback
func getEnvInt(key string, defaultValue int) int {
	if value := os.Getenv(key); value != "" {
		if result, err := strconv.Atoi(value); err == nil {
			return result
		}
	}
	return defaultValue
}
