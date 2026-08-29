CREATE TABLE IF NOT EXISTS schema_metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS chunks (
    world_uuid TEXT NOT NULL,
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    available INTEGER NOT NULL CHECK (available IN (0, 1)),
    revision INTEGER NOT NULL,
    PRIMARY KEY (world_uuid, chunk_x, chunk_z)
);

CREATE TABLE IF NOT EXISTS containers (
    id INTEGER PRIMARY KEY,
    world_uuid TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    block_type TEXT NOT NULL,
    fingerprint BLOB NOT NULL,
    revision INTEGER NOT NULL,
    UNIQUE (world_uuid, x, y, z)
);

CREATE INDEX IF NOT EXISTS containers_by_chunk
ON containers (world_uuid, chunk_x, chunk_z);

CREATE TABLE IF NOT EXISTS items (
    container_id INTEGER NOT NULL,
    path BLOB NOT NULL,
    amount INTEGER NOT NULL CHECK (amount > 0),
    descriptor BLOB NOT NULL,
    provider_id TEXT NOT NULL,
    provider_version INTEGER NOT NULL,
    vector BLOB NOT NULL,
    vector_norm REAL NOT NULL CHECK (vector_norm >= 0.0),
    PRIMARY KEY (container_id, path),
    FOREIGN KEY (container_id) REFERENCES containers(id) ON DELETE CASCADE
);