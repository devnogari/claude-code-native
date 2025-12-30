// Package main is the entry point for the claude-code-native backend server.
// It uses Uber's fx dependency injection framework to manage application lifecycle.
package main

import (
	"go.uber.org/fx"
)

func main() {
	fx.New(
		// Modules will be added here as the application grows
		fx.NopLogger, // Disable fx logging for now
	).Run()
}
