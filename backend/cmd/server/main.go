// SPDX-License-Identifier: MIT
// Copyright (c) 2024-2025 Claude Code Native Contributors

// Package main is the entry point for the claude-code-native backend server.
// It uses Uber's fx dependency injection framework to manage application lifecycle.
package main

import (
	"github.com/devnogari/claude-code-native/backend/internal/auth"
	"github.com/devnogari/claude-code-native/backend/internal/claude"
	"github.com/devnogari/claude-code-native/backend/internal/command"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/conversation"
	"github.com/devnogari/claude-code-native/backend/internal/database"
	"github.com/devnogari/claude-code-native/backend/internal/logger"
	"github.com/devnogari/claude-code-native/backend/internal/message"
	"github.com/devnogari/claude-code-native/backend/internal/middleware"
	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/devnogari/claude-code-native/backend/internal/queue"
	"github.com/devnogari/claude-code-native/backend/internal/server"
	"github.com/devnogari/claude-code-native/backend/internal/storage"
	"github.com/devnogari/claude-code-native/backend/internal/user"
	"github.com/devnogari/claude-code-native/backend/internal/ws"
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
		middleware.Module,
		project.Module,
		conversation.Module,
		message.Module,
		storage.Module,
		queue.Module,

		// Real-time modules
		claude.Module,
		ws.Module,

		// Command module
		command.Module,

		// Server (starts on OnStart, stops on OnStop)
		server.Module,
	).Run()
}
