-- Per-profile opt-in to mature and non-film search results (erotic titles, music videos, sports clips). Off by default.
ALTER TABLE profiles ADD COLUMN mature INTEGER NOT NULL DEFAULT 0;
