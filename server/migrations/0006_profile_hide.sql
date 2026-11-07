-- Per-profile kinds of content to hide (JSON array of anime/movies/series/live); never all four.
ALTER TABLE profiles ADD COLUMN hide TEXT NOT NULL DEFAULT '[]';
