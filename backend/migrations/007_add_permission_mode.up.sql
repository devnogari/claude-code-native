-- Add permission_mode column to conversations table
-- Valid values: 'default', 'plan', 'bypassPermissions'
-- Maps to Claude CLI --permission-mode flag

ALTER TABLE conversations
ADD COLUMN permission_mode VARCHAR(32) NOT NULL DEFAULT 'default';

-- Add check constraint for valid permission mode values
ALTER TABLE conversations
ADD CONSTRAINT chk_permission_mode
CHECK (permission_mode IN ('default', 'plan', 'bypassPermissions'));

-- Comment for documentation
COMMENT ON COLUMN conversations.permission_mode IS 'Claude CLI permission mode: default, plan, or bypassPermissions';
