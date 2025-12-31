package server

import (
	"context"
	"fmt"
	"time"

	"github.com/devnogari/claude-code-native/backend/internal/auth"
	"github.com/devnogari/claude-code-native/backend/internal/claude"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/conversation"
	"github.com/devnogari/claude-code-native/backend/internal/message"
	"github.com/devnogari/claude-code-native/backend/internal/middleware"
	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/devnogari/claude-code-native/backend/internal/user"
	"github.com/devnogari/claude-code-native/backend/internal/ws"
	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/cors"
	"github.com/gofiber/fiber/v2/middleware/recover"
	"go.uber.org/fx"
	"go.uber.org/zap"
)

type Server struct {
	app            *fiber.App
	config         *config.Config
	logger         *zap.Logger
	authService    *auth.Service
	authMiddleware *middleware.AuthMiddleware
	userRepo       *user.Repository
	projectRepo    *project.Repository
	convRepo       *conversation.Repository
	claudeMgr      *claude.Manager

	// Handlers
	authHandler         *auth.Handler
	projectHandler      *project.Handler
	convHandler         *conversation.Handler
	msgHandler          *message.Handler
	wsHandler           *ws.Handler
	historyHandler      *claude.HistoryHandler
	historyWatchHandler *claude.HistoryWatchHandler
	syncHandler         *claude.SyncHandler
}

type ServerParams struct {
	fx.In
	Config              *config.Config
	Logger              *zap.Logger
	AuthService         *auth.Service
	AuthMiddleware      *middleware.AuthMiddleware
	UserRepo            *user.Repository
	ProjectRepo         *project.Repository
	ConvRepo            *conversation.Repository
	MsgRepo             *message.Repository
	WSHandler           *ws.Handler
	SyncHandler         *claude.SyncHandler
	HistoryHandler      *claude.HistoryHandler
	HistoryWatchHandler *claude.HistoryWatchHandler
	ClaudeMgr           *claude.Manager
}

func New(p ServerParams) *Server {
	app := fiber.New(fiber.Config{
		AppName:      "Claude Code Native",
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  120 * time.Second,
		ErrorHandler: customErrorHandler(p.Logger),
	})

	// Middleware
	app.Use(recover.New())
	app.Use(requestLogger(p.Logger))
	app.Use(cors.New(cors.Config{
		AllowOrigins: "*",
		AllowMethods: "GET,POST,PUT,DELETE,OPTIONS",
		AllowHeaders: "Origin,Content-Type,Accept,Authorization",
	}))

	// Create handlers with proper dependencies
	authHandler := auth.NewHandler(p.AuthService, p.UserRepo)
	projectHandler := project.NewHandler(p.ProjectRepo)
	convHandler := conversation.NewHandler(p.ConvRepo, p.ProjectRepo)
	msgHandler := message.NewHandler(p.MsgRepo)

	s := &Server{
		app:                 app,
		config:              p.Config,
		logger:              p.Logger,
		authService:         p.AuthService,
		authMiddleware:      p.AuthMiddleware,
		userRepo:            p.UserRepo,
		projectRepo:         p.ProjectRepo,
		convRepo:            p.ConvRepo,
		claudeMgr:           p.ClaudeMgr,
		authHandler:         authHandler,
		projectHandler:      projectHandler,
		convHandler:         convHandler,
		msgHandler:          msgHandler,
		wsHandler:           p.WSHandler,
		historyHandler:      p.HistoryHandler,
		historyWatchHandler: p.HistoryWatchHandler,
		syncHandler:         p.SyncHandler,
	}

	s.setupRoutes()

	return s
}

func customErrorHandler(logger *zap.Logger) fiber.ErrorHandler {
	return func(c *fiber.Ctx, err error) error {
		code := fiber.StatusInternalServerError
		if e, ok := err.(*fiber.Error); ok {
			code = e.Code
		}
		logger.Error("HTTP error", zap.Error(err), zap.Int("status", code))
		return c.Status(code).JSON(fiber.Map{"error": err.Error()})
	}
}

func requestLogger(logger *zap.Logger) fiber.Handler {
	return func(c *fiber.Ctx) error {
		start := time.Now()
		err := c.Next()
		logger.Info("request",
			zap.String("method", c.Method()),
			zap.String("path", c.Path()),
			zap.Int("status", c.Response().StatusCode()),
			zap.Duration("latency", time.Since(start)),
		)
		return err
	}
}

func (s *Server) App() *fiber.App {
	return s.app
}

func (s *Server) Start() error {
	addr := fmt.Sprintf(":%s", s.config.Server.Port)
	s.logger.Info("Starting server", zap.String("addr", addr))
	return s.app.Listen(addr)
}

func (s *Server) Shutdown(ctx context.Context) error {
	s.logger.Info("Shutting down server")
	return s.app.ShutdownWithContext(ctx)
}
