package middleware

import "go.uber.org/fx"

// Module provides middleware dependencies for fx
var Module = fx.Module("middleware",
	fx.Provide(NewAuthMiddleware),
)
