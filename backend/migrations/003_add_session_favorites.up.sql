-- Create session_favorites table for storing favorite status of local Claude sessions
CREATE TABLE IF NOT EXISTS session_favorites (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id VARCHAR(255) NOT NULL,
    project_path VARCHAR(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, session_id, project_path)
);

-- Create index for efficient lookups
CREATE INDEX IF NOT EXISTS idx_session_favorites_user_project ON session_favorites(user_id, project_path);
CREATE INDEX IF NOT EXISTS idx_session_favorites_session ON session_favorites(session_id);
