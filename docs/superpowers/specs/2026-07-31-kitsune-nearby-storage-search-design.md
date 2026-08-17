# Kitsune Nearby Storage Search Design

**Date:** 2026-07-31
**Status:** Approved for implementation planning; protection scope revised to SPI plus LWCX
**Primary runtime:** Paper 1.21.4 on Java 21

## Summary

Kitsune is a Paper plugin that lets a player semantically search nearby block-based storage with `/kitsune <query>`. It maintains a persistent SQLite index of storage contents, represents item descriptors as vectors, ranks item paths with cosine similarity, filters inaccessible roots through protection-provider APIs before loading result details, and marks matching storage blocks with temporary player-private billboards.

The core plugin ships a deterministic sparse tag embedding. It does not bundle ONNX or call a remote service. Versioned provider interfaces allow later embedding models, custom-item feature extractors, custom nested-container extractors, and protection integrations without changing the search or UI contracts.

## Goals

- Build and run as a Java 21 Paper 1.21.4 plugin with Gradle Kotlin DSL and `xyz.jpenilla.run-paper`.
- Search block-based storage within a configurable 32-block Euclidean radius without force-loading or generating chunks.
- Persist container descriptors and vectors in SQLite while validating live world and access state before rendering results.
- Rank material, category, name, lore, enchantment, attribute, and custom metadata matches with cosine similarity.
- Preserve the full root-to-leaf path for items inside shulker boxes, bundles, and registered custom nested containers.
- Keep normal output visually small; expose scores, metadata, coordinates, and nested trees only with `--verbose`.
- Prevent result counts, contents, coordinates, and markers from leaking protected storage.
- Ship an optional LWCX adapter while allowing Bolt and every other protection plugin to register `BlockAccessProvider` through a separate bridge.
- Give unknown custom items a useful fallback descriptor instead of omitting them.

## Non-goals

- No inventory-menu search or result GUI. The user explicitly selected world-only result markers.
- No teleporting, opening, or modifying matching storage.
- No forced chunk loads, offline-world search, player inventories, player-scoped ender-chest contents, or entity inventories.
- No bundled ONNX runtime, bundled language model, or required external embedding service.
- No inference of opaque custom backpack contents without a registered nested-contents provider.
- No built-in land-claim compatibility matrix or unverified Bolt adapter; core ships the generic protection SPI and the source-verified LWCX integration.
- No permanent holograms or world block changes.

## Terminology

- **Root:** One logical block inventory. Connected double-chest halves form one root with a canonical anchor at the lexicographically lower `(x, y, z)` location.
- **Item path:** The ordered root slot and nested-container slots leading to one leaf stack.
- **Descriptor:** Immutable plain data derived from an item stack on the server thread.
- **Vector:** A provider-owned, versioned representation used for cosine similarity.
- **Search session:** One player's query, async work, chat output, billboards, and expiration task.
- **Accessible:** Allowed by every applicable protection provider at query time.

## Player Contract

### Commands

```text
/kitsune <query...>
/kitsune --verbose <query...>
```

`--verbose` is recognized only as the first argument and applies only to that invocation. Query text is the remaining non-empty, space-joined arguments. The command requires `kitsune.search` and a player sender because world position and private entity visibility are required.

One player may have one active search session. Starting another search cancels all stages of the previous session and removes its markers. Empty input returns command usage without starting a session.

### Search scope and ordering

- Default radius: 32 blocks.
- Configurable radius bounds: 1–128 blocks.
- Same world as the player's command location.
- Only roots whose chunks are currently loaded and reconciled or can be reconciled within the search warm-up deadline.
- Exact three-dimensional Euclidean distance is applied after the indexed chunk-coordinate candidate lookup.
- A leaf qualifies at or above the configured minimum cosine score.
- A root score is its highest qualifying leaf score.
- Roots sort by descending score, then ascending distance, then canonical coordinates for deterministic ties.
- Default root-result cap: 32. If more qualify, chat reports `Showing 32 of N matching storage blocks` without revealing inaccessible roots in `N`.

### Normal output

Every matching root receives one billboard above its canonical anchor. The billboard shows only:

```text
<QUERY LABEL> FOUND · <MATCHING LEAF STACKS> · <DISTANCE>m
```

The count is the number of qualifying leaf stacks, not the sum of their item amounts. Chat emits one aggregate line containing accessible matching-root and qualifying-stack counts. It does not print coordinates, paths, scores, or metadata.

