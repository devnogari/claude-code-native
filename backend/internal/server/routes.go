package server

import (
	"github.com/devnogari/claude-code-native/backend/internal/auth"
	"github.com/gofiber/fiber/v2"
)

func (s *Server) setupRoutes() {
	// Health check
	s.app.Get("/health", s.healthCheck)

	// API v1
	api := s.app.Group("/api/v1")

	// Auth routes (public)
	authHandler := auth.NewHandler(s.auth, s.userRepo)
	authGroup := api.Group("/auth")
	authGroup.Post("/login", authHandler.Login)
	authGroup.Post("/register", authHandler.Register)

	// Protected routes (to be added with middleware)
	// api.Use(s.authMiddleware)
	// api.Get("/projects", s.handleListProjects)
}

func (s *Server) healthCheck(c *fiber.Ctx) error {
	return c.JSON(fiber.Map{
		"status":  "ok",
		"service": "claude-code-native",
	})
}
