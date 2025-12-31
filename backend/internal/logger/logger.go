// backend/internal/logger/logger.go
package logger

import (
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

func New(cfg *config.Config) (*zap.Logger, error) {
	var level zapcore.Level
	if err := level.UnmarshalText([]byte(cfg.Server.LogLevel)); err != nil {
		level = zapcore.InfoLevel
	}

	// Use console encoding unless LOG_FORMAT=json is explicitly set
	// Default to human-readable console format for development
	logFormat := cfg.Server.LogFormat
	useConsole := logFormat != "json"

	encoding := "json"
	var encoderConfig zapcore.EncoderConfig
	if useConsole {
		encoding = "console"
		encoderConfig = zap.NewDevelopmentEncoderConfig()
		encoderConfig.EncodeLevel = zapcore.CapitalColorLevelEncoder
		encoderConfig.EncodeTime = zapcore.TimeEncoderOfLayout("15:04:05")
	} else {
		encoderConfig = zap.NewProductionEncoderConfig()
	}

	zapConfig := zap.Config{
		Level:            zap.NewAtomicLevelAt(level),
		Development:      useConsole,
		Encoding:         encoding,
		EncoderConfig:    encoderConfig,
		OutputPaths:      []string{"stdout"},
		ErrorOutputPaths: []string{"stderr"},
	}

	return zapConfig.Build()
}

// NewSugar provides a sugared logger for convenience
func NewSugar(logger *zap.Logger) *zap.SugaredLogger {
	return logger.Sugar()
}
