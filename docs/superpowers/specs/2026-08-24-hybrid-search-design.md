# Hybrid Full-Text + Semantic Search Design

**Date:** 2026-08-24
**Status:** Approved for implementation planning
**Depends on:** `docs/superpowers/specs/2026-07-31-kitsune-nearby-storage-search-design.md`
**Supersedes (search path only):** phonetic retriever from the 2026-08-24 brainstorm (dropped). Does **not** implement `docs/superpowers/specs/2026-08-22-lightweight-metadata-runtime-design.md` lexical-only default.

## Goal

`/kitsune <query>` shall retrieve nearby storage with two independent retrievers — SQLite FTS5 full-text and the configured embedding cosine — then fuse those ranked lists with Reciprocal Rank Fusion so exact names, prefixes, aliases, and paraphrases share one result list. Player commands, markers, radius, live-root checks, and protection filtering stay unchanged.

## Scope

**In scope**

- FTS5 projection of canonical item metadata in the existing SQLite index.
- Always-on hybrid ranking: FTS5 + current embedding provider, Reciprocal Rank Fusion.
- Safe MATCH construction from tokenized player text (never raw FTS syntax).
- Schema V3 migration that backfills FTS from stored descriptor blobs and keeps vectors.
- Config for RRF `k` and per-retriever candidate caps (not retriever on/off toggles).
- Tests for tokenizer/MATCH safety, fusion, protection, index maintenance, and migration.

**Out of scope / non-goals**

- Phonetic / Double Metaphone / Soundex indexes.
- Turning either retriever off, or a lexical-only runtime that drops embeddings.
- Apache Lucene, JVector ANN, or a second search engine.
- Dropping `items.vector`, the `embeddings` cache, or moving the OpenAI-compatible provider out of `kitsune-common`.
- New command flags, inventory GUIs, or player-visible mode switches.
- Searching unloaded chunks, player inventories, entity inventories, or bypassing protection.
- Changing marker, session, or chat-tree contracts except that the single displayed score is the normalized fused score.

## Player contract

`/kitsune <query>` and `/kitsune --verbose <query>` remain the only invocations. No hybrid flag.

One numbered result list. Roots still sort by descending score, then ascending Euclidean distance, then canonical coordinates. Default output still hides scores; verbose still prints one score per match. That score is the normalized fused value in `[0, 1]`, not raw cosine and not raw RRF.

Inaccessible, stale, unloaded, or out-of-radius roots never appear, including FTS-only hits.

## Architecture

```text
query
  ├─ FeatureVocabulary.tokenize + escape → FTS5 MATCH
  └─ embedQuery → cosine vs nearby item vectors (minimum-score filter)
         ↓
  two ranked item-path lists, each capped
         ↓
  RRF at (root key, item path): Σ 1/(k + rank_r)
         ↓
  display score = min(1.0, rrf * (k + 1) / 2)
         ↓
  roll up to roots (best fused child, then distance, then coords)
         ↓
  live / protection / radius gates; existing markers and chat
```

