-- Reelm's own collaborative filtering, rebuilt nightly from history by rebuildRecs() (cron in wrangler.toml).
CREATE TABLE related (item TEXT NOT NULL, other TEXT NOT NULL, score REAL NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (item, other));
CREATE INDEX related_item ON related(item, score);
CREATE TABLE popular (item TEXT PRIMARY KEY, score REAL NOT NULL, viewers INTEGER NOT NULL, meta TEXT);
-- Latest card payload per history key (title, poster, kind, ids only), kind = anime|movies|series, kids = a kids profile watched it.
CREATE TABLE item_meta (item TEXT PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL, updated_at INTEGER NOT NULL, kids INTEGER NOT NULL DEFAULT 0);
