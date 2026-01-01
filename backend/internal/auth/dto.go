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
	// Maximum password length to prevent bcrypt DoS (bcrypt truncates at 72 bytes)
	if len(r.Password) > 72 {
		return errors.New("password must be at most 72 characters")
	}
	// Check for password complexity: at least one uppercase, lowercase, and number
	hasUpper := false
	hasLower := false
	hasNumber := false
	for _, c := range r.Password {
		switch {
		case c >= 'A' && c <= 'Z':
			hasUpper = true
		case c >= 'a' && c <= 'z':
			hasLower = true
		case c >= '0' && c <= '9':
			hasNumber = true
		}
	}
	if !hasUpper || !hasLower || !hasNumber {
		return errors.New("password must contain at least one uppercase letter, one lowercase letter, and one number")
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
