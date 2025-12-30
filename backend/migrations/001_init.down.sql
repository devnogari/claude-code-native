DROP TRIGGER IF EXISTS conversations_updated_at ON conversations;
DROP TRIGGER IF EXISTS projects_updated_at ON projects;
DROP TRIGGER IF EXISTS users_updated_at ON users;
DROP FUNCTION IF EXISTS update_updated_at();

DROP TABLE IF EXISTS active_sessions;
DROP TABLE IF EXISTS user_settings;
DROP TABLE IF EXISTS messages;
DROP TABLE IF EXISTS conversations;
DROP TABLE IF EXISTS projects;
DROP TABLE IF EXISTS users;
