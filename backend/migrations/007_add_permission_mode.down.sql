-- Remove permission_mode column from conversations table

ALTER TABLE conversations DROP CONSTRAINT IF EXISTS chk_permission_mode;
ALTER TABLE conversations DROP COLUMN IF EXISTS permission_mode;
