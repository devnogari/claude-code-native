package server

import (
	"github.com/gofiber/fiber/v2"
)

func (s *Server) setupRoutes() {
	// Health check
	s.app.Get("/health", s.healthCheck)

	// API v1
	api := s.app.Group("/api/v1")

	// Auth routes (public)
	authGroup := api.Group("/auth")
	authGroup.Post("/login", s.authHandler.Login)
	authGroup.Post("/register", s.authHandler.Register)

	// Protected routes
	protected := api.Group("")
	protected.Use(s.authMiddleware.Authenticate)

	// Project routes
	protected.Post("/projects", s.projectHandler.Create)
	protected.Get("/projects", s.projectHandler.List)
	protected.Get("/projects/:id", s.projectHandler.Get)
	protected.Put("/projects/:id", s.projectHandler.Update)
	protected.Delete("/projects/:id", s.projectHandler.Delete)

	// Conversation routes
	protected.Post("/projects/:projectId/conversations", s.convHandler.Create)
	protected.Get("/projects/:projectId/conversations", s.convHandler.List)
	protected.Get("/conversations/:id", s.convHandler.Get)
	protected.Put("/conversations/:id", s.convHandler.Update)
	protected.Delete("/conversations/:id", s.convHandler.Delete)
}

func (s *Server) healthCheck(c *fiber.Ctx) error {
	return c.JSON(fiber.Map{
		"status":  "ok",
		"service": "claude-code-native",
	})
}
