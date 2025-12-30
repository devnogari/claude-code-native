package config

import (
	"go.uber.org/fx"
)

// Module provides config dependencies for fx dependency injection
var Module = fx.Module("config",
	fx.Provide(New),
)
