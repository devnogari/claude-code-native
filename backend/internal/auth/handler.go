package auth

import (
	"context"

	"github.com/devnogari/claude-code-native/backend/internal/user"
	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
)

// UserRepository defines the interface for user data operations
type UserRepository interface {
	Create(ctx context.Context, u *user.User) error
	FindByUsername(ctx context.Context, username string) (*user.User, error)
	UpdateLastLogin(ctx context.Context, id uuid.UUID) error
}

// Handler handles authentication HTTP requests
type Handler struct {
	service  *Service
	userRepo UserRepository
}

// NewHandler creates a new auth handler
func NewHandler(service *Service, userRepo UserRepository) *Handler {
	return &Handler{
		service:  service,
		userRepo: userRepo,
	}
}

// Register handles user registration
func (h *Handler) Register(c *fiber.Ctx) error {
	var req RegisterRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid request body",
		})
	}

	if err := req.Validate(); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: err.Error(),
		})
	}

	// Check if username already exists
	ctx := c.Context()
	existingUser, err := h.userRepo.FindByUsername(ctx, req.Username)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "internal server error",
		})
	}
	if existingUser != nil {
		return c.Status(fiber.StatusConflict).JSON(ErrorResponse{
			Error: "username already exists",
		})
	}

	// Hash password
	hashedPassword, err := h.service.HashPassword(req.Password)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to process password",
		})
	}

	// Create user
	newUser := &user.User{
		Username:     req.Username,
		PasswordHash: hashedPassword,
	}

	if err := h.userRepo.Create(ctx, newUser); err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to create user",
		})
	}

	// Generate JWT token
	token, expiresAt, err := h.service.GenerateToken(newUser.ID.String(), newUser.Username)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to generate token",
		})
	}

	return c.Status(fiber.StatusCreated).JSON(TokenResponse{
		Token:     token,
		ExpiresAt: expiresAt,
		User: UserResponse{
			ID:       newUser.ID.String(),
			Username: newUser.Username,
		},
	})
}

// Login handles user login
func (h *Handler) Login(c *fiber.Ctx) error {
	var req LoginRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid request body",
		})
	}

	if err := req.Validate(); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: err.Error(),
		})
	}

	// Find user by username
	ctx := c.Context()
	foundUser, err := h.userRepo.FindByUsername(ctx, req.Username)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "internal server error",
		})
	}
	if foundUser == nil {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "invalid credentials",
		})
	}

	// Verify password
	if !h.service.VerifyPassword(req.Password, foundUser.PasswordHash) {
		return c.Status(fiber.StatusUnauthorized).JSON(ErrorResponse{
			Error: "invalid credentials",
		})
	}

	// Update last login timestamp (ignore error, non-critical)
	_ = h.userRepo.UpdateLastLogin(ctx, foundUser.ID)

	// Generate JWT token
	token, expiresAt, err := h.service.GenerateToken(foundUser.ID.String(), foundUser.Username)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error: "failed to generate token",
		})
	}

	return c.Status(fiber.StatusOK).JSON(TokenResponse{
		Token:     token,
		ExpiresAt: expiresAt,
		User: UserResponse{
			ID:       foundUser.ID.String(),
			Username: foundUser.Username,
		},
	})
}
