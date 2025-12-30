package auth

import (
	"errors"
)

// LoginRequest is the request body for login
type LoginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

func (r *LoginRequest) Validate() error {
	if r.Username == "" {
		return errors.New("username is required")
	}
	if len(r.Username) < 3 {
		return errors.New("username must be at least 3 characters")
	}
	if len(r.Username) > 50 {
		return errors.New("username must be at most 50 characters")
	}
	if r.Password == "" {
		return errors.New("password is required")
	}
	return nil
}

// RegisterRequest is the request body for registration
type RegisterRequest struct {
	Username        string `json:"username"`
	Password        string `json:"password"`
	ConfirmPassword string `json:"confirm_password"`
}

func (r *RegisterRequest) Validate() error {
	if r.Username == "" {
		return errors.New("username is required")
	}
	if len(r.Username) < 3 {
		return errors.New("username must be at least 3 characters")
	}
	if len(r.Username) > 50 {
		return errors.New("username must be at most 50 characters")
	}
	if len(r.Password) < 8 {
		return errors.New("password must be at least 8 characters")
	}
	if r.Password != r.ConfirmPassword {
		return errors.New("passwords do not match")
	}
	return nil
}

// UserResponse is the user info in responses
type UserResponse struct {
	ID       string `json:"id"`
	Username string `json:"username"`
}

// TokenResponse is the response for login/register
type TokenResponse struct {
	Token     string       `json:"token"`
	ExpiresAt int64        `json:"expires_at"`
	User      UserResponse `json:"user"`
}

// ErrorResponse is the standard error response
type ErrorResponse struct {
	Error   string `json:"error"`
	Details string `json:"details,omitempty"`
}
