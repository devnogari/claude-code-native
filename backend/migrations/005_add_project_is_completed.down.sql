-- Remove is_completed column from projects table
DROP INDEX IF EXISTS idx_projects_is_completed;
ALTER TABLE projects DROP COLUMN IF EXISTS is_completed;
