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
	authGroup.Post("/login", s.handleLogin)
	authGroup.Post("/register", s.handleRegister)

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

func (s *Server) handleLogin(c *fiber.Ctx) error {
	// TODO: Implement in Phase 2
	return c.Status(501).JSON(fiber.Map{
		"error": "not implemented",
	})
}

func (s *Server) handleRegister(c *fiber.Ctx) error {
	// TODO: Implement in Phase 2
	return c.Status(501).JSON(fiber.Map{
		"error": "not implemented",
	})
}
