package hook

import "go.uber.org/fx"

var Module = fx.Module("hook",
	fx.Provide(NewHandler),
)
