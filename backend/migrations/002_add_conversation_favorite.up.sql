-- Add is_favorite column to conversations table
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS is_favorite BOOLEAN NOT NULL DEFAULT false;

-- Create index for efficient favorite queries
CREATE INDEX IF NOT EXISTS idx_conversations_is_favorite ON conversations(is_favorite) WHERE is_favorite = true;
