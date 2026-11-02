-- Teams are identified by number only (TEAM 01~20); the event does not collect team names.
-- Descriptions and every relation to teams(id) are preserved.
ALTER TABLE teams DROP COLUMN name;
