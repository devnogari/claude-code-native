-- Add is_completed column to projects table
ALTER TABLE projects ADD COLUMN is_completed BOOLEAN NOT NULL DEFAULT FALSE;

-- Create index for filtering completed projects
CREATE INDEX idx_projects_is_completed ON projects (is_completed);
