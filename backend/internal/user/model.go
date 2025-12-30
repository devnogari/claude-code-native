package user

import (
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

type User struct {
	bun.BaseModel `bun:"table:users,alias:u"`

	ID           uuid.UUID  `bun:"id,pk,type:uuid,default:uuidv7()"  json:"id"`
	Username     string     `bun:"username,notnull,unique"           json:"username"`
	PasswordHash string     `bun:"password_hash,notnull"             json:"-"`
	CreatedAt    time.Time  `bun:"created_at,default:now()"          json:"created_at"`
	UpdatedAt    time.Time  `bun:"updated_at,default:now()"          json:"updated_at"`
	LastLoginAt  *time.Time `bun:"last_login_at"                     json:"last_login_at,omitempty"`
}
