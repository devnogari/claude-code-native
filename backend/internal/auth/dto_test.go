package auth

import (
	"testing"
)

func TestLoginRequest_Validate(t *testing.T) {
	tests := []struct {
		name    string
		req     LoginRequest
		wantErr bool
		errMsg  string
	}{
		{
			name:    "valid request",
			req:     LoginRequest{Username: "testuser", Password: "password123"},
			wantErr: false,
		},
		{
			name:    "empty username",
			req:     LoginRequest{Username: "", Password: "password123"},
			wantErr: true,
			errMsg:  "username is required",
		},
		{
			name:    "empty password",
			req:     LoginRequest{Username: "testuser", Password: ""},
			wantErr: true,
			errMsg:  "password is required",
		},
		{
			name:    "username too short",
			req:     LoginRequest{Username: "ab", Password: "password123"},
			wantErr: true,
			errMsg:  "username must be at least 3 characters",
		},
		{
			name:    "username too long",
			req:     LoginRequest{Username: "abcdefghijklmnopqrstuvwxyz1234567890abcdefghijklmnop1", Password: "password123"},
			wantErr: true,
			errMsg:  "username must be at most 50 characters",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.req.Validate()
			if tt.wantErr {
				if err == nil {
					t.Error("expected error, got nil")
					return
				}
				if err.Error() != tt.errMsg {
					t.Errorf("expected error %q, got %q", tt.errMsg, err.Error())
				}
			} else {
				if err != nil {
					t.Errorf("unexpected error: %v", err)
				}
			}
		})
	}
}

func TestRegisterRequest_Validate(t *testing.T) {
	tests := []struct {
		name    string
		req     RegisterRequest
		wantErr bool
		errMsg  string
	}{
		{
			name:    "valid request",
			req:     RegisterRequest{Username: "testuser", Password: "password123", ConfirmPassword: "password123"},
			wantErr: false,
		},
		{
			name:    "passwords don't match",
			req:     RegisterRequest{Username: "testuser", Password: "password123", ConfirmPassword: "different"},
			wantErr: true,
			errMsg:  "passwords do not match",
		},
		{
			name:    "password too short",
			req:     RegisterRequest{Username: "testuser", Password: "short", ConfirmPassword: "short"},
			wantErr: true,
			errMsg:  "password must be at least 8 characters",
		},
		{
			name:    "empty username",
			req:     RegisterRequest{Username: "", Password: "password123", ConfirmPassword: "password123"},
			wantErr: true,
			errMsg:  "username is required",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.req.Validate()
			if tt.wantErr {
				if err == nil {
					t.Error("expected error, got nil")
					return
				}
				if err.Error() != tt.errMsg {
					t.Errorf("expected error %q, got %q", tt.errMsg, err.Error())
				}
			} else {
				if err != nil {
					t.Errorf("unexpected error: %v", err)
				}
			}
		})
	}
}

func TestTokenResponse_HasValidFields(t *testing.T) {
	resp := TokenResponse{
		Token:     "jwt-token",
		ExpiresAt: 1234567890,
		User: UserResponse{
			ID:       "uuid-123",
			Username: "testuser",
		},
	}

	if resp.Token == "" {
		t.Error("expected non-empty token")
	}
	if resp.ExpiresAt == 0 {
		t.Error("expected non-zero expiry")
	}
	if resp.User.ID == "" {
		t.Error("expected non-empty user ID")
	}
}
