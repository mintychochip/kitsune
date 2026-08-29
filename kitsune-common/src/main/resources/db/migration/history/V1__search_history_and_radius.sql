CREATE TABLE IF NOT EXISTS search_history (
    id TEXT PRIMARY KEY,
    player_id TEXT NOT NULL,
    query TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    result_count INTEGER NOT NULL CHECK (result_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_search_history_player
ON search_history (player_id, timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_search_history_timestamp
ON search_history (timestamp DESC);

CREATE TABLE IF NOT EXISTS player_radius_limits (
    player_id TEXT PRIMARY KEY,
    radius INTEGER NOT NULL CHECK (radius >= 1 AND radius <= 128),
    updated_at INTEGER NOT NULL
);