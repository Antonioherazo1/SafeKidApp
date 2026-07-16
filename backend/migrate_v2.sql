-- Migration: Add parent_code column if not exists
ALTER TABLE safekid_users ADD COLUMN IF NOT EXISTS parent_code VARCHAR(10) UNIQUE;
CREATE INDEX IF NOT EXISTS ix_safekid_users_parent_code ON safekid_users (parent_code);

-- Add parent_id column if not exists
ALTER TABLE safekid_users ADD COLUMN IF NOT EXISTS parent_id UUID REFERENCES safekid_users(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS ix_safekid_users_parent_id ON safekid_users (parent_id);

-- Generate parent_code for existing parents that don't have one
UPDATE safekid_users SET parent_code = upper(substr(md5(random()::text || clock_timestamp()::text), 1, 6))
WHERE role = 'parent' AND parent_code IS NULL;
