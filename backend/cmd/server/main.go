// Package main is the entry point for the claude-code-native backend server.
// It uses Uber's fx dependency injection framework to manage application lifecycle.
package main

import (
	"github.com/devnogari/claude-code-native/backend/internal/auth"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/database"
	"github.com/devnogari/claude-code-native/backend/internal/logger"
	"github.com/devnogari/claude-code-native/backend/internal/server"
	"github.com/devnogari/claude-code-native/backend/internal/user"
	"go.uber.org/fx"
)

func main() {
	fx.New(
		// Core modules
		config.Module,
		logger.Module,
		database.Module,

		// Domain modules
		user.Module,
		auth.Module,

		// Server (starts on OnStart, stops on OnStop)
		server.Module,
	).Run()
}
