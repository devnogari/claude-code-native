package auth

import (
	"time"

	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/user"
	"github.com/golang-jwt/jwt/v5"
	"go.uber.org/zap"
	"golang.org/x/crypto/bcrypt"
)

const bcryptCost = 12

type Service struct {
	config   *config.Config
	userRepo *user.Repository
	logger   *zap.Logger
}

func NewService(cfg *config.Config, userRepo *user.Repository) *Service {
	return &Service{
		config:   cfg,
		userRepo: userRepo,
	}
}

func NewServiceWithLogger(cfg *config.Config, userRepo *user.Repository, logger *zap.Logger) *Service {
	return &Service{
		config:   cfg,
		userRepo: userRepo,
		logger:   logger,
	}
}

func (s *Service) HashPassword(password string) (string, error) {
	bytes, err := bcrypt.GenerateFromPassword([]byte(password), bcryptCost)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

func (s *Service) VerifyPassword(password, hash string) bool {
	err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
	return err == nil
}

// GenerateToken creates a JWT token for the given user
func (s *Service) GenerateToken(userID, username string) (string, int64, error) {
	expiryDays := s.config.Auth.JWTExpiryDays
	if expiryDays <= 0 {
		expiryDays = 7 // default to 7 days
	}

	expiresAt := time.Now().Add(time.Duration(expiryDays) * 24 * time.Hour).Unix()

	claims := jwt.MapClaims{
		"sub":      userID,
		"username": username,
		"exp":      expiresAt,
		"iat":      time.Now().Unix(),
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tokenString, err := token.SignedString([]byte(s.config.Auth.JWTSecret))
	if err != nil {
		return "", 0, err
	}

	return tokenString, expiresAt, nil
}
