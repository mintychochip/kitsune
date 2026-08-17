# Embedding Cache and Hopper Coalesce Design

**Date:** 2026-08-17
**Status:** Approved for implementation
**Depends on:** `docs/superpowers/specs/2026-07-31-kitsune-nearby-storage-search-design.md`

## Problem

A hopper, dropper, dispenser, or hopper-minecart transfer fires `InventoryMoveItemEvent`. Kitsune dirties **both** inventories, snapshots every slot, calls `embed()` on every leaf, then `DELETE FROM items` + reinsert. There is no descriptor-keyed reuse.

The only current skip is whole-root fingerprint equality in `ContainerIndex.submitReplacement`. A count-only merge always changes that fingerprint. `SparseTagEmbeddingProvider` and `EmbeddingTextSerializer` ignore `amount`, so those recomputes are identical to the vectors just discarded.

A 1-item/tick hopper line also:

- invalidates search markers on every pulse
- cancels in-flight snapshot revisions
- fails live validation because live SHA-256 ≠ last committed fingerprint

The chest is re-embedded constantly and is usually unsearchable while automation runs.

## Goals

- Re-embed a descriptor only when its **semantic** identity is new for the active provider/version.
- A hopper moving an already-indexed cobblestone into a chest of cobblestone must persist new paths and amounts, not N embedding calls.
- Continuous transfer lines must not cancel every in-flight snapshot or drop the chest from search.
- Keep the whole-root fingerprint skip for true no-ops (reload / reconcile / non-mutating clicks).
- Survive process restart: the first cobblestone after boot may embed; every later identical descriptor reuses the stored vector.

## Non-goals

- Incremental `items` row updates. Wipe-and-rewrite of `items` stays; that write is cheap once vectors are cached.
- Changing the container fingerprint (it still hashes serialized stacks, including count).
- Removing hoppers as indexed roots.
- Eviction / LRU of the embeddings table in this slice.
- Remote-provider HTTP retry policy changes.

## Semantic descriptor key

`ItemDescriptor.amount` is inventory state, not embedding identity. The cache key is SHA-256 of the existing `DescriptorCodec` payload with `amount` forced to `1`.

- 1 cobblestone and 64 cobblestone share a key.
- A renamed, enchanted, or differently tagged cobblestone is a different key.
- The key is independent of slot path and container.

`DescriptorCodec.encode` is unchanged and still stores the real amount on `items.descriptor`.

## Persistent embeddings table

Schema version becomes `2`. Version `1` databases apply `V2__embeddings_cache.sql` transactionally.

```sql
CREATE TABLE IF NOT EXISTS embeddings (
    provider_id TEXT NOT NULL,
    provider_version INTEGER NOT NULL,
    descriptor_hash BLOB NOT NULL,
    vector BLOB NOT NULL,
    vector_norm REAL NOT NULL CHECK (vector_norm >= 0.0),
    PRIMARY KEY (provider_id, provider_version, descriptor_hash)
);
```

`IndexRepository` gains:

- `findEmbeddings(provider, hashes)` → map of hash to decoded `Embedding` for that provider identity
- `putEmbeddings(provider, embeddings)` → upsert vectors for that provider identity

Lookup and store run on the index worker thread with the existing SQLite connection.

Provider mismatch at startup still re-embeds stored item descriptors. `reembedAll` must:

1. embed each **unique** semantic hash once (not once per item row)
2. update every `items` row that uses that hash
3. replace `embeddings` rows for the new provider identity
4. delete `embeddings` rows for any other provider identity

## Replacement path

After the existing fingerprint no-op check fails:

1. Hash every draft leaf semantically.
2. Load cached vectors for those hashes.
3. Collect unique missing descriptors.
4. Call `EmbeddingProvider.embedAll(missing)` (default implementation loops `embed`; the OpenAI-compatible provider already batches).
5. Persist the new vectors.
6. Build `IndexedItem`s from cache + fresh vectors, using each draft’s real amount and path.
7. `replaceRoot` as today (wipe-and-rewrite `items`).

`items` continues to store a copy of the vector next to each path so `loadDocuments` stays a single join and search does not depend on the cache table.

## Hopper / transfer dirty policy

Clicks, drags, cook, and brew stay on `eventTick + 1`.

`InventoryMoveItemEvent` and `InventoryPickupItemEvent` use a trailing debounce of **5 ticks**, capped at **20 ticks** from the first unclaimed dirty in that window. A continuous hopper therefore snapshots at least every 20 ticks, and a short burst waits until 5 ticks after the last move.

If a snapshot is already **claimed / in flight**, a later dirty does **not** increment that revision or cancel the worker. It records a follow-up. Completing the in-flight work schedules one new snapshot at `currentTick + 1` when a follow-up is pending.

`DELETE` still cancels claimed snapshot work and wins.

`DirtyRootTracker` exposes `isPending(BlockKey)` for tests and diagnostics. It is not required for live validation.

## Search freshness

Content dirty no longer invalidates search-session markers. Markers identify a root location. Delete, chunk unload, and disappearance of a previously published root still invalidate.

Live validation keeps: same world, in radius, chunk loaded, snapshot complete, same canonical key, same block type, protection allows. It **stops** requiring live fingerprint == committed fingerprint.

Search documents remain the last committed snapshot. During a hopper pulse, amounts and newly arrived item types can lag until the next committed replacement. That is preferred to dropping the chest from results for the entire transfer.

Reconcile (200 ticks) still repairs missed mutations.

## Threading and identity

Unchanged: server thread snapshots; index worker embeds and writes SQLite. `EmbeddingProvider` stays thread-safe and accepts only immutable descriptors. Cache keys include `provider_id` and `provider_version` so a provider switch cannot reuse another vector space.

## Testing

- Semantic hash: amount-insensitive, material/name/enchantment-sensitive.
- Repository: put/find round-trip; different provider identity misses; `reembedAll` embeds unique hashes once and rewrites the cache table.
- Resolver: two identical-semantic stacks → one `embed`/`embedAll` item; second resolve of the same hash → zero provider calls.
- Tracker: transfer debounce 5 / max 20; in-flight dirty does not stale the claimed revision; follow-up runs after complete; delete still supersedes.
- Container index: fingerprint-changing count-only replacement does not increment embed calls when the hash is cached.
- Listener: move/pickup still dirty source and destination (via the transfer path).
- Session: `markDirty` does not call `rootInvalidated`; `delete` and unload still do.
- Live access: matching block type with a different fingerprint is still allowed.

## Decisions

| Date | Decision | Why |
|------|----------|-----|
| 2026-08-17 | Persistent table, not process-local memo | Hoppers outlive restarts; remote embed is expensive |
| 2026-08-17 | Hash excludes amount | Providers ignore amount; count-only merges are the hopper common case |
| 2026-08-17 | Keep wipe-and-rewrite `items` | Vectors are the cost; path rewrite is not |
| 2026-08-17 | Debounce only transfer/pickup | Player clicks stay `+1` tick fresh |
| 2026-08-17 | Do not cancel in-flight snapshots | Hopper every tick currently discards completed work |
| 2026-08-17 | Drop live fingerprint equality | Last committed snapshot stays searchable mid-transfer |
| 2026-08-17 | `markDirty` does not clear markers | A sorter pulse is not “this chest is gone” |
