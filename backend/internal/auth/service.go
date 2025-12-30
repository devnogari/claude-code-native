package auth

import (
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/user"
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