Both retrievers run on every successful query. An empty list from one retriever is valid; fusion of a single list still produces results. If either retriever throws, the search fails (same fail-closed behavior as today's embed failure).

All FTS and vector I/O stays on `IndexWorker`. The main thread never waits on SQLite.

## Data model

Schema version becomes `3`. Version `2` databases apply V3 transactionally and rebuild the FTS projection from `items.descriptor`. Fresh installs apply V1 → V2 → V3.

`items` gains `id INTEGER PRIMARY KEY`. `(container_id, path)` remains unique. Vector columns stay. V3 rebuilds `items` into a new table (SQLite cannot add a PK column in place), copies existing rows, and replaces the old table in the same transaction as FTS creation.

FTS cannot use `content='items'` because `items` stores a descriptor blob, not FTS columns. The projection is a dedicated content table whose row id equals `items.id`:

```sql
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
```

Triggers on `item_search` keep `item_fts` in lockstep (insert / update / delete). `PRAGMA foreign_keys = ON` is already required. Cascade delete of `items` must delete `item_search` and therefore FTS postings.

### Projection fields

Built from the canonical `ItemDescriptor` with `FeatureVocabulary.tokenize`. Fields are space-joined unique tokens:

| FTS column | Source | BM25 weight |
|---|---|---|
| `material` | `materialKey` | 10.0 |
| `display` | `displayText` | 8.0 |
| `tags` | `customTags`, `traits`, and `FeatureVocabulary` aliases of all projected tokens | 6.0 |
| `enchantments` | enchantment keys and attribute keys | 5.0 |
| `lore` | `lore` | 2.0 |

`replaceRoot` writes `items` then `item_search` in the same transaction. `deleteRoot` and container FK deletes must not leave FTS rows whose `item_id` is missing from `items`.

## Query construction

Raw player text is never passed to `MATCH`.

1. `tokens = FeatureVocabulary.tokenize(query)`.
2. If `tokens` is empty, return the existing unsupported-query outcome. Do not run FTS or embed.
3. For each token, collect `FeatureVocabulary.aliasesFor(token)`.
4. Escape by doubling `"` inside a token, then wrap the token in `"..."`.
5. Last **query** token (not aliases) is emitted as `"token"*` (FTS5 prefix). Earlier query tokens are exact quoted terms.
6. Each query token becomes a group `(quotedToken OR quotedAlias…)`. Groups are joined with `AND`.
7. Bind the finished expression as a single `PreparedStatement` parameter.

Example: query `oak plank` with alias `plank → wood` becomes:

```text
("oak") AND ("plank"* OR "wood")
```

Query `oak AND planks` tokenizes to `oak`, `and`, `planks`. `AND`/`OR`/`NOT`/`NEAR`/`*`/`"` in the player string never become MATCH operators because they are either stripped by the tokenizer or quoted as literals.

## Retrieval and fusion

Shared spatial universe remains the loaded-chunk rectangle around the player (existing candidate bounds). Euclidean radius, live fingerprint, and protection still run through `LiveRootAccess.validate` before a hit is visible.

**Full-text retriever**

```sql
SELECT c.world_uuid, c.x, c.y, c.z, c.block_type, c.fingerprint, c.revision,
       i.path, bm25(item_fts, 10.0, 8.0, 6.0, 5.0, 2.0) AS rank
FROM item_fts
JOIN item_search s ON s.item_id = item_fts.rowid
JOIN items i ON i.id = s.item_id
JOIN containers c ON c.id = i.container_id
JOIN chunks ch ON ch.world_uuid = c.world_uuid
               AND ch.chunk_x = c.chunk_x
               AND ch.chunk_z = c.chunk_z
WHERE item_fts MATCH ?
  AND ch.available = 1
  AND c.world_uuid = ?
  AND c.chunk_x BETWEEN ? AND ?
  AND c.chunk_z BETWEEN ? AND ?
ORDER BY rank ASC, c.x, c.y, c.z, i.path
LIMIT ?
```

FTS5 `bm25()` is lower-is-better (more relevant rows are more negative). Rank 1 is the first row after this order. Cap `search.hybrid.full-text-limit` (default 64). Drop hits whose roots fail live/protection/radius validation. Do not apply `minimum-score` to this list.

**Semantic retriever**

Unchanged load-and-cosine path over spatially paged, validated roots. Keep hits with cosine `>= search.minimum-score`. Sort with the existing item comparator (score desc, path, amount, material). Cap `search.hybrid.semantic-limit` (default 64).

**RRF**

Identity is `(BlockKey, ItemPath)`. 1-based ranks. Missing retriever contributes 0.

```text
rrf(item) = Σ 1 / (k + rank_r)    for retrievers that returned the item
score     = min(1.0, rrf * (k + 1) / 2)
```

Default `k = 60`. Two retrievers at rank 1 → display `1.0`. One retriever at rank 1 → display `0.5`. `ItemMatch.score` and `RootMatch.bestScore` remain in `[0, 1]`.

Root rollup: union of fused item hits, grouped by root. `bestScore` is the best fused display score. `totalMatchingStacks` counts fused hits for that root, not cosine-only hits. Visible paths still truncated to `max-paths-per-root`. Root cap still `max-results`. Comparator: fused score desc, distance asc, coordinates.

An FTS-only item (cosine below `minimum-score` or absent from the semantic cap) still appears if it survived FTS validation.

## Configuration

Add under `search.hybrid` in `kitsune-paper/src/main/resources/config.yml`. Retrievers cannot be disabled.

```yaml
search:
  hybrid:
    rrf-k: 60
    full-text-limit: 64
    semantic-limit: 64
```

| Key | Default | Bounds |
|---|---|---|
| `search.hybrid.rrf-k` | 60 | 1–1000 |
| `search.hybrid.full-text-limit` | 64 | 1–256 |
| `search.hybrid.semantic-limit` | 64 | 1–256 |

`KitsuneConfig` and `SearchPolicy` carry these three values. Missing keys use defaults so existing `config.yml` files keep working. Invalid values fail config load with the existing `Invalid …` pattern.

## Failure handling

| Condition | Outcome |
|---|---|
| Empty / punctuation-only query after tokenize | `SearchOutcome.unsupportedQuery()`; no FTS, no embed |
| Zero-norm query embedding | existing unsupported-query (semantic cannot run; always-on hybrid fails closed) |
| FTS or cosine throws | `SearchOutcome.failure()` |
| Canceled session | `SearchOutcome.canceled()` |
| Warmup timeout | `SearchOutcome.indexWarming()` |
| FTS empty, semantic hits (or reverse) | success from the non-empty list |
| Both empty after filters | `SearchOutcome.noMatches()` |
| MATCH string would be empty | unsupported-query, same as empty tokenize |
| V3 migration interrupted | rollback; schema stays at 2; next start retries |
| FTS row count ≠ items count after backfill | rollback; do not advertise schema 3 |

## Functional requirements

**FR-001** Every successful `/kitsune` query shall run FTS5 and embedding cosine, then RRF.
- **AC-1** A test item that only FTS-matches appears in the outcome.
- **AC-2** A test item that only cosine-matches (above `minimum-score`, absent from the FTS list) appears in the outcome. `SearchService` tests inject independent retriever lists; do not rely on `builtin:sparse-v1` vocabulary diverging from FTS.
- **AC-3** An item in both lists ranks above an otherwise equal item that appears in only one list.

**FR-002** Player text shall never be executed as FTS MATCH syntax.
- **AC-1** Queries containing `AND`, `OR`, `NOT`, `NEAR`, `"`, `*`, `(`, `)` either tokenize to quoted literals or drop those characters; they do not change MATCH operator structure.
- **AC-2** The MATCH expression is bound through a `PreparedStatement` placeholder.

**FR-003** Last query token shall be an FTS5 prefix; earlier tokens shall be exact.
- **AC-1** Query `diam` retrieves an indexed `minecraft:diamond` stack.
- **AC-2** Query `oak plank` requires an `oak` token and a `plank` prefix (and aliases of those tokens), not a global OR of aliases that drops `oak`.

**FR-004** `minimum-score` shall filter the semantic list only, before fusion.
- **AC-1** An FTS hit whose cosine is below `minimum-score` is still returned.
- **AC-2** A cosine hit below `minimum-score` with no FTS match is not returned.

**FR-005** Displayed scores shall lie in `[0, 1]` and reflect fusion, not raw BM25 or raw RRF.
- **AC-1** Rank-1 on both retrievers yields display score `1.0` at default `k`.
- **AC-2** Rank-1 on a single retriever yields display score `0.5` at default `k`.
- **AC-3** `ItemMatch` / `RootMatch` construction still rejects scores outside `[0, 1]`.

**FR-006** Protection, live-root, availability, and radius invariants shall apply to FTS hits.
- **AC-1** An FTS match in a denied, stale, unloaded, or out-of-radius root never appears in `SearchOutcome` and is never passed to rendering.
- **AC-2** Aggregate totals count only accessible matching roots.

**FR-007** Index writes shall keep FTS aligned with `items`.
- **AC-1** `replaceRoot` makes new descriptor text searchable and removes text for stacks no longer in the snapshot.
- **AC-2** `deleteRoot` leaves zero `item_search` / `item_fts` rows for that container.
- **AC-3** Reopen after replace/delete still returns the same FTS hits.

**FR-008** V3 migration shall backfill FTS from stored descriptors and keep vectors.
- **AC-1** Opening a V2 database sets `schema_version` to 3, FTS row count equals `items` count, and `loadDocuments` still decodes vectors.
- **AC-2** A backfill that cannot verify counts rolls back to version 2.

**FR-009** Hybrid caps and `k` shall be configurable without retriever toggles.
- **AC-1** Default config documents `rrf-k`, `full-text-limit`, `semantic-limit`.
- **AC-2** There is no config key that disables FTS or cosine.
- **AC-3** Out-of-range hybrid values fail config validation.

**FR-010** Command surface, markers, and session replacement shall stay as specified on 2026-07-31, except the meaning of the verbose score.
- **AC-1** Existing command-lifecycle, marker, and session tests still pass after ranking changes.

## Non-functional requirements

**NFR-001** FTS and vector SQL shall run only on `IndexWorker` (or equivalent off-main index thread). Main-thread search work remains live-root validation and rendering.
- **AC-1** Repository FTS methods are invoked from the worker submit path in `SearchService`, matching existing `findCandidates` / `loadDocuments`.

**NFR-002** Each retriever shall return at most its configured cap; fusion shall not sort an unbounded union of every nearby stack.
- **AC-1** Tests with more than `full-text-limit` FTS hits only fuse the capped prefix.
- **AC-2** Tests with more than `semantic-limit` cosine hits only fuse the capped prefix.

**NFR-003** MATCH construction shall be deterministic for a given query string and `FeatureVocabulary`.
- **AC-1** The same query yields the same MATCH string in repeated unit tests.

**NFR-004** Migration and `replaceRoot` shall be transactional: a failure leaves schema and searchable rows as before the attempt.
- **AC-1** Forced failure during V3 backfill leaves `schema_version = 2` and no half-built `item_fts` advertised as ready.

## Affected components

| Component | Change |
|---|---|
| `kitsune-common/.../db/migration/V3__item_fts.sql` | New. `items.id`, `item_search`, `item_fts`, triggers. |
| `SqliteIndexRepository` | V3 migrate/backfill; FTS writes in `replaceRoot`; `findFullTextMatches`. |
| `IndexRepository` | New full-text lookup contract. |
| `SearchService` | Dual retrieval + RRF + normalized scores. |
| `SearchPolicy`, `KitsuneConfig`, `ConfigLoader`, `config.yml` | Hybrid knobs. |
| `FeatureVocabulary` (or a sibling `FullTextQuery` type) | MATCH builder. |
| Search/index tests, config tests, README ranking sentence | Contracts above. |

Do not add a phonetic package, Lucene, or a second SQLite file.

## Alternatives considered

| Option | Why rejected |
|---|---|
| Double Metaphone third retriever | Dropped: FTS5 prefix + aliases cover the intended misspellings without a second index. |
| FTS as candidate filter, cosine re-rank only | Drops paraphrase hits with no token overlap. |
| Weighted linear blend of BM25 and cosine | Incompatible scales; needs constant tuning. |
| Cascade (FTS then phonetic then semantic) | Hides paraphrases whenever any text hits exist. |
| Lucene beside SQLite | Duplicate write path; unnecessary at radius-bounded cardinality. |
| `content='items'` FTS | `items` is a blob store; column names would not match. |
| Raw RRF as `ItemMatch.score` | Violates existing `[0, 1]` score invariant. |
| 2026-08-22 lexical-only default | Out of scope; embeddings remain always-on. |

## Verification policy

```bash
./gradlew :kitsune-api:test :kitsune-common:test :kitsune-paper:test
```

Expected: BUILD SUCCESSFUL, all tests PASS.

Required new or updated tests (names indicative):

- MATCH builder: operators, quotes, wildcards, empty input, last-token prefix, alias AND-groups.
- `SearchService`: FTS-only hit; cosine-only hit; both-lists beats one-list; `minimum-score` does not drop FTS-only; denied FTS root absent; caps honored.
- `SqliteIndexRepository`: FTS insert/replace/delete/restart; spatial + availability predicates; V2 → V3 backfill row counts and vector round-trip; backfill rollback.
- `ConfigLoader` / `KitsuneConfig`: defaults, bounds, no disable flags.

No live OpenAI calls. No in-game proof required for this spec; Paper tests stay unit/integration as today.

## Open questions

None remaining from design review. Phonetic dropped. Fusion is RRF. Retrievers always-on.

## Decisions log

| Date | Decision | Why |
|---|---|---|
| 2026-08-24 | Reciprocal Rank Fusion of independent retrievers | Exact, prefix, and paraphrase hits share one list without score-scale mixing. |
| 2026-08-24 | Drop phonetic / Double Metaphone | FTS5 prefix and aliases are enough; extra index not justified. |
| 2026-08-24 | Always-on FTS + configured embedding provider | `/kitsune` stays one command; no retriever toggles. |
| 2026-08-24 | `minimum-score` filters semantic list only | FTS-only name hits must survive weak cosine. |
| 2026-08-24 | Display `min(1.0, rrf * (k+1) / 2)` | Preserves `ItemMatch` `[0, 1]` contract; rank-1 both → 1.0. |
| 2026-08-24 | FTS projection table, not `content='items'` | Items persist descriptor blobs; FTS needs typed text columns. |
| 2026-08-24 | Do not implement 2026-08-22 lexical-only default | Hybrid keeps vectors and the embedding provider. |
