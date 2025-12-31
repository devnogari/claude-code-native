-- Remove is_favorite column from conversations table
DROP INDEX IF EXISTS idx_conversations_is_favorite;
ALTER TABLE conversations DROP COLUMN IF EXISTS is_favorite;
