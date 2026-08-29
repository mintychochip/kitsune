CREATE TABLE items_v3 (
    id INTEGER PRIMARY KEY,
    container_id INTEGER NOT NULL,
    path BLOB NOT NULL,
    amount INTEGER NOT NULL CHECK (amount > 0),
    descriptor BLOB NOT NULL,
    provider_id TEXT NOT NULL,
    provider_version INTEGER NOT NULL,
    vector BLOB NOT NULL,
    vector_norm REAL NOT NULL CHECK (vector_norm >= 0.0),
    UNIQUE (container_id, path),
    FOREIGN KEY (container_id) REFERENCES containers(id) ON DELETE CASCADE
);
INSERT INTO items_v3 (
    container_id, path, amount, descriptor, provider_id, provider_version, vector, vector_norm
)
SELECT container_id, path, amount, descriptor, provider_id, provider_version, vector, vector_norm
FROM items;
DROP TABLE items;
ALTER TABLE items_v3 RENAME TO items;

CREATE TABLE item_search (
    item_id INTEGER PRIMARY KEY,
    material TEXT NOT NULL,
    display TEXT NOT NULL,
    tags TEXT NOT NULL,
    enchantments TEXT NOT NULL,
    lore TEXT NOT NULL,
    FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE
);

CREATE VIRTUAL TABLE item_fts USING fts5(
    material,
    display,
    tags,
    enchantments,
    lore,
    content='item_search',
    content_rowid='item_id',
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TRIGGER item_search_ai AFTER INSERT ON item_search BEGIN
  INSERT INTO item_fts(rowid, material, display, tags, enchantments, lore)
  VALUES (new.item_id, new.material, new.display, new.tags, new.enchantments, new.lore);
END;

CREATE TRIGGER item_search_ad AFTER DELETE ON item_search BEGIN
  INSERT INTO item_fts(item_fts, rowid) VALUES('delete', old.item_id);
END;

CREATE TRIGGER item_search_au AFTER UPDATE ON item_search BEGIN
  INSERT INTO item_fts(item_fts, rowid) VALUES('delete', old.item_id);
  INSERT INTO item_fts(rowid, material, display, tags, enchantments, lore)
  VALUES (new.item_id, new.material, new.display, new.tags, new.enchantments, new.lore);
END;