### Verbose output

Verbose mode uses both surfaces:

- The billboard adds root type, best score, and bounded root-to-leaf paths.
- Chat prints numbered ASCII trees with canonical coordinates, root type, slot path, nested container names, leaf amount, score, and relevant matched metadata.

Example:

```text
[1] Barrel @ 12, 64, -8 · score 0.91
└─ slot 4: Purple Shulker Box x1
   └─ slot 12: Diamond Pickaxe x1 · score 0.91
      └─ Efficiency V, Mending
```

Verbose trees contain only qualifying paths. Output is capped by the configured root limit, path-per-root limit, and component length limits.

### Marker lifecycle and privacy

- Default lifetime: 20 seconds; configurable with bounded values.
- Markers clear on replacement search, expiration, player logout, world change, root invalidation, plugin disable, or explicit session cancellation caused by an error.
- Kitsune spawns a non-persistent `TextDisplay` through the Paper pre-spawn configuration callback, calls `setVisibleByDefault(false)` before the entity enters the world, and calls `showEntity(plugin, display)` only for the search owner.
- Default-hidden visibility means current and future non-owner players do not receive the marker unless explicitly shown it.
- Displays use center billboard mode, no gravity, a bounded background, and see-through text so they can identify storage behind a wall. Removing a session removes its entities rather than merely hiding them.
- A two-player live test must prove that the owner sees each marker and the second player does not.

Paper 1.21.4 documents that a non-default-visible entity requires `Player.showEntity` before it becomes visible to a player, and that the spawn consumer runs before the entity is spawned. Relevant API references:

- <https://jd.papermc.io/paper/1.21.4/org/bukkit/entity/Entity.html#setVisibleByDefault(boolean)>
- <https://jd.papermc.io/paper/1.21.4/org/bukkit/entity/Player.html#showEntity(org.bukkit.plugin.Plugin,org.bukkit.entity.Entity)>
- <https://jd.papermc.io/paper/1.21.4/org/bukkit/RegionAccessor.html#spawn(org.bukkit.Location,java.lang.Class,java.util.function.Consumer)>

## Storage Discovery and Indexing

### Supported roots

A root is a loaded block state that owns a persistent block inventory. This capability-based rule includes chests, trapped chests, barrels, placed shulker boxes, furnaces and variants, hoppers, brewing stands, dispensers, droppers, crafters, chiseled bookshelves, decorated pots, lecterns, and compatible future Paper block inventory holders without maintaining an air-block scan or a material-by-material world walk.

The following are excluded:

- player-scoped ender-chest contents;
- entity inventories such as chest minecarts and chest boats;
- inventories not anchored to a persistent block;
- unresolved loot-table roots whose contents have not been generated by normal gameplay.

Reading an unresolved loot root must not generate loot. Such a root remains unindexed until normal gameplay resolves it and a handled mutation trigger (click, drag, transfer, pickup, cooking, or brewing) marks it dirty; opening alone is not an indexing trigger.

Connected double chests are indexed once. Both halves must be loaded. Either half's content event invalidates the canonical root.

### Cheap chunk discovery

Kitsune never iterates every block in a chunk or section. Chunk-load work requests the chunk's block/tile-entity states, filters those states by the persistent block-inventory capability, canonicalizes roots, and enqueues bounded snapshot work. Empty sections therefore cost no block traversal.

Search never loads a chunk. SQLite records from unloaded chunks remain useful as a warm persistent cache but cannot become live results.

### Freshness model

The index is persistent but rebuildable from live worlds:

1. Chunk load discovers root identities and queues initial snapshots.
2. Block placement, removal, explosion, and relevant block-state changes insert, delete, or dirty roots.
3. Inventory click, drag, transfer, pickup, cooking, brewing, and related transaction events dirty affected roots.
4. Dirty snapshots for clicks, drags, cook, and brew run one tick after a transaction so they observe committed inventory state. Hopper, dropper, dispenser, and pickup transfers debounce 5 ticks and wait at most 20 ticks from the first unclaimed dirty in that window.
5. Dirty roots are deduplicated by canonical root key. A later transfer does not cancel an in-flight snapshot; it schedules one follow-up after that write completes.
6. Content dirty does not clear search-session markers. Delete, chunk unload, and disappearance of a published root still do.
7. A periodic budgeted reconciliation pass re-snapshots loaded roots to repair events missed because of automation or another plugin.
8. Chunk unload marks roots unavailable for live search but keeps committed SQLite rows.

