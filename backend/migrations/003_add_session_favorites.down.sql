-- Remove session_favorites table
DROP INDEX IF EXISTS idx_session_favorites_session;
DROP INDEX IF EXISTS idx_session_favorites_user_project;
DROP TABLE IF EXISTS session_favorites;
