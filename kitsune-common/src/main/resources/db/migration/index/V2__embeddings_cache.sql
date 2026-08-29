CREATE TABLE IF NOT EXISTS embeddings (
    provider_id TEXT NOT NULL,
    provider_version INTEGER NOT NULL,
    descriptor_hash BLOB NOT NULL,
    vector BLOB NOT NULL,
    vector_norm REAL NOT NULL CHECK (vector_norm >= 0.0),
    PRIMARY KEY (provider_id, provider_version, descriptor_hash)
);