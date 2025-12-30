package auth

import (
	"testing"

	"github.com/devnogari/claude-code-native/backend/internal/config"
)

func TestHashPassword(t *testing.T) {
	svc := NewService(&config.Config{
		Auth: config.AuthConfig{
			JWTSecret:     "testsecret12345678901234567890123",
			JWTExpiryDays: 7,
		},
	}, nil)

	password := "testpassword123"
	hash, err := svc.HashPassword(password)
	if err != nil {
		t.Fatalf("failed to hash password: %v", err)
	}

	if hash == password {
		t.Error("hash should not equal plain password")
	}
}

func TestVerifyPassword(t *testing.T) {
	svc := NewService(&config.Config{
		Auth: config.AuthConfig{
			JWTSecret:     "testsecret12345678901234567890123",
			JWTExpiryDays: 7,
		},
	}, nil)

	password := "testpassword123"
	hash, _ := svc.HashPassword(password)

	if !svc.VerifyPassword(password, hash) {
		t.Error("password verification failed for correct password")
	}

	if svc.VerifyPassword("wrongpassword", hash) {
		t.Error("password verification should fail for wrong password")
	}
}

func TestGenerateAndValidateJWT(t *testing.T) {
	svc := NewService(&config.Config{
		Auth: config.AuthConfig{
			JWTSecret:     "testsecret12345678901234567890123",
			JWTExpiryDays: 7,
		},
	}, nil)

	userID := "test-user-id"

	token, err := svc.GenerateJWT(userID)
	if err != nil {
		t.Fatalf("failed to generate JWT: %v", err)
	}

	claims, err := svc.ValidateJWT(token)
	if err != nil {
		t.Fatalf("failed to validate JWT: %v", err)
	}

	if claims.UserID != userID {
		t.Errorf("expected user ID %s, got %s", userID, claims.UserID)
	}
}
