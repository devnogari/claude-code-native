package command

import "go.uber.org/fx"

// Module provides command-related dependencies.
var Module = fx.Module("command",
	fx.Provide(NewService),
	fx.Provide(NewParser),
	fx.Provide(NewHandler),
)