Defaults are two chunk-discovery jobs per tick, eight root snapshots per tick, and a 200-tick reconciliation interval. These values are configurable within enforced bounds. A search prioritizes nearby loaded chunks that have not completed their initial scan. It waits asynchronously for at most three seconds; after that it reports that the nearby index is still warming and creates no partial markers.

### Thread boundary

Bukkit/Paper worlds, chunks, blocks, inventories, item stacks, entities, players, and protection APIs are accessed only on the owning server thread. The server thread converts each root into immutable records containing primitives, strings, byte arrays, and immutable collections.

One background index executor owns the SQLite connection and performs descriptor encoding, vector generation, cosine calculations, and database transactions. `EmbeddingProvider` implementations must be thread-safe and accept only immutable descriptors. `ItemFeatureProvider` and `NestedContentsProvider` implementations run during the server-thread snapshot stage and may inspect Bukkit item objects only for that call; they may not retain them.

Every async search stage carries a monotonically increasing player session token. Completion checks the token before scheduling or performing subsequent work, so canceled or superseded searches cannot render late results.

## SQLite Model

The plugin data directory contains one SQLite database. Schema migrations are transactional and versioned.

Logical tables are:

- `schema_metadata`: schema and active embedding-profile versions.
- `chunks`: world UUID, chunk coordinates, last reconciliation revision, and availability metadata.
- `containers`: canonical root ID, world UUID, block coordinates, block type, content fingerprint, and update revision.
- `items`: root ID, encoded slot path, leaf amount, canonical descriptor, embedding provider/version, vector payload, and vector norm.
- `embeddings`: provider id/version, semantic descriptor hash (SHA-256 of the descriptor codec with amount forced to 1), vector payload, and vector norm. Replacement reuses cached vectors and only embeds unique misses.

Foreign keys cascade item deletion when a root is replaced or removed. A unique root-coordinate key prevents duplicate double-chest rows. An index on `(world_uuid, chunk_x, chunk_z)` bounds candidate selection before exact distance filtering.

Candidate roots are selected in deterministic coordinate order through fixed-size 128-root keyset pages. Each page is live-validated and document-loaded separately; the final visible roots and total matching-root/stack counts are accumulated globally across every page.

A completed snapshot whose root coordinate, block type, and content fingerprint match the persisted identity skips embedding and the replacement transaction.

Root updates use replacement transactions: write the complete new root snapshot and its item paths atomically or retain the prior committed revision. SQLite failures never expose half-indexed roots. Item rows are still replaced as a set; vectors for unchanged semantic descriptors come from `embeddings` rather than a new provider call.

Live validation requires the root to exist, stay the same block type, be loaded, be in radius, and pass protection. It does not require the live inventory fingerprint to match the last committed snapshot. Search documents are always that last committed snapshot, so a hopper line stays searchable while amounts and newly arrived item types can lag until the next committed replacement.

At startup, an incompatible configured embedding provider does not silently fall back. Kitsune fails search initialization with an actionable log message because mixing vector spaces would make scores invalid. Switching provider or provider version invalidates affected vectors and re-embeds each unique semantic descriptor once without touching Bukkit objects.

## Item Descriptors and Cosine Embeddings

### Canonical descriptor

Every non-empty leaf and nested container stack captures bounded representations of:

- material namespaced key and amount;
- plain-text display name and lore;
- enchantment keys and levels;
- attribute keys, operations, values, and equipment slots;
- damage, durability-related state, unbreakable state, and item flags;
- potion effects, book title/author, firework data, trim, and other supported standard item-meta fields when present;
- custom-model data available in Paper 1.21.4;
- persistent-data key names and scalar byte, integer, long, float, double, and bounded string values.

Binary arrays, nested persistent-data containers, and unbounded opaque values are not copied into the fallback descriptor. A registered custom-item provider may interpret its own data and emit a stable custom item ID and semantic tags.

### Built-in sparse provider

`builtin:sparse-v1` produces a sparse map from normalized feature key to finite positive weight. It derives features from:

- tokenized material key, display name, and lore;
- exact namespaced identifiers;
- Bukkit/Paper material tags and safe material properties;
- a versioned alias/category vocabulary such as `sword → weapon, melee`, `pickaxe → tool, mining`, and material-family traits;
- enchantments, attributes, potion effects, and custom-provider tags.

