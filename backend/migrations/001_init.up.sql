-- Users table
CREATE TABLE IF NOT EXISTS users (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    username      VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ DEFAULT NOW(),
    updated_at    TIMESTAMPTZ DEFAULT NOW(),
    last_login_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);

-- Projects table
CREATE TABLE IF NOT EXISTS projects (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    path          VARCHAR(1024) NOT NULL,
    claude_id     VARCHAR(255),
    last_accessed TIMESTAMPTZ,
    created_at    TIMESTAMPTZ DEFAULT NOW(),
    updated_at    TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, path)
);

CREATE INDEX IF NOT EXISTS idx_projects_user_id ON projects(user_id);

-- Conversations table
CREATE TABLE IF NOT EXISTS conversations (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    claude_session  VARCHAR(255),
    title           VARCHAR(255),
    message_count   INTEGER DEFAULT 0,
    jsonl_path      VARCHAR(1024),
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_conversations_project_id ON conversations(project_id);

-- Messages table
CREATE TABLE IF NOT EXISTS messages (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content         TEXT NOT NULL,
    token_count     INTEGER,
    sequence_num    INTEGER NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation_seq ON messages(conversation_id, sequence_num);

-- User settings table
CREATE TABLE IF NOT EXISTS user_settings (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    theme           VARCHAR(20) DEFAULT 'system' CHECK (theme IN ('light', 'dark', 'system')),
    default_project UUID REFERENCES projects(id) ON DELETE SET NULL,
    preferences     JSONB DEFAULT '{}',
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Active sessions table
CREATE TABLE IF NOT EXISTS active_sessions (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES conversations(id) ON DELETE SET NULL,
    pid             INTEGER,
    started_at      TIMESTAMPTZ DEFAULT NOW(),
    last_activity   TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_active_sessions_user ON active_sessions(user_id);

-- Auto-update trigger
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS users_updated_at ON users;
CREATE TRIGGER users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

DROP TRIGGER IF EXISTS projects_updated_at ON projects;
CREATE TRIGGER projects_updated_at BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

DROP TRIGGER IF EXISTS conversations_updated_at ON conversations;
CREATE TRIGGER conversations_updated_at BEFORE UPDATE ON conversations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
