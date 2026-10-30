-- Provider-source registry: apps only run Vega provider code from the commit an admin approved here.
CREATE TABLE source_pins (id TEXT PRIMARY KEY, commit_sha TEXT NOT NULL, enabled TEXT NOT NULL, approved_by TEXT, approved_at INTEGER NOT NULL);
INSERT INTO source_pins VALUES ('vega', '4ecbc98e12da870ffc06b41bd7affe4b1fe316f0', '["hdhub4u","drive","vega","mod","topmovies"]', 'seed', CAST(strftime('%s','now') AS INTEGER) * 1000);