Query text uses the same normalization and alias/category vocabulary. Exact material and name features carry more weight than custom IDs/enchantments, which carry more than lore and broad traits. The profile's weights and vocabulary are code-versioned and contract-tested rather than individually configurable, preventing incompatible vectors under one provider version.

For sparse vectors `q` and `d`, score is:

```text
cos(q, d) = dot(q, d) / (norm(q) * norm(d))
```

A zero-norm query is invalid and returns a user-facing unsupported-query message. Scores are finite in `[0, 1]`. The initial minimum score is `0.30`; configuration may set it from `0.0` through `1.0`.

### Extension interfaces

- `EmbeddingProvider`: stable ID/version, descriptor batch embedding, query embedding, vector encoding, and cosine compatibility.
- `ItemFeatureProvider`: recognizes a custom stack snapshot and adds stable IDs/tags to the core descriptor.
- `NestedContentsProvider`: returns immutable child-stack snapshots and child labels for a recognized container item.

Providers register through Bukkit `ServicesManager`. Built-in providers execute first for baseline data; custom feature providers append data. Provider failures are isolated and rate-limited. An item falls back to core features and is never dropped solely because a custom provider failed.

The core nested providers support shulker-box block-state contents and bundle contents. Traversal is depth-first, cycle-safe, and bounded to depth 4, 4,096 visited stacks per root, configured paths per root, and bounded text/PDC lengths. An opaque custom backpack remains searchable as an item, but its contents are not invented.

## Protection Providers

### Contract

`BlockAccessProvider` is a public Bukkit service. For a player and a loaded canonical root, it returns:

- `ALLOW`: provider applies and grants access;
- `DENY`: provider applies and denies access;
- `NOT_APPLICABLE`: provider does not govern this root.

All checks execute on the server thread. Any `DENY` wins. If one or more providers allow and none deny, access is allowed. If every provider returns `NOT_APPLICABLE`, the ordinary unprotected root is allowed.

An exception from an applicable provider is treated as `DENY` for that root and query, with rate-limited diagnostics. If LWC is detected but its adapter cannot initialize, Kitsune disables search rather than running without the expected privacy boundary.

### Query-level privacy

The query pipeline is intentionally split:

1. SQLite returns nearby root identities and coordinates only.
2. The server thread removes unloaded, missing, changed, and inaccessible roots.
3. The background executor loads item descriptors/vectors only for allowed root IDs and ranks them.
4. The server thread revalidates root identity and access immediately before spawning each display.

Denied roots do not contribute to visible totals, truncation totals, empty-state wording, chat, logs sent to the player, scores, coordinates, or markers.

### Built-in and external integrations

The core plugin runs without a protection plugin. The LWCX adapter is an optional compile-only integration declared as a soft plugin relationship and isolated from the core search package.

Bolt and other protection or claim plugins integrate by registering `BlockAccessProvider`, normally from a separate bridge plugin. Kitsune does not guess APIs or import every server ecosystem dependency into its core artifact. LWCX coordinates and compatible versions must be verified from its maintained source before the Gradle dependency is pinned. Dependencies are never shaded into Kitsune.

## Search Pipeline

1. Parse command, validate player/permission/query, cancel prior session, and capture world/location/session token.
2. Determine intersecting loaded chunks for the configured radius.
3. Prioritize pending initial scans; wait asynchronously up to the warm-up deadline.
4. Query SQLite for root identities in the world/chunk bounds.
5. On the server thread, apply exact distance, live block/canonical-root validation, and the protection chain.
6. Load descriptors/vectors for allowed IDs and embed the query on the background executor.
7. Score leaves, discard scores below threshold, group paths by root, sort, and cap results.
8. On the server thread, revalidate session, world, root identity, and access.
9. Render chat and private displays, then schedule deterministic cleanup.

A no-match result clears the prior session and says `No accessible nearby storage matched <query>`. This wording deliberately does not distinguish no contents from protected contents.

## Configuration

The initial configuration exposes operational policy, not internal feature weights:

```yaml
search:
  radius: 32
  max-radius: 128
  minimum-score: 0.30
  max-results: 32
  max-paths-per-root: 16
  warmup-timeout-seconds: 3
markers:
  duration-seconds: 20
index:
  chunks-per-tick: 2
  roots-per-tick: 8
  reconciliation-period-ticks: 200
  maximum-depth: 4
  maximum-stacks-per-root: 4096
embedding:
  provider: builtin:sparse-v1
```

