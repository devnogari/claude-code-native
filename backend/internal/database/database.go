package database

import (
	"context"
	"database/sql"

	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/uptrace/bun"
	"github.com/uptrace/bun/dialect/pgdialect"
	"github.com/uptrace/bun/driver/pgdriver"
	"go.uber.org/fx"
	"go.uber.org/zap"
)

var Module = fx.Options(
	fx.Provide(New),
)

func New(lc fx.Lifecycle, cfg *config.Config, logger *zap.Logger) *bun.DB {
	sqldb := sql.OpenDB(pgdriver.NewConnector(pgdriver.WithDSN(cfg.Database.URL)))
	db := bun.NewDB(sqldb, pgdialect.New())

	lc.Append(fx.Hook{
		OnStart: func(ctx context.Context) error {
			if err := db.PingContext(ctx); err != nil {
				logger.Error("Failed to connect to database", zap.Error(err))
				return err
			}
			logger.Info("Database connected successfully")
			return nil
		},
		OnStop: func(ctx context.Context) error {
			logger.Info("Closing database connection")
			return db.Close()
		},
	})

	return db
}