Startup validation rejects non-finite scores, inverted bounds, non-positive budgets, excessive recursion limits, and unsupported provider IDs. Unsafe configuration does not get silently clamped.

## Failure Handling

- SQLite open or migration failure disables Kitsune before listeners or commands become active.
- A root snapshot or transaction failure retains the prior committed root revision and queues bounded retry; repeated failures are rate-limited.
- A missing or stale root is omitted and queued for repair.
- An embedding query failure returns one search error and creates no partial markers.
- A custom descriptor/nesting failure falls back to baseline item data where safe.
- An applicable protection failure denies that root.
- Player logout, world change, new search, plugin disable, or session error cancels pending stages and removes displays.
- Shutdown stops acceptance of new work, cancels sessions, removes displays, drains already-committed index work within the plugin shutdown path, and closes SQLite.

## Component Boundaries

- `KitsunePlugin`: construction, lifecycle, configuration, and service registration.
- `KitsuneCommand`: syntax and user-facing result mapping only.
- `ContainerIndex`: discovery, root canonicalization, dirty queue, snapshots, and reconciliation.
- `IndexRepository`: SQLite migrations, transactions, candidate identities, and descriptor/vector loading.
- `ItemDescriber`: baseline immutable descriptor creation.
- `EmbeddingRegistry`: provider selection/versioning and vector operations.
- `NestedContentsRegistry`: bounded nested item traversal.
- `ProtectionRegistry`: provider discovery, precedence, and fail-closed behavior.
- `SearchService`: cancellable staged query pipeline.
- `MarkerRenderer`: private preconfigured TextDisplay creation/removal.
- `SearchSessionManager`: one-session invariant, tokens, expiry, and cleanup.

No background executor receives live Bukkit world, inventory, item, entity, block, or player objects; server-thread stages retain those objects only for the duration of validation, snapshotting, or rendering.

## Verification Strategy

### Unit contracts

- command parsing, multi-word queries, and invocation-scoped verbose flag;
- material/name/category/metadata feature derivation;
- sparse cosine values, zero vectors, thresholds, deterministic ties, and rank ordering;
- unknown custom-item fallback and provider failure isolation;
- shulker/bundle paths, limits, malformed nesting, and double-chest canonicalization;
- protection precedence and provider exceptions;
- session replacement, late async completion rejection, and cleanup transitions.

### SQLite contracts

- migration from an empty database;
- atomic root replacement and cascading deletion;
- world/chunk candidate selection plus exact radius filtering;
- provider-version invalidation and descriptor re-embedding;
- restart persistence and unloaded-root exclusion.

### Paper-facing contracts

- chunk discovery uses block/tile-entity state enumeration rather than block-volume iteration;
- inventory and block events dirty the expected canonical roots after committed transactions;
- snapshots contain no live Bukkit objects;
- unresolved loot tables are not generated by indexing;
- TextDisplays are configured default-hidden before spawn, shown only to the owner, non-persistent, and removed on every terminal session transition.

### RunPaper acceptance scenario

Run Paper 1.21.4 with Java 21 through the Gradle RunPaper task and exercise:

1. Server boots and enables Kitsune; `/kitsune` returns usage.
2. Representative loaded storage roots are indexed without scanning block volume.
3. Material (`diamond pickaxe`), semantic (`mining tool`), metadata (`mending`), and nested-shulker queries rank the intended roots.
4. Default search creates minimal private billboards and one aggregate chat line.
5. `--verbose` creates expanded billboards and nested chat trees.
6. A second player never sees the first player's displays.
7. Moving/removing an item changes the next search after the dirty-root update.
8. A stub denial provider, then the installed LWCX adapter in its supported test environment, produce no observable denied result.
9. New search, timeout, world change, logout, root invalidation, and plugin shutdown remove all marker entities.
10. Restart reuses SQLite data only after loaded roots and access are validated.

The first live checkpoint is deliberately smaller than the full acceptance scenario: boot Paper 1.21.4, index one loaded storage root, run `/kitsune diamond`, and observe one owner-only billboard. Subsequent behavior remains behind the interfaces above, so failures in SQLite, extraction, scoring, protection, or rendering can be isolated.
